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
import java.lang.invoke.MethodHandle;
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
     * Preference order: bandwidth-efficient is now preferred (lower number = higher preference).
     * Temporarily increased to 41 to test if octet-aligned codec resolves audio corruption.
     */
    private static final int PREFERENCE = 41;

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

    /** CDI no-args constructor. */
    public AmrWbBandwidthEfficientRtpCodec() {
        super();
    }

    /**
     * Package-scoped constructor for per-call instances created by {@link #createForCallInstance}.
     *
     * <p>Not intended for direct use outside this package.
     * The encoder state is already initialised and bound to the arena.
     *
     * @param eIfEncodeHandle downcall handle for {@code E_IF_encode}
     * @param arena           confined arena that owns the encoder state lifetime
     * @param stateSegment    arena-scoped encoder state
     * @param encodingMode    AMR-WB encoding mode (0–8)
     */
    AmrWbBandwidthEfficientRtpCodec(
            MethodHandle eIfEncodeHandle, Arena arena, MemorySegment stateSegment, int encodingMode) {
        super(eIfEncodeHandle, arena, stateSegment, encodingMode);
    }

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
     * Accepts AMR-WB ONLY when the caller explicitly specifies {@code octet-align=0}.
     *
     * <p>This codec is narrowly scoped to handle explicit bandwidth-efficient requests.
     * It REJECTS empty fmtp (ambiguous — now handled by octet-aligned codec).
     * It REJECTS fmtp with mode parameters that don't explicitly request bandwidth-efficient.
     * It ACCEPTS ONLY explicit {@code octet-align=0} (caller wants bandwidth-efficient format).
     *
     * <p>This narrow matching ensures octet-aligned codec (preference 40) wins for ambiguous offers,
     * while still supporting callers that explicitly request bandwidth-efficient (preference 41).
     */
    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        // Match explicit octet-align=0 OR mode params without explicit octet-align
        boolean hasExplicitOctetAlignZero = Arrays.stream(offeredFmtp.split(";"))
                .map(String::strip)
                .anyMatch(AmrWbBandwidthEfficientRtpCodec::isOctetAlignZeroParam);

        if (hasExplicitOctetAlignZero) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (explicit octet-align=0): {0}",
                    offeredFmtp);
            return true;
        }

        // Also match mode params without explicit octet-align (pragmatic: assume BW-efficient when alignment not
        // specified)
        String[] params = offeredFmtp.split(";");
        boolean hasOctetAlignExplicit =
                Arrays.stream(params).map(String::strip).anyMatch(AmrWbBandwidthEfficientRtpCodec::isOctetAlignParam);
        boolean hasModeParams = Arrays.stream(params)
                .map(String::strip)
                .anyMatch(p -> p.startsWith("mode-") || p.startsWith("max-red"));

        if (hasModeParams && !hasOctetAlignExplicit) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (mode params without explicit octet-align): {0}",
                    offeredFmtp);
            return true;
        }

        LOGGER.log(
                System.Logger.Level.TRACE, "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: rejected: {0}", offeredFmtp);
        return false;
    }

    private static boolean isOctetAlignZeroParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip()) && "0".equals(kv[1].strip());
    }

    private static boolean isOctetAlignParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip());
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

            // Log PCM input sample range for diagnostics
            short minSample = Short.MAX_VALUE;
            short maxSample = Short.MIN_VALUE;
            for (short sample : pcmFrame) {
                if (sample < minSample) minSample = sample;
                if (sample > maxSample) maxSample = sample;
            }
            short firstSample;
            if (pcmFrame.length > 0) {
                firstSample = pcmFrame[0];
            } else {
                firstSample = 0;
            }
            short lastSample;
            if (pcmFrame.length > 0) {
                lastSample = pcmFrame[pcmFrame.length - 1];
            } else {
                lastSample = 0;
            }

            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AMR-WB bandwidth-efficient encode: starting with encodingMode={0}, pcmSamples={1}, pcmRange=[{2},{3}], first={4}, last={5}",
                    this.encodingMode,
                    pcmFrame.length,
                    minSample,
                    maxSample,
                    firstSample,
                    lastSample);

            int speechBytes = invokeEncode(inputSeg, outputSeg);

            LOGGER.log(System.Logger.Level.TRACE, "Encoder returned speechBytes={0}", speechBytes);

            if (speechBytes < 0) {
                throw new IOException("E_IF_encode failed with error code " + speechBytes);
            }

            // Log a sample of the encoder output to verify it's not all zeros or garbage
            byte firstByte = outputSeg.get(ValueLayout.JAVA_BYTE, 0);
            byte secondByte;
            if (speechBytes > 1) {
                secondByte = outputSeg.get(ValueLayout.JAVA_BYTE, 1);
            } else {
                secondByte = 0;
            }
            byte thirdByte;
            if (speechBytes > 2) {
                thirdByte = outputSeg.get(ValueLayout.JAVA_BYTE, 2);
            } else {
                thirdByte = 0;
            }

            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "Encoder output first 3 bytes (hex): {0} {1} {2}",
                    String.format("%02x", firstByte & 0xFF),
                    String.format("%02x", secondByte & 0xFF),
                    String.format("%02x", thirdByte & 0xFF));

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
            } else {
                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "Encoder output first byte matches expected ToC (0x{0})",
                        String.format(java.util.Locale.ROOT, "%02x", firstEncoderByte & 0xFF));
            }

            // Extract the bandwidth-efficient payload: convert to byte array and return.
            // The encoder outputs exactly speechBytes, so we get the correctly-sized array directly.
            byte[] payload = outputSeg.asSlice(0L, speechBytes).toArray(ValueLayout.JAVA_BYTE);

            // RFC 4867 §4.3: Bandwidth-efficient ToC packing is different from octet-aligned!
            // The encoder outputs octet-aligned ToC format: F(1)|FT(4)|Q(1)|P(2)
            // We must convert to BW-efficient format: CMR(4)|F(1)|FT(3)|FT(1)|Q(1)|...
            //
            // For a single frame, the packing is:
            // - Byte 1 bits 7-4: CMR (4 bits)
            // - Byte 1 bits 3: F (1 bit)
            // - Byte 1 bits 2-0: FT high 3 bits
            // - Byte 2 bits 7-6: FT low 1 bit, Q
            // - Byte 2 bits 5-0: Speech data starts here (or padding if more frames)

            if (payload.length > 1) {
                // Extract octet-aligned ToC from encoder output
                byte encoderOctetAlignedToc = payload[0];

                // Octet-aligned format: 0|FT(4)|Q|00
                int ftFromEncoder = (encoderOctetAlignedToc >> 3) & 0x0F;
                int qFromEncoder = (encoderOctetAlignedToc >> 2) & 0x01;

                // Build BW-efficient format
                // Byte 0: [CMR(4)][F(1)][FT high 3 bits]
                int f = 0; // Single frame, no continuation
                int ftHigh3 = (ftFromEncoder >> 1) & 0x07;
                int cmr = this.encodingMode; // Send the encoding mode we're actually using
                byte bwEfficientByte0 = (byte)
                        (((cmr & 0x0F) << 4)
                                | // CMR
                                ((f & 0x01) << 3)
                                | // F
                                (ftHigh3 & 0x07) // FT high 3 bits
                        );

                // Byte 1: [FT low 1 bit][Q][first 6 bits of speech data]
                // Extract first 6 bits from encoder's byte 1 (which contains ToC[1:2] + padding + speech start)
                // In encoder output: byte[1] =
                // [ToC_bit_1][ToC_bit_0][0][0][speech_bit_3][speech_bit_2][speech_bit_1][speech_bit_0]
                // We want the speech bits (bottom 6 bits when ToC is stripped): payload[1] has 8 bits, we take bits 5-0
                int ftLow1 = ftFromEncoder & 0x01;
                int speechBits6from1 = payload[1] & 0x3F; // Bottom 6 bits of payload[1] contain first 6 bits of speech
                byte bwEfficientByte1 = (byte)
                        (((ftLow1 & 0x01) << 7)
                                | // FT low bit (bit 7)
                                ((qFromEncoder & 0x01) << 6)
                                | // Q (bit 6)
                                (speechBits6from1 & 0x3F) // Speech bits 5-0
                        );

                // For remaining speech bytes, they need bit-shifting because we only consumed 6 bits from byte 1
                // Encoder byte 1 contains: [ToC(2)][P(2)][speech_bits(4)]
                // We took bottom 6 bits, leaving top 2 bits of speech from byte 1 + all of bytes 2+ to shift
                // Shift the speech stream right by 2 bits to account for the 2 bits we moved to byte 1

                byte[] newPayload = new byte[payload.length];
                newPayload[0] = bwEfficientByte0;
                newPayload[1] = bwEfficientByte1;

                // Shift remaining speech right by 2 bits
                // payload[1] bits 7-6 (2 bits) go to newPayload[2] bits 7-6
                // payload[2] bits 7-0 go to newPayload[2] bits 5-0 + newPayload[3] bits 7-6
                // ... and so on for all remaining bytes
                int carryover = (payload[1] >> 6) & 0x03; // Top 2 bits of encoder byte 1
                for (int i = 2; i < payload.length; i++) {
                    newPayload[i] = (byte)
                            (((payload[i] & 0xFF) >> 2) // Current byte shifted right by 2
                                    | ((carryover & 0x03) << 6) // Carryover from previous byte to top 2 bits
                            );
                    carryover = (payload[i] & 0x03); // Save bottom 2 bits for next iteration
                }
                payload = newPayload;

                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "BW-efficient ToC conversion: encoder format 0x{0} → BW-efficient 0x{1}0x{2} (mode={3}, F={4}, FT={5}, Q={6})",
                        String.format(java.util.Locale.ROOT, "%02x", encoderOctetAlignedToc & 0xFF),
                        String.format(java.util.Locale.ROOT, "%02x", bwEfficientByte0 & 0xFF),
                        String.format(java.util.Locale.ROOT, "%02x", bwEfficientByte1 & 0xFF),
                        this.encodingMode,
                        f,
                        ftFromEncoder,
                        qFromEncoder);
            }

            // Analyze ToC byte for diagnostics
            if (payload.length > 1) {
                byte bwEfficientByte0 = payload[0];
                byte bwEfficientByte1 = payload[1];

                // RFC 4867 §4.3 BW-efficient format:
                // Byte 0: [CMR(4)][F(1)][FT_high(3)]
                // Byte 1: [FT_low(1)][Q(1)][speech(6)]
                int cmrBits = (bwEfficientByte0 >> 4) & 0x0F;
                int f = (bwEfficientByte0 >> 3) & 0x01;
                int ftHigh3 = (bwEfficientByte0 & 0x07);
                int ftLow1 = (bwEfficientByte1 >> 7) & 0x01;
                int qualityBit = (bwEfficientByte1 >> 6) & 0x01;
                int frameType = (ftHigh3 << 1) | ftLow1; // Reconstruct 4-bit FT

                // Build hex dump of first few bytes for diagnostic
                StringBuilder hexDump = new StringBuilder();
                int bytesToShow = Math.min(10, payload.length);
                for (int i = 0; i < bytesToShow; i++) {
                    if (i > 0) hexDump.append(' ');
                    hexDump.append(String.format("%02x", payload[i] & 0xFF));
                }
                if (payload.length > bytesToShow) {
                    hexDump.append(String.format(" ... (%d more)", payload.length - bytesToShow));
                }

                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "AMR-WB BW-efficient payload: encodingMode={0} payloadBytes={1} CMR={2} F={3} FT={4} Q={5} hex=[{6}]",
                        this.encodingMode,
                        payload.length,
                        cmrBits,
                        f,
                        frameType,
                        qualityBit,
                        hexDump.toString());
            }

            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AMR-WB bandwidth-efficient encode complete: speechBytes={0} payloadBytes={1}",
                    speechBytes,
                    payload.length);

            return payload;
        }
    }

    /**
     * Factory method that creates a {@code AmrWbBandwidthEfficientRtpCodec} instance.
     *
     * <p>Overrides the parent's {@link AmrWbRtpCodec#createForCallInstance(MethodHandle, Arena, MemorySegment, int)}
     * to ensure that a bandwidth-efficient codec instance is created,
     * not the parent's octet-aligned type.
     * This ensures the correct RTP payload format is encoded.
     *
     * @param eIfEncodeHandle downcall handle for {@code E_IF_encode}
     * @param arena           confined arena that owns the encoder state lifetime
     * @param stateSegment    arena-scoped encoder state
     * @param encodingMode    AMR-WB encoding mode (0–8)
     * @return a fully initialised per-call {@link AmrWbBandwidthEfficientRtpCodec}
     */
    @Override
    protected RtpCodec createForCallInstance(
            MethodHandle eIfEncodeHandle, Arena arena, MemorySegment stateSegment, int encodingMode) {
        LOGGER.log(
                System.Logger.Level.TRACE,
                "AmrWbBandwidthEfficientRtpCodec.createForCallInstance: creating instance with encodingMode={0}",
                encodingMode);
        return new AmrWbBandwidthEfficientRtpCodec(eIfEncodeHandle, arena, stateSegment, encodingMode);
    }

    @Override
    public String fmtpParams() {
        // Bandwidth-efficient mode requires no fmtp parameters.
        return "";
    }

    @Override
    public String fmtpAnswer(String offeredFmtp) {
        // RFC 4867: Simply echo back the offered fmtp.
        // The remote side knows what we're sending via the CMR field in each frame.
        // (CMR is set to our encoding mode; the decoder will adapt to what we send.)
        String answer = offeredFmtp;

        LOGGER.log(
                System.Logger.Level.TRACE,
                "AmrWbBandwidthEfficientRtpCodec.fmtpAnswer: offeredFmtp=''{0}'' encodingMode={1} → answer=''{2}''",
                offeredFmtp,
                this.encodingMode,
                answer);

        return answer;
    }
}
