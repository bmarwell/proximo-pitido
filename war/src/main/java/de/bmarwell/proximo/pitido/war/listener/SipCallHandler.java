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
import de.bmarwell.proximo.pitido.core.LanguageSelector;
import de.bmarwell.proximo.pitido.core.media.PcmDecoderFactory;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer;
import de.bmarwell.proximo.pitido.war.media.SdpNegotiator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@link SipSession#getId()}, and cleaned up on BYE or after the announcement completes.
 */
@ApplicationScoped
public class SipCallHandler {

    private static final System.Logger LOGGER = System.getLogger(SipCallHandler.class.getName());

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    SdpNegotiator sdpNegotiator;

    @Inject
    PcmDecoderFactory pcmDecoderFactory;

    /** Holds per-call state while a call is active. */
    private record CallState(Thread menuThread, List<LanguageFactory> sorted, CallMedia media) {}

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
        var sorted = LanguageSelector.sorted(languageFactories);

        if (sorted.isEmpty()) {
            LOGGER.log(
                    System.Logger.Level.WARNING, "No language factories registered, rejecting call {0}", req.getFrom());
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
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Only one language factory registered, playing immediately to {0}",
                    req.getFrom());
            acceptAndAnnounce(req, sorted.getFirst());

            return;
        }
        acceptAndPlayMenu(req, sorted);
    }

    private static void rejectNoLanguage(SipServletRequest req) throws IOException {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "No language factories registered — rejecting call from [{0}] with 480",
                req.getFrom());
        req.createResponse(SipServletResponse.SC_TEMPORARLY_UNAVAILABLE).send();
    }

    private void acceptAndAnnounce(SipServletRequest req, LanguageFactory factory) throws IOException {
        CallMedia media = sdpNegotiator.negotiate(req);
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        SipSession session = req.getSession();
        String sessionId = session.getId();
        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);

        Thread thread = Thread.ofVirtual().name("call-announce-" + sessionId).start(() -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }

            playAnnouncementAndHangUp(session, player, factory, media);
        });

        activeCalls.put(sessionId, new CallState(thread, List.of(factory), media));
    }

    private void acceptAndPlayMenu(SipServletRequest req, List<LanguageFactory> sorted) throws IOException {
        CallMedia media = sdpNegotiator.negotiate(req);
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        SipSession session = req.getSession();
        String sessionId = session.getId();
        AudioPlayer player = new RtpAudioPlayer(media, this.pcmDecoderFactory);
        Thread menuThread = Thread.ofVirtual()
                .name("call-menu-" + sessionId)
                .start(() -> runMenu(session, player, sorted, sessionId, media));
        activeCalls.put(sessionId, new CallState(menuThread, sorted, media));
    }

    private void runMenu(
            SipSession session, AudioPlayer player, List<LanguageFactory> sorted, String sessionId, CallMedia media) {
        try {
            Thread.sleep(1_000);
            runMenuLoop(player, sorted);
        } catch (InterruptedException interruptedException) {
            Thread.interrupted(); // consume interrupt; the chosen language is played in finally
        } finally {
            activeCalls.remove(sessionId);
            LanguageFactory chosen = pendingSelections.remove(sessionId);

            if (chosen != null) {
                playAnnouncementAndHangUp(session, player, chosen, media);
            } else {
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
                    slot);
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
            return;
        }
        Optional<LanguageFactory> chosen = LanguageSelector.fromDigit(callState.sorted(), digit);
        if (chosen.isEmpty()) {
            return;
        }
        pendingSelections.putIfAbsent(sessionId, chosen.get());
        callState.menuThread().interrupt();
    }

    /**
     * Handles BYE — interrupts any running menu thread, closes the RTP socket,
     * removes per-call state, and sends {@code 200 OK}.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        CallState callState = activeCalls.remove(sessionId);

        if (callState != null) {
            callState.menuThread().interrupt();
            closeMedia(callState.media());
        }

        pendingSelections.remove(sessionId);
        req.createResponse(SipServletResponse.SC_OK).send();
    }

    private static void playAnnouncementAndHangUp(
            SipSession session, AudioPlayer player, LanguageFactory factory, CallMedia media) {
        TimeAnnouncement announcement = factory.createTimeAnnouncement(player, Clock.systemDefaultZone());

        try {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Playing time announcement in [{0}] using [{1}] to {2}",
                    factory.displayName(),
                    announcement,
                    session.getRemoteParty());
            var receipt = announcement.announce();
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Announcement complete; played {0} file(s)",
                    receipt.fileNames().size());
        } catch (InterruptedException interruptedException) {
            // Caller hung up; the BYE handler closes the session.
            Thread.currentThread().interrupt();
            return;
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Time announcement failed for language [{0}]: {1}",
                    factory.displayName(),
                    ioException.getMessage(),
                    ioException);
        } finally {
            closeMedia(media);
        }

        LOGGER.log(System.Logger.Level.INFO, "Hanging up call to {0}", session.getRemoteParty());
        sendBye(session);
    }

    private static void closeMedia(CallMedia media) {
        if (!media.localSocket().isClosed()) {
            media.localSocket().close();
        }
    }

    private static void sendBye(SipSession session) {
        if (!session.isValid()) {
            return;
        }
        try {
            session.createRequest("BYE").send();
        } catch (IOException ioException) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to send BYE after time announcement", ioException);
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
