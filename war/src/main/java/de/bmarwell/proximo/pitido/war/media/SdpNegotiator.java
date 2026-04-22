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

import de.bmarwell.proximo.pitido.codecs.sip.PcmaRtpCodec;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.core.sip.LocalSipHostProvider;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
        String callId = invite.getSession().getId();
        String sdpOffer = readSdpBody(invite);

        LOGGER.log(
                System.Logger.Level.DEBUG, "{0}SDP offer:{1}{2}", callPrefix(callId), System.lineSeparator(), sdpOffer);

        String remoteIp = parseConnectionIp(sdpOffer);
        int remotePort = parseAudioPort(sdpOffer);
        int telephoneEventPt = parseTelephoneEventPayloadType(sdpOffer);
        RtpCodec codec = selectCodec(sdpOffer);

        DatagramSocket localSocket = new DatagramSocket(0);
        int localPort = localSocket.getLocalPort();
        String localIp = localSipHostProvider.get();

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}SDP negotiation: local RTP {1}:{2} → remote RTP {3}:{4} — codec [{5}], telephone-event PT [{6}]",
                callPrefix(callId),
                localIp,
                localPort,
                remoteIp,
                remotePort,
                codec.sdpName(),
                telephoneEventPt);

        String sdpAnswer = buildSdpAnswer(localIp, localPort, codec, telephoneEventPt);
        InetSocketAddress remoteAddr = new InetSocketAddress(remoteIp, remotePort);

        return new CallMedia(localSocket, remoteAddr, sdpAnswer, codec, telephoneEventPt, new AtomicBoolean(false));
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
     * Extracts the RFC 4733 telephone-event RTP payload type from the SDP offer.
     * Scans {@code a=rtpmap:<pt> telephone-event/<clock>} lines.
     * Returns {@code -1} if the remote side did not offer telephone-event.
     */
    static int parseTelephoneEventPayloadType(String sdp) {
        return sdp.lines()
                .filter(line -> line.startsWith("a=rtpmap:") && line.contains("telephone-event"))
                .findFirst()
                .map(line ->
                        Integer.parseInt(line.substring("a=rtpmap:".length()).split("[/ ]")[0]))
                .orElse(-1);
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
     * Parses {@code a=rtpmap} lines from the SDP offer into a map of payload type → codec key.
     *
     * <p>The codec key is {@code "<NAME>/<RATE>"} in upper case, e.g. {@code "AMR-WB/16000"}.
     * Insertion order follows the document order of the {@code a=rtpmap} lines, which typically
     * matches the preference order from the {@code m=audio} line.
     *
     * @param sdp the full SDP offer string
     * @return a map from payload type to codec key; empty map if no {@code a=rtpmap} lines are present
     */
    static Map<Integer, String> parseRtpmap(String sdp) {
        Map<Integer, String> result = new LinkedHashMap<>();

        sdp.lines().filter(line -> line.startsWith("a=rtpmap:")).forEach(line -> {
            try {
                // a=rtpmap:<pt> <name>/<rate>[/<channels>]
                String rest = line.substring("a=rtpmap:".length()).strip();
                int spacePos = rest.indexOf(' ');

                if (spacePos < 0) {
                    return;
                }

                int pt = Integer.parseInt(rest.substring(0, spacePos));
                String encoding = rest.substring(spacePos + 1).strip();
                String[] encodingParts = encoding.split("/");

                if (encodingParts.length < 2) {
                    return;
                }

                String codecKey = (encodingParts[0] + "/" + encodingParts[1]).toUpperCase(Locale.ROOT);
                result.put(pt, codecKey);
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                // Skip malformed a=rtpmap lines; remaining codecs can still be matched.
            }
        });

        return result;
    }

    /**
     * Parses {@code a=fmtp} lines from the SDP offer into a map of payload type → fmtp parameters.
     *
     * @param sdp the full SDP offer string
     * @return a map from payload type to fmtp parameter string (the part after the {@code "<pt> "});
     *         empty map if no {@code a=fmtp} lines are present
     */
    static Map<Integer, String> parseFmtp(String sdp) {
        Map<Integer, String> result = new LinkedHashMap<>();

        sdp.lines().filter(line -> line.startsWith("a=fmtp:")).forEach(line -> {
            // a=fmtp:<pt> <params>
            String rest = line.substring("a=fmtp:".length()).strip();
            int spacePos = rest.indexOf(' ');

            if (spacePos < 0) {
                return;
            }

            final int pt;

            try {
                pt = Integer.parseInt(rest.substring(0, spacePos));
            } catch (NumberFormatException ignored) {
                // Skip malformed a=fmtp lines; this payload type simply has no fmtp params.
                return;
            }

            String params = rest.substring(spacePos + 1).strip();
            result.put(pt, params);
        });

        return result;
    }

    /**
     * Selects the best available codec from the CDI-injected {@link RtpCodec} beans.
     *
     * <p>Filters by {@link RtpCodec#isAvailable()}, sorts by {@link RtpCodec#preference()}
     * (lower = preferred), then matches each codec by name and clock rate against the
     * {@code a=rtpmap} lines in the SDP offer.
     * For codecs with dynamic payload types (96–127), the matching is done by codec key
     * ({@code NAME/RATE}) rather than by static payload type number, because the caller
     * assigns a fresh payload type per call.
     * The payload type found in the offer is preserved in a {@link NegotiatedRtpCodec} wrapper
     * so that outgoing RTP packet headers and the SDP answer use the correct value.
     *
     * <p>Falls back to the static payload type match for codecs whose payload type is in the
     * static range (0–95) and which are offered without an explicit {@code a=rtpmap} line (e.g.
     * PCMA at PT 8, which is sometimes omitted by legacy endpoints).
     *
     * <p>Falls back to {@link PcmaRtpCodec#INSTANCE} if no injected codec matches.
     *
     * @param sdpOffer the full SDP offer string from the INVITE body
     * @return a codec descriptor (CDI bean) whose {@link RtpCodec#payloadType()} returns the PT
     *         actually negotiated with the caller; callers must call {@link RtpCodec#forCall()}
     *         on the announcement thread before encoding
     */
    private RtpCodec selectCodec(String sdpOffer) {
        return selectCodec(this.availableCodecs.stream(), sdpOffer);
    }

    /**
     * Selects the best codec from the given stream, matching against the SDP offer.
     *
     * <p>This static overload exists to allow unit testing without CDI injection.
     *
     * @param codecs   available codec descriptors, each an {@code @ApplicationScoped} CDI bean
     * @param sdpOffer the full SDP offer string from the INVITE body
     * @return a {@link NegotiatedRtpCodec} wrapping the selected codec descriptor with the
     *         negotiated payload type; call {@link RtpCodec#forCall()} on the announcement thread
     */
    static RtpCodec selectCodec(Stream<RtpCodec> codecs, String sdpOffer) {
        Set<Integer> offeredPts = parseOfferedPayloadTypes(sdpOffer);
        Map<Integer, String> rtpmap = parseRtpmap(sdpOffer);
        Map<Integer, String> fmtp = parseFmtp(sdpOffer);

        Optional<NegotiatedRtpCodec> selected = codecs.filter(RtpCodec::isAvailable)
                .sorted(Comparator.comparingInt(RtpCodec::preference))
                .flatMap(codec -> negotiatedPt(codec, offeredPts, rtpmap, fmtp)
                        .map(pt -> new NegotiatedRtpCodec(codec, pt, fmtp.getOrDefault(pt, "")))
                        .stream())
                .findFirst();

        if (selected.isEmpty()) {
            LOGGER.log(System.Logger.Level.DEBUG, "No matching codec found in SDP offer; falling back to PCMA (PT 8)");
        }

        NegotiatedRtpCodec result = selected.orElseGet(
                () -> new NegotiatedRtpCodec(PcmaRtpCodec.INSTANCE, PcmaRtpCodec.INSTANCE.payloadType(), ""));

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Codec selected: {0} (negotiated PT {1}) from offered payload types {2}",
                result.sdpName(),
                result.payloadType(),
                offeredPts);

        return result;
    }

    /**
     * Finds the negotiated payload type for a codec against the offered payload types and rtpmap.
     *
     * <p>Iterates the rtpmap entries (in offer order) looking for entries whose codec key matches
     * {@code "<sdpName>/<rtpClockRate>"} (case-insensitive) and whose fmtp parameters are
     * accepted by {@link RtpCodec#matchesFmtp(String)}.
     * Falls back to the codec's own static payload type if no rtpmap entry is present (e.g.
     * PCMA at PT 8 offered without an explicit {@code a=rtpmap:8 PCMA/8000} line).
     *
     * @param codec      the codec descriptor to match
     * @param offeredPts the set of payload types from the {@code m=audio} line
     * @param rtpmap     map of payload type → codec key from {@code a=rtpmap} lines
     * @param fmtp       map of payload type → fmtp params from {@code a=fmtp} lines
     * @return the payload type to use, or empty if the codec is not compatible with this offer
     */
    private static Optional<Integer> negotiatedPt(
            RtpCodec codec, Set<Integer> offeredPts, Map<Integer, String> rtpmap, Map<Integer, String> fmtp) {
        String codecKey = (codec.sdpName() + "/" + codec.rtpClockRate()).toUpperCase(Locale.ROOT);

        // Find the first offered PT whose rtpmap matches this codec and whose fmtp is compatible.
        Optional<Integer> fromRtpmap = rtpmap.entrySet().stream()
                .filter(entry -> offeredPts.contains(entry.getKey()))
                .filter(entry -> entry.getValue().equals(codecKey))
                .filter(entry -> codec.matchesFmtp(fmtp.getOrDefault(entry.getKey(), "")))
                .map(Map.Entry::getKey)
                .findFirst();

        if (fromRtpmap.isPresent()) {
            return fromRtpmap;
        }

        int payloadType = codec.payloadType();

        // Fallback: static payload type (0–95) offered without an explicit rtpmap line.
        // Dynamic PTs (96–127) must always be declared via a=rtpmap; skip them here.
        // Also skip if the rtpmap already maps this PT to a different codec.
        if (payloadType > 95) {
            return Optional.empty();
        }

        if (rtpmap.containsKey(payloadType)) {
            return Optional.empty();
        }

        if (offeredPts.contains(payloadType) && codec.matchesFmtp("")) {
            return Optional.of(payloadType);
        }

        return Optional.empty();
    }

    private static String buildSdpAnswer(String localIp, int localPort, RtpCodec codec, int telephoneEventPt) {
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
                .append(codec.payloadType());

        if (telephoneEventPt >= 0) {
            sdp.append(" ").append(telephoneEventPt);
        }

        sdp.append("\r\n")
                .append("a=rtpmap:")
                .append(codec.payloadType())
                .append(" ")
                .append(codec.sdpName())
                .append("/")
                .append(codec.rtpClockRate());

        if (codec.sdpChannelCount() > 1) {
            sdp.append("/").append(codec.sdpChannelCount());
        }

        sdp.append("\r\n");

        if (!codec.fmtpParams().isEmpty()) {
            sdp.append("a=fmtp:")
                    .append(codec.payloadType())
                    .append(" ")
                    .append(codec.fmtpParams())
                    .append("\r\n");
        }

        if (telephoneEventPt >= 0) {
            sdp.append("a=rtpmap:").append(telephoneEventPt).append(" telephone-event/8000\r\n");
            sdp.append("a=fmtp:").append(telephoneEventPt).append(" 0-15\r\n");
        }

        sdp.append("a=ptime:").append(PTIME_MS).append("\r\n").append("a=sendonly\r\n");

        return sdp.toString();
    }

    private static String callPrefix(String callId) {
        return "[callId=" + callId + "] ";
    }
}
