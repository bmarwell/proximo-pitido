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

import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logBwEfficientPayload;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logBwEfficientToCANConversion;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logEncodeComplete;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logEncoderOutputSample;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logPcmInputDiagnostics;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logToCANVersionMatch;
import static de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging.logToCANVersionMismatch;

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
            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (explicit octet-align=0): {0}",
                        offeredFmtp);
            }
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
            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (mode params without explicit octet-align): {0}",
                        offeredFmtp);
            }
            return true;
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: rejected: {0}",
                    offeredFmtp);
        }
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
     * <p>The native encoder outputs 33 bytes in octet-aligned format: [ToC(octet-aligned)][speech(32 bytes)].
     * We convert this to bandwidth-efficient format by prepending 4 bits of CMR and packing the ToC+speech data.
     *
     * <p>RFC 4867 §4.3 bandwidth-efficient payload structure for single frame (F=0):
     * Byte 0: [CMR(4)][F(1)][FT_high(3)]
     * Byte 1: [FT_low(1)][Q(1)][speech(6)]
     * Bytes 2-32: [speech shifted left by 4 bits]
     *
     * @param pcmFrame 320 mono PCM samples at 16 kHz
     * @return RTP payload in bandwidth-efficient format (33 bytes)
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

            /* Collect PCM input diagnostics for trace logging. */
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

            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                logPcmInputDiagnostics(
                        this.encodingMode, pcmFrame.length, minSample, maxSample, firstSample, lastSample);
            }

            int speechBytes = invokeEncode(inputSeg, outputSeg);

            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(System.Logger.Level.TRACE, "Encoder returned speechBytes={0}", speechBytes);
            }

            if (speechBytes < 0) {
                throw new IOException("E_IF_encode failed with error code " + speechBytes);
            }

            /* Extract sample of encoder output bytes for diagnostics. */
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

            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                logEncoderOutputSample(firstByte, secondByte, thirdByte);
            }

            /* Sanity check: encoder should output octet-aligned ToC as first byte. */
            byte expectedOctetAlignedToC = (byte) ((this.encodingMode << 3) | 0x04);
            byte firstEncoderByte = outputSeg.get(ValueLayout.JAVA_BYTE, 0);

            if (firstEncoderByte != expectedOctetAlignedToC) {
                logToCANVersionMismatch(firstEncoderByte, expectedOctetAlignedToC, this.encodingMode);
            } else if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                logToCANVersionMatch(firstEncoderByte);
            }

            /* Get encoder output as byte array. */
            byte[] encoderOutput = outputSeg.asSlice(0L, speechBytes).toArray(ValueLayout.JAVA_BYTE);

            /* Convert from octet-aligned to bandwidth-efficient format by prepending CMR
            and packing the ToC+speech data to the right.

            Encoder output format (octet-aligned):
              Byte 0: [0][FT(4)][Q][P(2)]
              Bytes 1-32: [speech data (256 bits)]

            BW-efficient output format:
              Byte 0: [CMR(4)][F(1)][FT_high(3)]
              Byte 1: [FT_low(1)][Q(1)][speech(6)]
              Bytes 2-32: [speech shifted left by 4 bits]

            We need to:
            1. Extract FT and Q from encoder byte 0
            2. Build new byte 0 with CMR | F | FT_high
            3. Build new byte 1 with FT_low | Q | first 6 bits of speech
            4. Shift all remaining speech left by 4 bits (since we used 4 bits of CMR)
            */
            byte[] bwEfficientPayload = new byte[encoderOutput.length];

            /* Extract FT (4 bits) and Q (1 bit) from octet-aligned ToC. */
            int ftFromEncoder = (firstEncoderByte >> 3) & 0x0F;
            int qFromEncoder = (firstEncoderByte >> 2) & 0x01;

            /* Split FT into high 3 bits and low 1 bit. */
            int ftHigh3 = (ftFromEncoder >> 1) & 0x07;
            int ftLow1 = ftFromEncoder & 0x01;

            /* Byte 0: [CMR(4)][F(1)][FT_high(3)]. */
            int cmr = this.encodingMode; // Send the encoding mode we're actually using
            int f = 0; // Single frame, no continuation
            bwEfficientPayload[0] = (byte) (((cmr & 0x0F) << 4) | ((f & 0x01) << 3) | (ftHigh3 & 0x07));

            /* Byte 1: [FT_low(1)][Q(1)][speech(6)].
            The first 6 bits of speech come from encoder's byte 1 (which has [ToC][P][speech(4)])
            We want bits 5-0 of encoder byte 1 (the bottom 6 bits are the 4 speech bits + padding)
            */
            int speechBits6from1 = encoderOutput[1] & 0x3F;
            bwEfficientPayload[1] =
                    (byte) (((ftLow1 & 0x01) << 7) | ((qFromEncoder & 0x01) << 6) | (speechBits6from1 & 0x3F));

            /*
              Remaining bytes: shift speech left by 4 bits.
              Encoder byte 1 has 2 bits of ToC+padding in the top, which we don't use.
              Those 2 bits represent "used bits", so the remaining speech starts 2 bits into byte 1.
              We've already extracted 6 bits from byte 1 for bwEfficientPayload[1].
              Remaining speech: 2 bits from byte 1 (bits 7-6) + all of bytes 2-32.
              These 2 bits become the top 2 bits of bwEfficientPayload[2].

              RFC 4867 §4.3: BW-efficient payload is 263 bits (CMR(4) + ToC(6) + speech(253)),
              which rounds up to 33 bytes = 264 bits.
              The lower 1 bit of byte 32 is padding and MUST be zeroed per RFC.
            */
            int carryover = (encoderOutput[1] >> 6) & 0x03; // Top 2 bits of encoder byte 1
            for (int i = 2; i < encoderOutput.length; i++) {
                bwEfficientPayload[i] = (byte) (((encoderOutput[i] & 0xFF) >> 2) | ((carryover & 0x03) << 6));
                carryover = (encoderOutput[i] & 0x03); // Save bottom 2 bits for next iteration
            }

            /* RFC 4867 §4.3 padding: zero out the lowest 1 bit of the last byte (padding). */
            if (bwEfficientPayload.length > 0) {
                bwEfficientPayload[bwEfficientPayload.length - 1] &= 0xFE; // Clear bit 0 only
            }

            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                logBwEfficientToCANConversion(
                        firstEncoderByte,
                        bwEfficientPayload[0],
                        bwEfficientPayload[1],
                        this.encodingMode,
                        f,
                        ftFromEncoder,
                        qFromEncoder);
            }

            /* Analyze ToC byte for diagnostics. */
            if (bwEfficientPayload.length > 1 && LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                byte bwByte0 = bwEfficientPayload[0];
                byte bwByte1 = bwEfficientPayload[1];

                int cmrBits = (bwByte0 >> 4) & 0x0F;
                int fBit = (bwByte0 >> 3) & 0x01;
                int ftHigh = (bwByte0 & 0x07);
                int ftLow = (bwByte1 >> 7) & 0x01;
                int qualityBit = (bwByte1 >> 6) & 0x01;
                int frameType = (ftHigh << 1) | ftLow; // Reconstruct 4-bit FT

                logBwEfficientPayload(this.encodingMode, bwEfficientPayload, cmrBits, fBit, frameType, qualityBit);
            }

            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                logEncodeComplete(speechBytes, bwEfficientPayload.length);
            }

            return bwEfficientPayload;
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
    protected RtpCodecFactory createForCallInstance(
            MethodHandle eIfEncodeHandle, Arena arena, MemorySegment stateSegment, int encodingMode) {
        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.createForCallInstance: creating instance with encodingMode={0}",
                    encodingMode);
        }

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

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.fmtpAnswer: offeredFmtp=''{0}'' encodingMode={1} → answer=''{2}''",
                    offeredFmtp,
                    this.encodingMode,
                    offeredFmtp);
        }

        return offeredFmtp;
    }
}
