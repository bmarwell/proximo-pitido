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
import de.bmarwell.proximo.pitido.core.LanguageSelector;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer;
import de.bmarwell.proximo.pitido.war.media.SdpNegotiator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

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
 * <p>Audio interruption: when a DTMF digit arrives, the active menu thread is interrupted via
 * {@link Thread#interrupt()} so that blocking playback stops promptly.
 *
 * <p>Per-call state ({@link CallState}) is stored in {@link #activeCalls}, keyed by
 * {@link SipSession#getId()}, and cleaned up on BYE or Liberty shutdown.
 *
 * <p>The announcement loop runs until the caller hangs up (BYE), Liberty shuts down, or the
 * per-call maximum duration of two minutes has elapsed.
 * On timeout the server sends BYE to free the SIP line.
 * On shutdown, {@link #onShutdown()} sends BYE to all active calls and interrupts their threads.
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

    /** Holds per-call state while a call is active. */
    private record CallState(
            SipSession session, Thread menuThread, List<LanguageFactory> sorted, CallMedia media, Instant startTime) {}

    private final ConcurrentHashMap<String, CallState> activeCalls = new ConcurrentHashMap<>();

    /**
     * Written by {@link #handleDtmf} before interrupting the menu thread;
     * read by the menu thread's {@code finally} block to play the announcement.
     * {@link ConcurrentHashMap#putIfAbsent} ensures only the first digit wins.
     */
    private final ConcurrentHashMap<String, LanguageFactory> pendingSelections = new ConcurrentHashMap<>();

    /**
     * Handles an incoming INVITE.
     * Rejects with {@code 480 Temporarily Unavailable} when no language factory is registered.
     * Answers with {@code 200 OK} otherwise (after a short pre-accept pause), then plays the time
     * announcement (single language) or the language-selection menu (multiple languages) on a virtual
     * thread.
     * A further pause after accepting gives the caller's handset time to connect before audio begins.
     */
    public void handleInvite(SipServletRequest req) throws IOException {
        LOGGER.log(System.Logger.Level.DEBUG, "Incoming call from [{0}]", req.getFrom());
        logCallerIdentityDebug(req);
        var sorted = LanguageSelector.sorted(languageFactories);

        if (sorted.isEmpty()) {
            rejectNoLanguage(req);

            return;
        }

        try {
            Thread.sleep(1_000);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
        }

        if (sorted.size() == 1) {
            acceptAndAnnounce(req, sorted.getFirst());

            return;
        }

        acceptAndPlayMenu(req, sorted);
    }

    private static void rejectNoLanguage(SipServletRequest req) throws IOException {
        LOGGER.log(
                System.Logger.Level.DEBUG, "Rejecting call from [{0}] with 480 — no language factories", req.getFrom());
        LOGGER.log(
                System.Logger.Level.WARNING,
                "No language factories registered — rejecting incoming call with 480 Temporarily Unavailable");
        req.createResponse(SipServletResponse.SC_TEMPORARLY_UNAVAILABLE).send();
    }

    private void acceptAndAnnounce(SipServletRequest req, LanguageFactory factory) throws IOException {
        LOGGER.log(System.Logger.Level.DEBUG, "Accepting call from [{0}]", req.getFrom());
        CallMedia media = sdpNegotiator.negotiate(req);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Call accepted — language [{0}], codec [{1}]",
                factory.displayName(),
                media.codec().sdpName());
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        SipSession session = req.getSession();
        String sessionId = session.getId();
        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);
        Instant startTime = Instant.now();

        Thread thread = Thread.ofVirtual().name("call-announce-" + sessionId).start(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }

            playAnnouncementLoop(session, player, factory, sessionId, media);
        });

        activeCalls.put(sessionId, new CallState(session, thread, List.of(factory), media, startTime));
    }

    private void acceptAndPlayMenu(SipServletRequest req, List<LanguageFactory> sorted) throws IOException {
        LOGGER.log(System.Logger.Level.DEBUG, "Accepting call from [{0}]", req.getFrom());
        CallMedia media = sdpNegotiator.negotiate(req);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Call accepted — language-selection menu ({0} languages), codec [{1}]",
                sorted.size(),
                media.codec().sdpName());
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        SipSession session = req.getSession();
        String sessionId = session.getId();
        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);
        Instant startTime = Instant.now();
        Thread menuThread = Thread.ofVirtual()
                .name("call-menu-" + sessionId)
                .start(() -> runMenu(session, player, sorted, sessionId, media, startTime));
        activeCalls.put(sessionId, new CallState(session, menuThread, sorted, media, startTime));
    }

    private void runMenu(
            SipSession session,
            AudioPlayer player,
            List<LanguageFactory> sorted,
            String sessionId,
            CallMedia media,
            Instant startTime) {
        try {
            Thread.sleep(1_000);
            runMenuLoop(player, sorted);
        } catch (InterruptedException interruptedException) {
            Thread.interrupted(); // consume interrupt; the chosen language is played in finally
        } finally {
            LanguageFactory chosen = pendingSelections.remove(sessionId);

            if (chosen != null) {
                // Re-register with the current thread so @PreDestroy can interrupt the
                // announcement loop and send BYE.
                activeCalls.put(
                        sessionId, new CallState(session, Thread.currentThread(), List.of(chosen), media, startTime));
                playAnnouncementLoop(session, player, chosen, sessionId, media);
            } else {
                activeCalls.remove(sessionId);
                closeMedia(media);
            }
        }
    }

    private static void runMenuLoop(AudioPlayer player, List<LanguageFactory> sorted) throws InterruptedException {
        while (true) {
            for (int slot = 1; slot <= sorted.size(); slot++) {
                playSelectionPhrase(player, sorted.get(slot - 1), slot);
            }
        }
    }

    private static void playSelectionPhrase(AudioPlayer player, LanguageFactory factory, int slot)
            throws InterruptedException {
        try {
            LanguageSelectionAnnouncement announcement = factory.createLanguageSelectionAnnouncement(player);
            announcement.playSelectionPhrase(slot);
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not play selection phrase for [{0}] at slot {1} — skipping",
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
            LOGGER.log(System.Logger.Level.DEBUG, "DTMF digit unrecognised or out of range — ignoring");
            return;
        }

        LOGGER.log(System.Logger.Level.DEBUG, "DTMF digit [{0}] received for session [{1}]", digit, sessionId);
        Optional<LanguageFactory> chosen = LanguageSelector.fromDigit(callState.sorted(), digit);

        if (chosen.isEmpty()) {
            LOGGER.log(
                    System.Logger.Level.DEBUG, "DTMF digit [{0}] does not match any language slot — ignoring", digit);
            return;
        }

        LOGGER.log(
                System.Logger.Level.INFO,
                "DTMF digit [{0}] selected language [{1}]",
                digit,
                chosen.get().displayName());
        pendingSelections.putIfAbsent(sessionId, chosen.get());
        callState.menuThread().interrupt();
    }

    /**
     * Handles BYE — interrupts any running menu or announcement thread, closes the RTP socket,
     * removes per-call state, and sends {@code 200 OK}.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        LOGGER.log(System.Logger.Level.DEBUG, "BYE received from [{0}]", req.getFrom());
        CallState callState = activeCalls.remove(sessionId);

        if (callState == null) {
            req.createResponse(SipServletResponse.SC_OK).send();
            return;
        }

        callState.menuThread().interrupt();
        closeMedia(callState.media());
        Duration callDuration = Duration.between(callState.startTime(), Instant.now());
        LOGGER.log(System.Logger.Level.INFO, "Call ended — duration {0}s", callDuration.toSeconds());
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
     * <p>Each iteration waits for the next announcement slot ({@code second % 10 == 2}), then
     * plays one full announcement cycle.
     * I/O errors in a single cycle are logged and the loop continues;
     * only an {@link InterruptedException} or a timeout exits the loop.
     *
     * <p>On exit, removes this call from {@link #activeCalls} and closes the media socket.
     */
    private void playAnnouncementLoop(
            SipSession session, AudioPlayer player, LanguageFactory factory, String sessionId, CallMedia media) {
        LOGGER.log(System.Logger.Level.INFO, "Announcement loop starting — language [{0}]", factory.displayName());
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Announcement loop starting — language [{0}], remote [{1}]",
                factory.displayName(),
                session.getRemoteParty());

        Instant deadline = Instant.now().plus(CALL_MAX_DURATION);

        try {
            while (Instant.now().isBefore(deadline)) {
                waitForNextAnnouncementSlot();

                if (Instant.now().isAfter(deadline)) {
                    break;
                }

                TimeAnnouncement announcement = factory.createTimeAnnouncement(player, Clock.systemDefaultZone());

                try {
                    var receipt = announcement.announce();
                    LOGGER.log(
                            System.Logger.Level.DEBUG,
                            "Announcement complete; played {0} file(s)",
                            receipt.fileNames().size());
                } catch (IOException ioException) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Time announcement failed for language [{0}]: {1}",
                            factory.displayName(),
                            ioException.getMessage(),
                            ioException);
                }
            }

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Maximum call duration reached for session [{0}] — hanging up",
                    sessionId);
            sendBye(session);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.DEBUG, "Announcement loop interrupted for session [{0}]", sessionId);
        } finally {
            activeCalls.remove(sessionId);
            closeMedia(media);
        }
    }

    /**
     * Blocks until the wall-clock second satisfies {@code second % 10 == 2}.
     * Polls every 100 ms.
     *
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    private static void waitForNextAnnouncementSlot() throws InterruptedException {
        while (ZonedDateTime.now().getSecond() % 10 != 2) {
            Thread.sleep(100);
        }
    }

    /**
     * Sends BYE to all active calls and interrupts their threads when Liberty shuts down.
     * Called automatically by CDI before the bean is destroyed.
     */
    @PreDestroy
    public void onShutdown() {
        LOGGER.log(
                System.Logger.Level.INFO,
                "Liberty shutting down — sending BYE to {0} active call(s)",
                activeCalls.size());
        activeCalls.values().forEach(callState -> {
            callState.menuThread().interrupt();
            sendBye(callState.session());
        });
        activeCalls.clear();
    }

    private static void closeMedia(CallMedia media) {
        if (!media.localSocket().isClosed()) {
            media.localSocket().close();
        }
    }

    private static void logCallerIdentityDebug(SipServletRequest req) {
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Caller identity: from=[{0}], to=[{1}], requestUri=[{2}], callId=[{3}], pAssertedIdentity=[{4}],"
                        + " remotePartyId=[{5}], pPreferredIdentity=[{6}], privacy=[{7}], diversion=[{8}],"
                        + " historyInfo=[{9}], contact=[{10}], via=[{11}], userAgent=[{12}]",
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
            LOGGER.log(System.Logger.Level.WARNING, "Failed to send BYE on shutdown", ioException);
        }
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
