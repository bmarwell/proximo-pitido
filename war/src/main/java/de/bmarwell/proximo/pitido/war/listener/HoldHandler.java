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

import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/**
 * Handles in-dialog re-INVITEs used for call hold and unhold.
 *
 * <p>When a caller puts a call on hold, their phone sends a re-INVITE containing
 * {@code a=sendonly} or {@code a=inactive} in the SDP offer.
 * The current implementation would treat this as a new INVITE, causing undefined behaviour.
 * This bean detects the re-INVITE, pauses or resumes RTP, and responds with {@code 200 OK}.
 *
 * <h2>Hold detection</h2>
 *
 * <p>The SDP direction attribute is inspected:
 * <ul>
 *   <li>{@code a=sendonly} — caller is sending hold; we stop sending RTP and reply
 *       {@code a=recvonly}.</li>
 *   <li>{@code a=inactive} — caller is fully inactive; we stop sending RTP and reply
 *       {@code a=inactive}.</li>
 *   <li>{@code a=recvonly} or {@code a=sendrecv} (or absent) — caller is resuming;
 *       we resume RTP and reply {@code a=sendrecv}.</li>
 * </ul>
 *
 * <h2>RTP behaviour during hold</h2>
 *
 * <p>The audio sender thread continues running but skips sending RTP packets and pauses PCM
 * file consumption (see {@link de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer}).
 * The RTP timestamp is still advanced per 20 ms tick so the timeline is correct on resume.
 * When the caller un-holds, the announcement resumes from where it paused.
 *
 * <h2>Session expiry</h2>
 *
 * <p>For a speaking-clock call (5–15 seconds), the announcement will typically have finished
 * before the caller un-holds.
 * The RTP sender thread will have exited naturally; the SIP session is left open until the
 * caller sends BYE.
 */
@ApplicationScoped
public class HoldHandler {

    private static final System.Logger LOGGER = System.getLogger(HoldHandler.class.getName());

    @Inject
    CallSessionManager callSessionManager;

    /**
     * Processes a re-INVITE, pausing or resuming RTP based on the SDP direction.
     * Responds with {@code 200 OK} and an updated SDP direction.
     *
     * @param req the re-INVITE request; must belong to an already-established session
     * @throws IOException if sending the SIP response fails
     */
    public void handle(SipServletRequest req) throws IOException {
        String sessionId = req.getSession().getId();
        CallState callState = this.callSessionManager.get(sessionId);

        if (callState == null) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Re-INVITE received but no active call state found — responding 481",
                    SipCallHeaders.callPrefix(sessionId));
            req.createResponse(SipServletResponse.SC_CALL_LEG_DONE).send();

            return;
        }

        String sdpOffer = readSdpBody(req);
        boolean callerIsHolding = isHoldOffer(sdpOffer);
        boolean inactive = sdpOffer.lines().anyMatch(line -> line.strip().equals("a=inactive"));
        CallMedia media = callState.media();

        if (callerIsHolding) {
            media.hold();
            String direction = inactive ? "inactive" : "recvonly";
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "{0}Call put on hold (SDP direction: {1})",
                    SipCallHeaders.callPrefix(sessionId),
                    direction);
            sendOkWithDirection(req, media, direction);
        } else {
            media.unhold();
            LOGGER.log(System.Logger.Level.INFO, "{0}Call resumed from hold", SipCallHeaders.callPrefix(sessionId));
            sendOkWithDirection(req, media, "sendrecv");
        }
    }

    /**
     * Returns {@code true} when the SDP offer indicates the caller is placing the call on hold.
     * Checks for {@code a=sendonly} or {@code a=inactive} in the media section.
     */
    static boolean isHoldOffer(String sdp) {
        return sdp.lines().anyMatch(line -> {
            String stripped = line.strip();
            return stripped.equals("a=sendonly") || stripped.equals("a=inactive");
        });
    }

    private static void sendOkWithDirection(SipServletRequest req, CallMedia media, String direction)
            throws IOException {
        String sdpAnswer = replaceDirection(media.sdpAnswer(), direction);
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(sdpAnswer.getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();
    }

    /**
     * Replaces the {@code a=sendrecv}, {@code a=sendonly}, {@code a=recvonly}, or
     * {@code a=inactive} attribute line in the SDP answer with the given direction.
     * If none of those lines is present, appends the direction before the end of the string.
     */
    static String replaceDirection(String sdpAnswer, String direction) {
        String newLine = "a=" + direction;

        for (String candidate : new String[] {"a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive"}) {
            if (sdpAnswer.contains(candidate)) {
                return sdpAnswer.replace(candidate, newLine);
            }
        }

        return sdpAnswer + newLine + "\r\n";
    }

    private static String readSdpBody(SipServletRequest req) throws IOException {
        Object content = req.getContent();

        if (content instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        return String.valueOf(content);
    }
}
