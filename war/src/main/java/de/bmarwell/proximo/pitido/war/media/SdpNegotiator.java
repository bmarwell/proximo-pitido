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

import de.bmarwell.proximo.pitido.codecs.sip.PcmaRtpCodecFactory;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;

/// Performs SDP offer/answer negotiation for incoming INVITE requests.
///
/// Parses the SDP offer from the INVITE body to extract the remote RTP endpoint,
/// allocates a local UDP socket for sending RTP, selects the best mutually supported codec,
/// and builds the SDP answer string to include in the 200 OK response.
///
/// Codec preference is driven by CDI-injected [RtpCodecFactory] beans, filtered by
/// [RtpCodecFactory#isAvailable()] and sorted by [RtpCodecFactory#preference()] (lower = preferred).
/// The first available codec whose payload type appears in the SDP offer is selected.
/// If no injected codec matches, the injected [PcmaRtpCodecFactory] is used as the unconditional
/// fallback (PCMA is always available).
///
/// The SDP answer advertises `sendrecv` direction to enable DTMF (telephone-event)
/// reception on the shared RTP socket.
///
/// The returned [CallMedia] record's [CallMedia#localSocket()] must be
/// closed by the caller when the call ends.
@ApplicationScoped
public class SdpNegotiator {

    private static final System.Logger LOGGER = System.getLogger(SdpNegotiator.class.getName());

    private static final int PTIME_MS = 20;

    @Inject
    LocalSipHostProvider localSipHostProvider;

    @Inject
    Instance<RtpCodecFactory> availableCodecFactories;

    @Inject
    Instance<PcmaRtpCodecFactory> pcmaFallback;

    /**
     * Negotiates media for the given INVITE.
     *
     * <p>Parses the SDP offer, allocates a UDP socket on an OS-assigned port, and builds
     * the SDP answer using the configured public host address.
     * Creates the codec immediately to enable fmtpAnswer() for SDP negotiation,
     * then wraps it in NegotiatedRtpCodec with the negotiated payload type.
     *
     * @param invite the incoming INVITE request; must contain a valid SDP offer body
     * @return a {@link CallMedia} containing the allocated socket, remote RTP address,
     *         SDP answer text, and the negotiated codec
     * @throws IOException if the SDP body cannot be read, the offer is malformed, or
     *                     the local UDP socket cannot be created
     */
    public CallMedia negotiate(SipServletRequest invite) throws IOException {
        String callId = invite.getSession().getId();
        String sdpOffer = readSdpBody(invite);

        LOGGER.log(
                System.Logger.Level.TRACE, "{0}SDP offer:{1}{2}", callPrefix(callId), System.lineSeparator(), sdpOffer);

        String remoteIp = parseConnectionIp(sdpOffer);
        int remotePort = parseAudioPort(sdpOffer);
        int telephoneEventPt = parseTelephoneEventPayloadType(sdpOffer);
        NegotiatedRtpCodecFactory negotiatedCodecFactory = selectCodec(sdpOffer);

        DatagramSocket localSocket = new DatagramSocket(0);
        int localPort = localSocket.getLocalPort();
        String localIp = localSipHostProvider.get();

        LOGGER.log(
                System.Logger.Level.TRACE,
                "{0}SDP negotiation: local RTP {1}:{2} → remote RTP {3}:{4} — codec [{5}], telephone-event PT [{6}]",
                callPrefix(callId),
                localIp,
                localPort,
                remoteIp,
                remotePort,
                negotiatedCodecFactory.metadata().sdpName(),
                telephoneEventPt);

        String sdpAnswer = buildSdpAnswer(localIp, localPort, negotiatedCodecFactory, telephoneEventPt);
        InetSocketAddress remoteAddr = new InetSocketAddress(remoteIp, remotePort);

        return new CallMedia(
                localSocket, remoteAddr, sdpAnswer, negotiatedCodecFactory, telephoneEventPt, new AtomicBoolean(false));
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
    static Set<Integer> parseOfferedPayloadTypes(String sdp, Supplier<RtpCodecFactory> fallbackSupplier) {
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
                .orElse(Set.of(fallbackSupplier.get().metadata().payloadType()));
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
     * Selects the best available codec from the CDI-injected {@link RtpCodecFactory} beans.
     *
     * <p>Filters by {@link RtpCodecFactory#isAvailable()}, sorts by {@link RtpCodecFactory#preference()}
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
     * @param sdpOffer the full SDP offer string from the INVITE body
     * @return a codec descriptor (CDI bean) whose {@link RtpCodecFactory#metadata()#payloadType} ()} returns the PT
     *         actually negotiated with the caller; callers must call {@link RtpCodecFactory#forCall()}
     *         on the announcement thread before encoding
     */
    private NegotiatedRtpCodecFactory selectCodec(String sdpOffer) {
        return selectCodec(this.availableCodecFactories.stream(), sdpOffer, () -> this.pcmaFallback.get());
    }

    /**
     * Selects the best codec from the given stream, matching against the SDP offer.
     *
     * <p>This static overload exists to allow unit testing without CDI injection.
     *
     * @param codecs      available codec descriptors, each an {@code @ApplicationScoped} CDI bean
     * @param sdpOffer    the full SDP offer string from the INVITE body
     * @param fallbackSupplier Supplier for PCMA codec factory as fallback
     * @return a NegotiatedRtpCodecFactory wrapping the selected factory with negotiated PT and fmtp
     */
    static NegotiatedRtpCodecFactory selectCodec(
            Stream<RtpCodecFactory> codecs, String sdpOffer, Supplier<RtpCodecFactory> fallbackSupplier) {
        Set<Integer> offeredPts = parseOfferedPayloadTypes(sdpOffer, fallbackSupplier);
        Map<Integer, String> rtpmap = parseRtpmap(sdpOffer);
        Map<Integer, String> fmtp = parseFmtp(sdpOffer);

        record SelectionResult(RtpCodecFactory factory, int negotiatedPt, String offeredFmtp) {}

        Optional<SelectionResult> selected = codecs.filter(RtpCodecFactory::isAvailable)
                .sorted(Comparator.comparingInt(RtpCodecFactory::preference))
                .flatMap(codec -> negotiatedPt(codec, offeredPts, rtpmap, fmtp)
                        .map(pt -> new SelectionResult(codec, pt, fmtp.getOrDefault(pt, "")))
                        .stream())
                .findFirst();

        if (selected.isEmpty()) {
            LOGGER.log(System.Logger.Level.DEBUG, "No matching codec found in SDP offer; falling back to PCMA (PT 8)");
        }

        SelectionResult result = selected.orElseGet(() -> {
            RtpCodecFactory fallback = fallbackSupplier.get();
            return new SelectionResult(fallback, fallback.metadata().payloadType(), "");
        });

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Codec selected: {0} (negotiated PT {1}) from offered payload types {2}",
                result.factory.metadata().sdpName(),
                result.negotiatedPt,
                offeredPts);

        // Wrap factory with negotiated PT and offered fmtp to enable fmtpAnswer during SDP building
        // and codec creation on executor thread.
        return new NegotiatedRtpCodecFactory(result.factory, result.negotiatedPt, result.offeredFmtp);
    }

    /**
     * Finds the negotiated payload type for a codecFactory against the offered payload types and rtpmap.
     *
     * <p>Iterates the rtpmap entries (in offer order) looking for entries whose codecFactory key matches
     * {@code "<sdpName>/<rtpClockRate>"} (case-insensitive) and whose fmtp parameters are
     * accepted by {@link RtpCodecFactory#matchesFmtp(String)}.
     * Falls back to the codecFactory's own static payload type if no rtpmap entry is present (e.g.
     * PCMA at PT 8 offered without an explicit {@code a=rtpmap:8 PCMA/8000} line).
     *
     * @param codecFactory      the codecFactory descriptor to match
     * @param offeredPts the set of payload types from the {@code m=audio} line
     * @param rtpmap     map of payload type → codecFactory key from {@code a=rtpmap} lines
     * @param fmtp       map of payload type → fmtp params from {@code a=fmtp} lines
     * @return the payload type to use, or empty if the codecFactory is not compatible with this offer
     */
    private static Optional<Integer> negotiatedPt(
            RtpCodecFactory codecFactory,
            Set<Integer> offeredPts,
            Map<Integer, String> rtpmap,
            Map<Integer, String> fmtp) {
        String codecKey = (codecFactory.metadata().sdpName() + "/"
                        + codecFactory.metadata().rtpClockRate())
                .toUpperCase(Locale.ROOT);

        // Find the first offered PT whose rtpmap matches this codecFactory and whose fmtp is compatible.
        Optional<Integer> fromRtpmap = rtpmap.entrySet().stream()
                .filter(entry -> offeredPts.contains(entry.getKey()))
                .filter(entry -> entry.getValue().equals(codecKey))
                .filter(entry -> {
                    String offeredFmtp = fmtp.getOrDefault(entry.getKey(), "");
                    boolean matches = codecFactory.matchesFmtp(offeredFmtp);

                    LOGGER.log(
                            System.Logger.Level.TRACE,
                            "negotiatedPt: codecFactory={0} PT={1} offeredFmtp=''{2}'' matches={3}",
                            codecFactory.metadata().sdpName(),
                            entry.getKey(),
                            offeredFmtp,
                            matches);

                    return matches;
                })
                .map(Map.Entry::getKey)
                .findFirst();

        if (fromRtpmap.isPresent()) {
            return fromRtpmap;
        }

        int payloadType = codecFactory.metadata().payloadType();

        // Fallback: static payload type (0–95) offered without an explicit rtpmap line.
        // Dynamic PTs (96–127) must always be declared via a=rtpmap; skip them here.
        // Also skip if the rtpmap already maps this PT to a different codecFactory.
        if (payloadType > 95) {
            return Optional.empty();
        }

        if (rtpmap.containsKey(payloadType)) {
            return Optional.empty();
        }

        if (offeredPts.contains(payloadType) && codecFactory.matchesFmtp("")) {
            return Optional.of(payloadType);
        }

        return Optional.empty();
    }

    private static String buildSdpAnswer(
            String localIp, int localPort, NegotiatedRtpCodecFactory negotiatedCodecFactory, int telephoneEventPt) {
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
                .append(negotiatedCodecFactory.metadata().payloadType());

        if (telephoneEventPt >= 0) {
            sdp.append(" ").append(telephoneEventPt);
        }

        sdp.append("\r\n")
                .append("a=rtpmap:")
                .append(negotiatedCodecFactory.metadata().payloadType())
                .append(" ")
                .append(negotiatedCodecFactory.metadata().sdpName())
                .append("/")
                .append(negotiatedCodecFactory.metadata().rtpClockRate());

        if (negotiatedCodecFactory.metadata().sdpChannelCount() > 1) {
            sdp.append("/").append(negotiatedCodecFactory.metadata().sdpChannelCount());
        }

        sdp.append("\r\n");

        String fmtpParams = negotiatedCodecFactory.fmtpAnswer();
        LOGGER.log(
                System.Logger.Level.TRACE,
                "SDP answer fmtp: codec={0} PT={1} fmtpParams=''{2}''",
                negotiatedCodecFactory.metadata().sdpName(),
                negotiatedCodecFactory.metadata().payloadType(),
                fmtpParams);

        if (!fmtpParams.isEmpty()) {
            sdp.append("a=fmtp:")
                    .append(negotiatedCodecFactory.metadata().payloadType())
                    .append(" ")
                    .append(fmtpParams)
                    .append("\r\n");
        }

        if (telephoneEventPt >= 0) {
            sdp.append("a=rtpmap:").append(telephoneEventPt).append(" telephone-event/8000\r\n");
            sdp.append("a=fmtp:").append(telephoneEventPt).append(" 0-15\r\n");
        }

        sdp.append("a=ptime:").append(PTIME_MS).append("\r\n").append("a=sendrecv\r\n");

        String answer = sdp.toString();
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}SDP answer:{1}{2}",
                callPrefix(negotiatedCodecFactory.metadata().sdpName()),
                System.lineSeparator(),
                answer);

        return answer;
    }

    private static String callPrefix(String callId) {
        return "[callId=" + callId + "] ";
    }
}
