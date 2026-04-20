/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * ${PROJECT_HOME}/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */
package de.bmarwell.proximo.pitido.war.listener;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import de.bmarwell.proximo.pitido.codecs.input.PcmDecoderFactory;
import de.bmarwell.proximo.pitido.core.LanguageMenuConfig;
import de.bmarwell.proximo.pitido.core.LanguageSelector;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer;
import de.bmarwell.proximo.pitido.war.media.SdpNegotiator;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Handles incoming SIP call sessions: INVITE, DTMF input (INFO), and call teardown (BYE).
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Answering incoming calls (INVITE)</li>
 *   <li>Playing the language-selection menu when multiple languages are available</li>
 *   <li>Processing DTMF digits (INFO) to let the caller select a language mid-playback</li>
 *   <li>Playing the time announcement in the selected language</li>
 *   <li>Tearing down the call session (BYE)</li>
 * </ul>
 *
 * <p>Language selection: if exactly one {@link LanguageFactory} is present on the classpath,
 * the announcement plays immediately.
 * With two or more, the selection menu cycles through all available languages in order of
 * {@link LanguageFactory#defaultOrder()}.
 * The caller may interrupt at any point by pressing a digit.
 *
 * <p>Audio interruption: when a DTMF digit arrives, the active call's {@link Future} is cancelled
 * with {@code mayInterruptIfRunning=true} so that blocking playback stops promptly.
 *
 * <p>Per-call state ({@link CallState}) is stored in {@link #activeCalls}, keyed by
 * {@link SipSession#getId()}, and cleaned up on BYE or Liberty shutdown.
 *
 * <p>The announcement loop runs until the caller hangs up (BYE), Liberty shuts down, or the
 * per-call maximum duration of two minutes has elapsed.
 * On timeout the server sends BYE to free the SIP line.
 * On shutdown, {@link #onShutdown()} sends BYE to all active calls and cancels their futures.
 */
@ApplicationScoped
public class SipCallHandler {

    private static final System.Logger LOGGER = System.getLogger(SipCallHandler.class.getName());

    /** Maximum duration of a single call before the server hangs up. */
    private static final Duration CALL_MAX_DURATION = Duration.ofMinutes(2);

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    SdpNegotiator sdpNegotiator;

    @Inject
    PcmDecoderFactory pcmDecoderFactory;

    @Inject
    SipCallBlacklist sipCallBlacklist;

    @Inject
    @ConfigProperty(name = "sip.languages.enabled", defaultValue = "")
    String enabledLanguagesConfig;

    @Resource
    ManagedExecutorService managedExecutorService;

    /** Holds per-call state while a call is active. */
    private record CallState(
            SipSession session,
            Future<?> callFuture,
            SequencedMap<Integer, LanguageFactory> menu,
            CallMedia media,
            Instant startTime,
            String callerIdentitySummary) {}

    private final ConcurrentHashMap<String, CallState> activeCalls = new ConcurrentHashMap<>();

    /**
     * Written by {@link #handleDtmf} before cancelling the call future;
     * read by the menu task's {@code finally} block to play the announcement.
     * {@link ConcurrentHashMap#putIfAbsent} ensures only the first digit wins.
     */
    private final ConcurrentHashMap<String, LanguageFactory> pendingSelections = new ConcurrentHashMap<>();

    /**
     * Handles an incoming INVITE.
     * Rejects with {@code 480 Temporarily Unavailable} when no language factory is registered.
     * Sends {@code 180 Ringing} immediately to free the Liberty SIP thread, then delegates to a
     * managed executor task: waits one second, negotiates SDP, and plays the time announcement
     * (single language) or the language-selection menu (multiple languages).
     * The one-second pre-accept pause gives the caller's handset time to connect before audio begins.
     */
    public void handleInvite(SipServletRequest req) throws IOException {
        String callId = req.getSession().getId();
        LOGGER.log(System.Logger.Level.DEBUG, "{0}Incoming call from [{1}]", callPrefix(callId), req.getFrom());

        if (this.sipCallBlacklist.isBlacklisted(req)) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "{0}Rejecting blacklisted call from [{1}]",
                    callPrefix(callId),
                    req.getFrom());
            req.createResponse(SipServletResponse.SC_FORBIDDEN).send();

            return;
        }

        var sorted = LanguageSelector.sorted(languageFactories);
        var menu = buildLanguageMenu(sorted);

        if (menu.isEmpty()) {
            rejectNoLanguage(req, callId);

            return;
        }

        req.createResponse(SipServletResponse.SC_RINGING).send();

        this.managedExecutorService.execute(() -> processAcceptedInvite(req, menu, callId));
    }

    private void processAcceptedInvite(
            SipServletRequest req, SequencedMap<Integer, LanguageFactory> menu, String callId) {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            if (menu.size() == 1) {
                acceptAndAnnounce(req, menu.firstEntry().getValue());

                return;
            }

            acceptAndPlayMenu(req, menu);
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "{0}Failed to accept call from [{1}]",
                    callPrefix(callId),
                    req.getFrom(),
                    ioException);
        }
    }

    private static void rejectNoLanguage(SipServletRequest req, String callId) throws IOException {
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Rejecting call from [{1}] with 480 — no language factories",
                callPrefix(callId),
                req.getFrom());
        LOGGER.log(
                System.Logger.Level.WARNING,
                "{0}No language factories registered — rejecting incoming call with 480 Temporarily Unavailable",
                callPrefix(callId));
        req.createResponse(SipServletResponse.SC_TEMPORARLY_UNAVAILABLE).send();
    }

    /**
     * Builds an ordered digit-to-factory menu from all discovered language factories,
     * applying the {@code sip.languages.enabled} filter if configured.
     *
     * <p>When the config is blank, all languages are included with digits assigned
     * sequentially by {@link LanguageFactory#defaultOrder()}.
     * When explicit digits are configured (e.g. {@code 1=de-DE,2=en-GB}), those digits
     * are used directly; unrecognised locale tags are silently skipped.
     */
    private SequencedMap<Integer, LanguageFactory> buildLanguageMenu(List<LanguageFactory> sorted) {
        SequencedMap<Integer, String> configured = LanguageMenuConfig.parse(this.enabledLanguagesConfig);

        if (configured.isEmpty()) {
            LinkedHashMap<Integer, LanguageFactory> menu = new LinkedHashMap<>();

            for (int index = 0; index < sorted.size(); index++) {
                menu.put(index + 1, sorted.get(index));
            }

            return menu;
        }

        LinkedHashMap<Integer, LanguageFactory> menu = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> entry : configured.entrySet()) {
            sorted.stream()
                    .filter(factory -> factory.locale().toLanguageTag().equals(entry.getValue()))
                    .findFirst()
                    .ifPresent(factory -> menu.put(entry.getKey(), factory));
        }

        return menu;
    }

    private void acceptAndAnnounce(SipServletRequest req, LanguageFactory factory) throws IOException {
        SipSession session = req.getSession();
        String sessionId = session.getId();
        LOGGER.log(System.Logger.Level.DEBUG, "{0}Accepting call from [{1}]", callPrefix(sessionId), req.getFrom());
        CallMedia media = sdpNegotiator.negotiate(req);
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}Call accepted — language [{1}], codec [{2}]",
                callPrefix(sessionId),
                factory.displayName(),
                media.codec().sdpName());
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);
        Instant startTime = Instant.now();
        String callerIdentitySummary = buildCallerIdentitySummary(req);

        LinkedHashMap<Integer, LanguageFactory> singleMenu = new LinkedHashMap<>();
        singleMenu.put(1, factory);

        Future<?> callFuture = this.managedExecutorService.submit(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }

            playAnnouncementLoop(session, player, factory, sessionId, media, callerIdentitySummary);
        });

        activeCalls.put(
                sessionId, new CallState(session, callFuture, singleMenu, media, startTime, callerIdentitySummary));
    }

    private void acceptAndPlayMenu(SipServletRequest req, SequencedMap<Integer, LanguageFactory> menu)
            throws IOException {
        SipSession session = req.getSession();
        String sessionId = session.getId();
        LOGGER.log(System.Logger.Level.DEBUG, "{0}Accepting call from [{1}]", callPrefix(sessionId), req.getFrom());
        CallMedia media = sdpNegotiator.negotiate(req);
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}Call accepted — language-selection menu ({1} languages), codec [{2}]",
                callPrefix(sessionId),
                menu.size(),
                media.codec().sdpName());
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);
        Instant startTime = Instant.now();
        String callerIdentitySummary = buildCallerIdentitySummary(req);
        Future<?> callFuture = this.managedExecutorService.submit(
                () -> runMenu(session, player, menu, sessionId, media, startTime, callerIdentitySummary));
        activeCalls.put(sessionId, new CallState(session, callFuture, menu, media, startTime, callerIdentitySummary));
    }

    private void runMenu(
            SipSession session,
            AudioPlayer player,
            SequencedMap<Integer, LanguageFactory> menu,
            String sessionId,
            CallMedia media,
            Instant startTime,
            String callerIdentitySummary) {
        try {
            Thread.sleep(1_000);
            runMenuLoop(player, menu, sessionId);
        } catch (InterruptedException interruptedException) {
            Thread.interrupted(); // consume interrupt; the chosen language is played in finally
        } finally {
            LanguageFactory chosen = pendingSelections.remove(sessionId);

            if (chosen != null) {
                // The same managed task continues into the announcement loop;
                // the Future already stored in activeCalls covers this entire execution.
                playAnnouncementLoop(session, player, chosen, sessionId, media, callerIdentitySummary);
            } else {
                activeCalls.remove(sessionId);
                closeMedia(media);
            }
        }
    }

    private static void runMenuLoop(AudioPlayer player, SequencedMap<Integer, LanguageFactory> menu, String sessionId)
            throws InterruptedException {
        while (true) {
            for (Map.Entry<Integer, LanguageFactory> entry : menu.entrySet()) {
                playSelectionPhrase(player, entry.getValue(), entry.getKey(), sessionId);
            }
        }
    }

    private static void playSelectionPhrase(AudioPlayer player, LanguageFactory factory, int slot, String sessionId)
            throws InterruptedException {
        try {
            LanguageSelectionAnnouncement announcement = factory.createLanguageSelectionAnnouncement(player);
            announcement.playSelectionPhrase(slot);
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Could not play selection phrase for [{1}] at slot {2} — skipping",
                    callPrefix(sessionId),
                    factory.displayName(),
                    slot,
                    ioException);
        }
    }

    /**
     * Handles a SIP INFO message carrying a DTMF digit.
     * Records the caller's language choice and interrupts the menu thread so playback stops
     * immediately.
     * A second digit for the same session is ignored ({@code putIfAbsent}).
     */
    public void handleDtmf(SipServletRequest req) throws IOException {
        req.createResponse(SipServletResponse.SC_OK).send();
        String sessionId = req.getSession().getId();
        CallState callState = activeCalls.get(sessionId);

        if (callState == null) {
            return;
        }

        int digit = parseDtmfDigit(req);

        if (digit < 1) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "{0}DTMF digit unrecognised or out of range — ignoring",
                    callPrefix(sessionId));
            return;
        }

        LOGGER.log(System.Logger.Level.DEBUG, "{0}DTMF digit [{1}] received", callPrefix(sessionId), digit);
        LanguageFactory chosen = callState.menu().get(digit);

        if (chosen == null) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "{0}DTMF digit [{1}] does not match any language slot — ignoring",
                    callPrefix(sessionId),
                    digit);
            return;
        }

        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}DTMF digit [{1}] selected language [{2}]",
                callPrefix(sessionId),
                digit,
                chosen.displayName());
        pendingSelections.putIfAbsent(sessionId, chosen);
        callState.callFuture().cancel(true);
    }

    /**
     * Handles BYE — cancels any running menu or announcement task, closes the RTP socket,
     * removes per-call state, and sends {@code 200 OK}.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        LOGGER.log(System.Logger.Level.DEBUG, "{0}BYE received from [{1}]", callPrefix(sessionId), req.getFrom());
        CallState callState = activeCalls.remove(sessionId);

        if (callState == null) {
            req.createResponse(SipServletResponse.SC_OK).send();
            return;
        }

        callState.callFuture().cancel(true);
        closeMedia(callState.media());
        Duration callDuration = Duration.between(callState.startTime(), Instant.now());
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}Call ended — duration {1}s",
                callPrefix(sessionId),
                callDuration.toSeconds());
        pendingSelections.remove(sessionId);
        req.createResponse(SipServletResponse.SC_OK).send();
    }

    /**
     * Plays the time announcement repeatedly until interrupted.
     *
     * <p>Interrupted on BYE received (via {@link #handleBye}) or Liberty shutdown
     * (via {@link #onShutdown()}).
     * Also exits when the call has exceeded {@link #CALL_MAX_DURATION}; in that case a BYE is
     * sent to the caller to release the SIP line.
     *
     * <p>Each iteration plays one full announcement cycle immediately.
     * The announcement itself schedules silence until the next timed boundary via
     * {@link de.bmarwell.proximo.pitido.spi.AbstractTimeAnnouncement#playSilenceUntil}.
     * I/O errors in a single cycle are logged and the loop continues;
     * only an {@link InterruptedException} or a timeout exits the loop.
     *
     * <p>On exit, removes this call from {@link #activeCalls} and closes the media socket.
     */
    private void playAnnouncementLoop(
            SipSession session,
            AudioPlayer player,
            LanguageFactory factory,
            String sessionId,
            CallMedia media,
            String callerIdentitySummary) {
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}Announcement loop starting — language [{1}]",
                callPrefix(sessionId),
                factory.displayName());
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Announcement loop context: codec=[{1}], caller={2}",
                callPrefix(sessionId),
                media.codec().sdpName(),
                callerIdentitySummary);

        Instant deadline = Instant.now().plus(CALL_MAX_DURATION);

        try {
            while (Instant.now().isBefore(deadline)) {
                if (Instant.now().isAfter(deadline)) {
                    break;
                }

                TimeAnnouncement announcement = factory.createTimeAnnouncement(player, Clock.systemDefaultZone());

                try {
                    var receipt = announcement.announce();
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "{0}Announcement complete; played {1} file(s)",
                            callPrefix(sessionId),
                            receipt.fileNames().size());
                    LOGGER.log(Level.TRACE, "{0}Announcement complete; played: {1}.", callPrefix(sessionId), receipt);
                    player.playSilence(Duration.ofSeconds(1));
                } catch (IOException ioException) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "{0}Time announcement failed for language [{1}]: {2}",
                            callPrefix(sessionId),
                            factory.displayName(),
                            ioException.getMessage(),
                            ioException);
                }
            }

            LOGGER.log(
                    System.Logger.Level.INFO, "{0}Maximum call duration reached — hanging up", callPrefix(sessionId));
            sendBye(session);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.DEBUG, "{0}Announcement loop interrupted", callPrefix(sessionId));
        } finally {
            activeCalls.remove(sessionId);
            closeMedia(media);
        }
    }

    /**
     * Cancels all active call tasks and sends BYE when Liberty shuts down.
     * Called automatically by CDI before the bean is destroyed.
     */
    @PreDestroy
    public void onShutdown() {
        LOGGER.log(
                System.Logger.Level.INFO,
                "Liberty shutting down — sending BYE to {0} active call(s)",
                activeCalls.size());
        activeCalls.values().forEach(callState -> {
            callState.callFuture().cancel(true);
            sendBye(callState.session());
        });
        activeCalls.clear();
    }

    private static void closeMedia(CallMedia media) {
        if (!media.localSocket().isClosed()) {
            media.localSocket().close();
        }
    }

    private static String buildCallerIdentitySummary(SipServletRequest req) {
        return String.format(
                "from=[%s], to=[%s], requestUri=[%s], callId=[%s], pAssertedIdentity=[%s], remotePartyId=[%s], "
                        + "pPreferredIdentity=[%s], privacy=[%s], diversion=[%s], historyInfo=[%s], contact=[%s], "
                        + "via=[%s], userAgent=[%s]",
                req.getFrom(),
                req.getTo(),
                req.getRequestURI(),
                normaliseHeader(req.getHeader("Call-ID")),
                normaliseHeader(req.getHeader("P-Asserted-Identity")),
                normaliseHeader(req.getHeader("Remote-Party-ID")),
                normaliseHeader(req.getHeader("P-Preferred-Identity")),
                normaliseHeader(req.getHeader("Privacy")),
                normaliseHeader(req.getHeader("Diversion")),
                normaliseHeader(req.getHeader("History-Info")),
                normaliseHeader(req.getHeader("Contact")),
                normaliseHeader(req.getHeader("Via")),
                normaliseHeader(req.getHeader("User-Agent")));
    }

    private static String normaliseHeader(String value) {
        if (value == null) {
            return "<absent>";
        }

        if (value.isBlank()) {
            return "<blank>";
        }

        return value;
    }

    private static void sendBye(SipSession session) {
        if (!session.isValid()) {
            return;
        }
        try {
            session.createRequest("BYE").send();
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Failed to send BYE on shutdown",
                    callPrefix(session.getId()),
                    ioException);
        }
    }

    private static String callPrefix(String callId) {
        return "[callId=" + callId + "] ";
    }

    /**
     * Parses a DTMF digit from a SIP INFO body.
     * Supports {@code application/dtmf-relay} ({@code Signal=N\r\nDuration=…}) and plain digit
     * bodies.
     * Returns {@code -1} for any unrecognised format.
     */
    private static int parseDtmfDigit(SipServletRequest req) {
        try {
            Object content = req.getContent();
            String body = content instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : String.valueOf(content);
            if (body.contains("Signal=")) {
                return body.lines()
                        .filter(line -> line.startsWith("Signal="))
                        .findFirst()
                        .map(line -> line.replace("Signal=", "").strip())
                        .map(Integer::parseInt)
                        .orElse(-1);
            }
            return Integer.parseInt(body.strip());
        } catch (Exception ignored) {
            return -1;
        }
    }
}
