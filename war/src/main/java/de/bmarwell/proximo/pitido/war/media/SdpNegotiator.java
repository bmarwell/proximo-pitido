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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;

/**
 * Performs SDP offer/answer negotiation for incoming INVITE requests.
 *
 * <p>Parses the SDP offer from the INVITE body to extract the remote RTP endpoint,
 * allocates a local UDP socket for sending RTP, selects the best mutually supported codec,
 * and builds the SDP answer string to include in the 200 OK response.
 *
 * <p>Codec preference is driven by CDI-injected {@link RtpCodec} beans, filtered by
 * {@link RtpCodec#isAvailable()} and sorted by {@link RtpCodec#preference()} (lower = preferred).
 * The first available codec whose payload type appears in the SDP offer is selected.
 * If no injected codec matches, {@link PcmaRtpCodec#INSTANCE} is used as the unconditional
 * fallback (PCMA is always available).
 *
 * <p>The SDP answer advertises {@code sendonly} direction since this application is a
 * speaking clock that transmits audio but never expects to receive it.
 *
 * <p>The returned {@link CallMedia} record's {@link CallMedia#localSocket()} must be
 * closed by the caller when the call ends.
 */
@ApplicationScoped
public class SdpNegotiator {

    private static final System.Logger LOGGER = System.getLogger(SdpNegotiator.class.getName());

    private static final int PTIME_MS = 20;

    @Inject
    LocalSipHostProvider localSipHostProvider;

    @Inject
    Instance<RtpCodec> availableCodecs;

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
        RtpCodec codec = selectCodec(parseOfferedPayloadTypes(sdpOffer));

        DatagramSocket localSocket = new DatagramSocket(0);
        int localPort = localSocket.getLocalPort();
        String localIp = localSipHostProvider.get();

        LOGGER.log(
                System.Logger.Level.INFO,
                "SDP negotiation: local RTP {0}:{1} → remote RTP {2}:{3} — codec [{4}]",
                localIp,
                localPort,
                remoteIp,
                remotePort,
                codec.sdpName());

        String sdpAnswer = buildSdpAnswer(localIp, localPort, codec);
        InetSocketAddress remoteAddr = new InetSocketAddress(remoteIp, remotePort);

        return new CallMedia(localSocket, remoteAddr, sdpAnswer, codec);
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

    /**
     * Extracts the offered RTP payload type integers from the {@code m=audio} line of the SDP offer.
     * Returns {@code {8}} (PCMA) as default if the line cannot be parsed.
     */
    static Set<Integer> parseOfferedPayloadTypes(String sdp) {
        return sdp.lines()
                .filter(line -> line.startsWith("m=audio "))
                .findFirst()
                .map(line -> {
                    String[] parts = line.split(" ");
                    // m=audio <port> <proto> <pt1> [<pt2> ...]
                    return Arrays.stream(parts, 3, parts.length)
                            .map(Integer::parseInt)
                            .collect(Collectors.toSet());
                })
                .orElse(Set.of(PcmaRtpCodec.INSTANCE.payloadType()));
    }

    /**
     * Selects the best available codec from the CDI-injected {@link RtpCodec} beans.
     * Filters by {@link RtpCodec#isAvailable()}, sorts by {@link RtpCodec#preference()}
     * (lower = preferred), then picks the first whose payload type appears in the offer.
     * Falls back to {@link PcmaRtpCodec#INSTANCE} if nothing matches.
     * Calls {@link RtpCodec#forCall()} on the winner to obtain a per-call encoder instance.
     */
    private RtpCodec selectCodec(Set<Integer> offeredPayloadTypes) {
        RtpCodec descriptor = this.availableCodecs.stream()
                .filter(RtpCodec::isAvailable)
                .filter(codec -> offeredPayloadTypes.contains(codec.payloadType()))
                .min(Comparator.comparingInt(RtpCodec::preference))
                .orElse(PcmaRtpCodec.INSTANCE);

        return descriptor.forCall();
    }

    private static String buildSdpAnswer(String localIp, int localPort, RtpCodec codec) {
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n")
                .append("o=proximo-pitido 0 0 IN IP4 ")
                .append(localIp)
                .append("\r\n")
                .append("s=-\r\n")
                .append("c=IN IP4 ")
                .append(localIp)
                .append("\r\n")
                .append("t=0 0\r\n")
                .append("m=audio ")
                .append(localPort)
                .append(" RTP/AVP ")
                .append(codec.payloadType())
                .append("\r\n")
                .append("a=rtpmap:")
                .append(codec.payloadType())
                .append(" ")
                .append(codec.sdpName())
                .append("/")
                .append(codec.rtpClockRate())
                .append("\r\n");

        if (!codec.fmtpParams().isEmpty()) {
            sdp.append("a=fmtp:")
                    .append(codec.payloadType())
                    .append(" ")
                    .append(codec.fmtpParams())
                    .append("\r\n");
        }

        sdp.append("a=ptime:").append(PTIME_MS).append("\r\n").append("a=sendonly\r\n");

        return sdp.toString();
    }
}
