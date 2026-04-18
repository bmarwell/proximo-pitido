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
package de.bmarwell.proximo.pitido.war.media;

import de.bmarwell.proximo.pitido.core.sip.LocalSipHostProvider;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;

/**
 * Performs SDP offer/answer negotiation for incoming INVITE requests.
 *
 * <p>Parses the SDP offer from the INVITE body to extract the remote RTP endpoint,
 * allocates a local UDP socket for sending RTP, and builds the SDP answer string
 * to include in the 200 OK response.
 *
 * <p>Only G.711 A-law (PCMA, payload type 8) is supported as codec.
 * The SDP answer advertises {@code sendonly} direction since this application is a
 * speaking clock that transmits audio but never expects to receive it.
 *
 * <p>The returned {@link CallMedia} record's {@link CallMedia#localSocket()} must be
 * closed by the caller when the call ends.
 */
@ApplicationScoped
public class SdpNegotiator {

    private static final System.Logger LOGGER = System.getLogger(SdpNegotiator.class.getName());

    private static final int PCMA_PAYLOAD_TYPE = 8;
    private static final int PTIME_MS = 20;

    @Inject
    LocalSipHostProvider localSipHostProvider;

    /**
     * Negotiates media for the given INVITE.
     *
     * <p>Parses the SDP offer, allocates a UDP socket on an OS-assigned port, and builds
     * the SDP answer using the configured public host address.
     *
     * @param invite the incoming INVITE request; must contain a valid SDP offer body
     * @return a {@link CallMedia} containing the allocated socket, remote RTP address,
     *         and the SDP answer text
     * @throws IOException if the SDP body cannot be read, the offer is malformed, or
     *                     the local UDP socket cannot be created
     */
    public CallMedia negotiate(SipServletRequest invite) throws IOException {
        String sdpOffer = readSdpBody(invite);

        LOGGER.log(System.Logger.Level.DEBUG, "SDP offer:{0}{1}", System.lineSeparator(), sdpOffer);

        String remoteIp = parseConnectionIp(sdpOffer);
        int remotePort = parseAudioPort(sdpOffer);

        DatagramSocket localSocket = new DatagramSocket(0);
        int localPort = localSocket.getLocalPort();
        String localIp = localSipHostProvider.get();

        LOGGER.log(
                System.Logger.Level.INFO,
                "SDP negotiation: local RTP {0}:{1} → remote RTP {2}:{3}",
                localIp,
                localPort,
                remoteIp,
                remotePort);

        String sdpAnswer = buildSdpAnswer(localIp, localPort);
        InetSocketAddress remoteAddr = new InetSocketAddress(remoteIp, remotePort);

        return new CallMedia(localSocket, remoteAddr, sdpAnswer);
    }

    private static String readSdpBody(SipServletRequest req) throws IOException {
        Object content = req.getContent();

        if (content instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        return String.valueOf(content);
    }

    /**
     * Extracts the connection IP from the SDP offer.
     * Prefers a media-level {@code c=} line inside the audio section over the
     * session-level {@code c=} line.
     */
    static String parseConnectionIp(String sdp) {
        boolean inAudioSection = false;

        for (String line : sdp.lines().toList()) {
            if (line.startsWith("m=audio")) {
                inAudioSection = true;
            }

            if (inAudioSection && line.startsWith("c=IN IP4 ")) {
                return line.substring("c=IN IP4 ".length()).strip();
            }
        }

        return sdp.lines()
                .filter(line -> line.startsWith("c=IN IP4 "))
                .findFirst()
                .map(line -> line.substring("c=IN IP4 ".length()).strip())
                .orElseThrow(() -> new IllegalArgumentException("No c= line found in SDP offer"));
    }

    /**
     * Extracts the remote RTP port from the {@code m=audio} line of the SDP offer.
     */
    static int parseAudioPort(String sdp) {
        return sdp.lines()
                .filter(line -> line.startsWith("m=audio "))
                .findFirst()
                .map(line -> Integer.parseInt(line.split(" ")[1]))
                .orElseThrow(() -> new IllegalArgumentException("No m=audio line found in SDP offer"));
    }

    private static String buildSdpAnswer(String localIp, int localPort) {
        return "v=0\r\n"
                + "o=proximo-pitido 0 0 IN IP4 " + localIp + "\r\n"
                + "s=-\r\n"
                + "c=IN IP4 " + localIp + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + localPort + " RTP/AVP " + PCMA_PAYLOAD_TYPE + "\r\n"
                + "a=rtpmap:" + PCMA_PAYLOAD_TYPE + " PCMA/8000\r\n"
                + "a=ptime:" + PTIME_MS + "\r\n"
                + "a=sendonly\r\n";
    }
}
