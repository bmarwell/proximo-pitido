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
 */
@ApplicationScoped
public class CallSessionManager {

    private static final System.Logger LOGGER = System.getLogger(CallSessionManager.class.getName());

    private final ConcurrentHashMap<String, CallState> activeCalls = new ConcurrentHashMap<>();

    /**
     * Language selections written by the DTMF dispatcher; read (and cleared) by the menu runner
     * once the menu playback is interrupted.
     */
    private final ConcurrentHashMap<String, LanguageFactory> pendingSelections = new ConcurrentHashMap<>();

    void register(String sessionId, CallState state) {
        this.activeCalls.put(sessionId, state);
    }

    CallState get(String sessionId) {
        return this.activeCalls.get(sessionId);
    }

    CallState remove(String sessionId) {
        return this.activeCalls.remove(sessionId);
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
     * Handles a BYE: cancels futures, closes media, logs call duration, and sends 200 OK.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}BYE received from [{1}]",
                SipCallHeaders.callPrefix(sessionId),
                req.getFrom());
        CallState callState = this.activeCalls.remove(sessionId);

        if (callState == null) {
            req.createResponse(SipServletResponse.SC_OK).send();
            return;
        }

        callState.callFuture().cancel(true);
        callState.receiverFuture().cancel(true);
        String codecName = callState.media().codec().sdpName();
        closeMedia(callState.media());
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
                "{0}Caller identity: {1}",
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
    }

    static void closeMedia(CallMedia media) {
        if (!media.localSocket().isClosed()) {
            media.localSocket().close();
        }

        try {
            media.codec().close();
        } catch (Exception closeException) {
            LOGGER.log(System.Logger.Level.WARNING, "Error closing codec after call", closeException);
        }
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
