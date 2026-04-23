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
package de.bmarwell.proximo.pitido.codecs.sip;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import javax.enterprise.context.ApplicationScoped;

/**
 * AMR-WB bandwidth-efficient RTP codec (RFC 4867 §4.3).
 *
 * <p>Encodes AMR-WB frames in bandwidth-efficient format, which omits the 1-byte CMR header
 * and uses the ToC byte as the first octet of the RTP payload.
 * This variant is used by some SIP endpoints (e.g. 1&1 Mobilfunk) that do not support
 * or do not offer octet-aligned mode.
 *
 * <p>Unlike the octet-aligned variant, this implementation does NOT require an explicit
 * {@code octet-align=1} fmtp parameter; if the caller offers AMR-WB without any fmtp
 * constraints (or with bandwidth-efficient only), this codec is selected as a fallback.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 4867 §8.3, bandwidth-efficient AMR-WB is indicated by:
 * <pre>
 * a=rtpmap:104 AMR-WB/16000/1
 * </pre>
 *
 * The {@code /1} suffix indicates bandwidth-efficient mode (no octet-align parameter in fmtp).
 *
 * <h2>RTP payload format (RFC 4867 §4.3 bandwidth-efficient)</h2>
 *
 * <p>Each RTP packet contains exactly one AMR-WB frame in bandwidth-efficient mode:
 * <ol>
 *   <li>1-byte Table of Contents (ToC) — frame type index (bits 6–3), quality flag
 *       {@code Q=1} (bit 2), one padding bit, and continuation flag {@code F=0}.</li>
 *   <li>Speech data bytes produced by {@code E_IF_encode}.</li>
 * </ol>
 *
 * <p>Note: no CMR header is present.
 * This saves 1 byte per packet compared to octet-aligned format, but requires
 * the decoder to have out-of-band knowledge of the active codec mode.
 *
 * @see AmrWbRtpCodec
 */
@ApplicationScoped
public final class AmrWbBandwidthEfficientRtpCodec extends AmrWbRtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbBandwidthEfficientRtpCodec.class.getName());

    /**
     * Payload type for bandwidth-efficient AMR-WB (dynamic; typically 104 from 1&1 Mobilfunk).
     * This is a placeholder used only during factory bean construction.
     * The {@link #payloadType()} method always returns this constant because the SDP negotiation
     * framework handles dynamic payload type mapping externally.
     * The actual PT used in SDP answers and RTP demux is determined by {@link SdpNegotiator},
     * which wraps this codec in a {@link NegotiatedRtpCodec} with the correct PT.
     */
    private static final int PAYLOAD_TYPE_PLACEHOLDER = 104;

    /**
     * Preference order: octet-aligned is preferred (lower number = higher preference).
     * Bandwidth-efficient is a fallback when the caller doesn't offer octet-aligned.
     */
    private static final int PREFERENCE = 40;

    /**
     * Preference order: bandwidth-efficient is tried first (lower number = higher preference).
     * Octet-aligned is tried second, only when explicitly offered ("octet-align=1" in SDP).
     *
     * <p>RFC 4867 specifies bandwidth-efficient as the DEFAULT AMR-WB packetisation format.
     * Using bandwidth-efficient first (preference 40) ensures compatibility with endpoints that
     * advertise only bandwidth-efficient support and do not send octet-align=1 fmtp parameter.
     * Octet-aligned codec has preference 41 (tried second) and requires explicit "octet-align=1".
     */

    /**
     * RTP clock rate for AMR-WB: 16 kHz (wideband).
     */
    private static final int RTP_CLOCK_RATE = 16_000;

    @Override
    public String sdpName() {
        return "AMR-WB";
    }

    @Override
    public int payloadType() {
        return PAYLOAD_TYPE_PLACEHOLDER;
    }

    @Override
    public int rtpClockRate() {
        return RTP_CLOCK_RATE;
    }

    @Override
    public int preference() {
        return PREFERENCE;
    }

    /**
     * Accepts AMR-WB when the caller offers it WITHOUT {@code octet-align=1}.
     *
     * <p>Since bandwidth-efficient mode is the default when no fmtp parameters are present,
     * this codec accepts empty fmtp strings (no parameters at all).
     * It accepts any fmtp string that does not explicitly specify {@code octet-align=1};
     * other parameters (e.g. {@code mode-set}, {@code octet-align=0}) are ignored.
     * This allows some flexibility in fmtp declarations while still rejecting octet-aligned offers.
     */
    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        // Bandwidth-efficient is the default: accept empty fmtp.
        if (offeredFmtp.isEmpty()) {
            return true;
        }

        // Reject only if octet-align=1 is explicitly present (parsed as key=value pairs).
        // This ensures we match the parent class's parameter parsing style and avoid
        // false substring matches (e.g., "octet-align=0" or "octet-align=10" should not be rejected).
        return !Arrays.stream(offeredFmtp.split(";"))
                .map(String::strip)
                .anyMatch(AmrWbBandwidthEfficientRtpCodec::isOctetAlignOneParam);
    }

    private static boolean isOctetAlignOneParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip()) && "1".equals(kv[1].strip());
    }

    /**
     * Encodes one PCM frame to bandwidth-efficient AMR-WB RTP payload.
     *
     * <p>Reuses the parent class's native encoder invocation but builds a different payload
     * format: [ToC][speech bytes] without the CMR header.
     *
     * @param pcmFrame 320 mono PCM samples at 16 kHz
     * @return RTP payload in bandwidth-efficient format
     * @throws IOException if the native encoder fails
     */
    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        if (this.stateSegment == null) {
            throw new IllegalStateException(
                    "encode() must not be called on the CDI factory bean; obtain a per-call instance via forCall() first");
        }

        try (Arena frameArena = Arena.ofConfined()) {
            MemorySegment inputSeg = frameArena.allocateFrom(ValueLayout.JAVA_SHORT, pcmFrame);
            MemorySegment outputSeg = frameArena.allocate(ValueLayout.JAVA_BYTE, MAX_ENCODED_BYTES);

            int speechBytes = invokeEncode(inputSeg, outputSeg);

            if (speechBytes < 0) {
                throw new IOException("E_IF_encode failed with error code " + speechBytes);
            }

            // libvo-amrwbenc outputs bandwidth-efficient format (ToC + speech) natively.
            // For bandwidth-efficient RTP payloads, we can use the encoder output as-is,
            // since the first byte from the encoder is already the correct ToC for this mode.
            byte expectedToC = (byte) ((this.encodingMode << 3) | 0x04);
            byte firstEncoderByte = outputSeg.get(ValueLayout.JAVA_BYTE, 0);

            // Sanity check: if the encoder's first byte matches our expected ToC,
            // it confirms the encoder is outputting bandwidth-efficient format.
            if (firstEncoderByte != expectedToC) {
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "Encoder output first byte (0x{0}) does not match expected ToC (0x{1}) for mode {2}; audio may be corrupt",
                        String.format(java.util.Locale.ROOT, "%02x", firstEncoderByte & 0xFF),
                        String.format(java.util.Locale.ROOT, "%02x", expectedToC & 0xFF),
                        this.encodingMode);
            }

            // Extract the bandwidth-efficient payload: convert to byte array and return.
            // The encoder outputs exactly speechBytes, so we get the correctly-sized array directly.
            byte[] payload = outputSeg.asSlice(0L, speechBytes).toArray(ValueLayout.JAVA_BYTE);

            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AMR-WB bandwidth-efficient encode: encodingMode={0} encoderOutputBytes={1} payloadBytes={2}",
                    this.encodingMode,
                    speechBytes,
                    payload.length);

            return payload;
        }
    }

    @Override
    public String fmtpParams() {
        // Bandwidth-efficient mode requires no fmtp parameters.
        return "";
    }
}
