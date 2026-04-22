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
import de.bmarwell.proximo.pitido.war.media.RtpDtmfReceiver;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/**
 * Handles DTMF digit input arriving via both SIP INFO messages (RFC 2976) and RFC 2833
 * telephone-event RTP packets.
 *
 * <p>When a valid digit arrives, the corresponding language is recorded in
 * {@link CallSessionManager} and the menu playback future is cancelled so that
 * playback stops immediately.
 * Only the first digit wins; subsequent digits for the same session are ignored.
 */
@ApplicationScoped
public class DtmfDispatcher {

    private static final System.Logger LOGGER = System.getLogger(DtmfDispatcher.class.getName());

    @Inject
    CallSessionManager callSessionManager;

    @Resource(lookup = "concurrent/ioExecutor")
    ManagedExecutorService managedExecutorService;

    /**
     * Handles a SIP INFO message carrying a DTMF digit.
     */
    public void dispatch(SipServletRequest req) throws IOException {
        req.createResponse(SipServletResponse.SC_OK).send();
        String sessionId = req.getSession().getId();
        LOGGER.log(Level.DEBUG, "{0}SIP INFO (DTMF) received", SipCallHeaders.callPrefix(sessionId));
        CallState callState = this.callSessionManager.get(sessionId);

        if (callState == null) {
            LOGGER.log(
                    Level.DEBUG,
                    "{0}SIP INFO received but no active call state — ignoring",
                    SipCallHeaders.callPrefix(sessionId));
            return;
        }

        Object rawContent = req.getContent();
        String body = rawContent instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : String.valueOf(rawContent);
        LOGGER.log(Level.DEBUG, "{0}SIP INFO DTMF body: [{1}]", SipCallHeaders.callPrefix(sessionId), body.strip());
        int digit = parseDtmfDigit(body);

        if (digit < 1) {
            LOGGER.log(
                    Level.DEBUG,
                    "{0}SIP INFO DTMF digit unrecognised or out of range — ignoring",
                    SipCallHeaders.callPrefix(sessionId));
            return;
        }

        applyDtmfSelection(sessionId, digit);
    }

    /**
     * Starts an RFC 2833 telephone-event receiver for the given call if telephone-event was
     * negotiated in the SDP.
     * Returns a completed future immediately if telephone-event was not offered by the remote side.
     */
    Future<?> startReceiver(CallMedia media, String sessionId) {
        int telephoneEventPt = media.telephoneEventPayloadType();

        if (telephoneEventPt < 0) {
            LOGGER.log(
                    Level.DEBUG,
                    "{0}No telephone-event PT negotiated — RFC 2833 DTMF receiver not started",
                    SipCallHeaders.callPrefix(sessionId));

            return CompletableFuture.completedFuture(null);
        }

        LOGGER.log(
                Level.DEBUG,
                "{0}Starting RFC 2833 DTMF receiver (PT={1})",
                SipCallHeaders.callPrefix(sessionId),
                telephoneEventPt);
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(
                media.localSocket(), telephoneEventPt, eventCode -> handleRfc2833Digit(sessionId, eventCode));

        return this.managedExecutorService.submit(receiver);
    }

    private void handleRfc2833Digit(String sessionId, int eventCode) {
        LOGGER.log(
                Level.DEBUG,
                "{0}RFC 2833 DTMF event code [{1}] received",
                SipCallHeaders.callPrefix(sessionId),
                eventCode);
        applyDtmfSelection(sessionId, eventCode);
    }

    private void applyDtmfSelection(String sessionId, int digit) {
        CallState callState = this.callSessionManager.get(sessionId);

        if (callState == null) {
            return;
        }

        LanguageFactory chosen = callState.menu().get(digit);

        if (chosen == null) {
            LOGGER.log(
                    Level.DEBUG,
                    "{0}DTMF digit [{1}] does not match any language slot — ignoring",
                    SipCallHeaders.callPrefix(sessionId),
                    digit);
            return;
        }

        LOGGER.log(
                Level.INFO,
                "{0}DTMF digit [{1}] selected language [{2}]",
                SipCallHeaders.callPrefix(sessionId),
                digit,
                chosen.displayName());
        this.callSessionManager.recordPendingSelection(sessionId, chosen);
        callState.callFuture().cancel(true);
        callState.receiverFuture().cancel(true);
    }

    /**
     * Parses a DTMF digit from a SIP INFO body string.
     * Supports {@code application/dtmf-relay} ({@code Signal=N\r\nDuration=…}) and plain digit
     * bodies.
     * Returns {@code -1} for any unrecognised format.
     */
    static int parseDtmfDigit(String body) {
        try {
            if (body.contains("Signal=")) {
                return body.lines()
                        .filter(line -> line.startsWith("Signal="))
                        .findFirst()
                        .map(line -> line.replace("Signal=", "").strip())
                        .map(Integer::parseInt)
                        .orElse(-1);
            }

            return Integer.parseInt(body.strip());
        } catch (Exception ignoredException) {
            return -1;
        }
    }
}
