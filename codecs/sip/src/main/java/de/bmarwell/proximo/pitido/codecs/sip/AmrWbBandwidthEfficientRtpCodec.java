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

public class AmrWbBandwidthEfficientRtpCodec extends AmrWbRtpCodec implements RtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbBandwidthEfficientRtpCodec.class.getName());

    private static final RtpCodecMetadata METADATA = new AmrWbBandwidthEfficientMetadata();

    AmrWbBandwidthEfficientRtpCodec(String offeredFmtp) {
        super(offeredFmtp);
    }

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
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

            if (speechBytes > MAX_ENCODED_BYTES) {
                throw new IOException(
                        "E_IF_encode wrote " + speechBytes + " bytes — exceeds MAX_ENCODED_BYTES=" + MAX_ENCODED_BYTES
                                + "; native buffer overflow occurred before this check. Increase MAX_ENCODED_BYTES.");
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
            The first 6 bits of speech come from encoder's byte 1.
            Encoder byte 1 has 8 bits of speech data.
            We need the top 6 bits (bits 7-2), right-aligned to positions 5-0.
            */
            int speechBits6from1 = (encoderOutput[1] >> 2) & 0x3F;
            bwEfficientPayload[1] =
                    (byte) (((ftLow1 & 0x01) << 7) | ((qFromEncoder & 0x01) << 6) | (speechBits6from1 & 0x3F));

            /*
              Remaining bytes: shift speech left by 4 bits.
              We've already extracted top 6 bits (bits 7-2) from encoder byte 1.
              Remaining 2 bits: bits 1-0 of encoder byte 1 become the top 2 bits of bwEfficientPayload[2].
              Plus all of encoder bytes 2-32.

              RFC 4867 §4.3: BW-efficient payload is 263 bits (CMR(4) + ToC(6) + speech(253)),
              which rounds up to 33 bytes = 264 bits.
              The lower 1 bit of byte 32 is padding and MUST be zeroed per RFC.
            */
            int carryover = encoderOutput[1] & 0x03; // Bottom 2 bits of encoder byte 1
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
}
