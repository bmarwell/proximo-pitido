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

import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;

/**
 * Manages the lifecycle of active SIP call sessions.
 *
 * <p>Stores per-call {@link CallState}, records pending DTMF language selections, and handles
 * orderly teardown on BYE or Liberty shutdown.
 * All other handler beans read and write call state through this class.
 *
 * <p>Telekom (and other IMS providers) may fork the same INVITE over two parallel TCP paths.
 * The SIP Call-ID is the same on both forks.
 * {@code claimedSipCallIds} tracks which SIP Call-IDs are currently being handled so that the
 * second fork can be rejected with {@code 486 Busy Here} before it creates a second session.
 */
@ApplicationScoped
public class CallSessionManager {

    private static final System.Logger LOGGER = System.getLogger(CallSessionManager.class.getName());

    private final ConcurrentHashMap<String, CallState> activeCalls = new ConcurrentHashMap<>();

    /**
     * SIP Call-IDs currently being handled, claimed atomically before {@code 180 Ringing} is sent.
     */
    private final Set<String> claimedSipCallIds = ConcurrentHashMap.newKeySet();

    /**
     * Language selections written by the DTMF dispatcher; read (and cleared) by the menu runner
     * once the menu playback is interrupted.
     */
    private final ConcurrentHashMap<String, LanguageFactory> pendingSelections = new ConcurrentHashMap<>();

    /**
     * Atomically claims a SIP Call-ID.
     * Returns {@code true} if the claim succeeded (no other session was already handling this
     * Call-ID), or {@code false} if the Call-ID was already claimed — indicating a duplicate
     * INVITE fork that should be rejected.
     */
    boolean tryClaimSipCallId(String sipCallId) {
        return this.claimedSipCallIds.add(sipCallId);
    }

    /** Releases a previously claimed SIP Call-ID. */
    void releaseSipCallId(String sipCallId) {
        this.claimedSipCallIds.remove(sipCallId);
    }

    void register(String sessionId, CallState state) {
        this.activeCalls.put(sessionId, state);
    }

    CallState get(String sessionId) {
        return this.activeCalls.get(sessionId);
    }

    CallState remove(String sessionId) {
        CallState removed = this.activeCalls.remove(sessionId);

        if (removed != null) {
            releaseSipCallId(removed.sipCallId());
        }

        return removed;
    }

    /**
     * Records the language selected by a DTMF digit.
     * Only the first digit wins ({@code putIfAbsent}).
     */
    void recordPendingSelection(String sessionId, LanguageFactory factory) {
        this.pendingSelections.putIfAbsent(sessionId, factory);
    }

    /**
     * Returns and removes the pending language selection for the given session, or {@code null}
     * if no selection was recorded.
     */
    LanguageFactory takePendingSelection(String sessionId) {
        return this.pendingSelections.remove(sessionId);
    }

    /**
     * Handles a BYE: cancels futures, closes the RTP socket, logs call duration, and sends 200 OK.
     *
     * <p>Only the UDP socket is closed here — closing it from any thread is safe and immediately
     * signals the RTP sender to stop.
     * The codec (which may hold a confined FFM {@link java.lang.foreign.Arena}) is closed by the
     * announcement-loop or menu-runner {@code finally} block, which runs on the thread that created
     * the arena.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}BYE received from [{1}]",
                SipCallHeaders.callPrefix(sessionId),
                req.getFrom());
        CallState callState = this.remove(sessionId);

        if (callState == null) {
            req.createResponse(SipServletResponse.SC_OK).send();
            return;
        }

        callState.callFuture().cancel(true);
        callState.receiverFuture().cancel(true);
        String codecName = callState.media().codec().metadata().sdpName();
        closeSocket(callState.media());
        Duration callDuration = Duration.between(callState.startTime(), Instant.now());
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}Call ended — from=[{1}], codec=[{2}], duration={3}s",
                SipCallHeaders.callPrefix(sessionId),
                req.getFrom(),
                codecName,
                callDuration.toSeconds());
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Caller identity details: {1}",
                SipCallHeaders.callPrefix(sessionId),
                callState.callerIdentitySummary());
        this.pendingSelections.remove(sessionId);
        req.createResponse(SipServletResponse.SC_OK).send();
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
                this.activeCalls.size());
        this.activeCalls.values().forEach(callState -> {
            callState.callFuture().cancel(true);
            callState.receiverFuture().cancel(true);
            sendBye(callState.session());
        });
        this.activeCalls.clear();
        this.claimedSipCallIds.clear();
    }

    /**
     * Closes the RTP socket only.
     * Safe to call from any thread; the UDP socket has no thread-confinement requirement.
     * Closing it immediately signals the RTP sender to stop sending packets.
     */
    static void closeSocket(CallMedia media) {
        if (!media.localSocket().isClosed()) {
            media.localSocket().close();
        }
    }

    /**
     * Closes the RTP socket and the codec.
     *
     * <p><strong>Must</strong> be called from the thread that created the codec's
     * {@link java.lang.foreign.Arena} (the announcement-loop or menu-runner executor thread).
     * Calling this from a different thread (e.g. the Liberty SIP thread) will throw
     * {@link java.lang.WrongThreadException} for native codecs that use a confined arena.
     */
    static void closeMedia(CallMedia media) {
        closeSocket(media);

        /*
         * The per-call RtpCodec is closed by the announcement or menu-runner thread
         * in its try-finally block in CallAcceptor.java. The factory (CodecFactory)
         * is an @ApplicationScoped CDI singleton and must not be closed.
         */
    }

    static void sendBye(SipSession session) {
        if (!session.isValid()) {
            return;
        }

        try {
            session.createRequest("BYE").send();
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Failed to send BYE",
                    SipCallHeaders.callPrefix(session.getId()),
                    ioException);
        }
    }
}
