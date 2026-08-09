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
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class AmrWbBandwidthEfficientRtpCodec extends AmrWbRtpCodec implements RtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbBandwidthEfficientRtpCodec.class.getName());

    private static final RtpCodecMetadata METADATA = new AmrWbBandwidthEfficientMetadata();

    AmrWbBandwidthEfficientRtpCodec(String offeredFmtp, AmrWbEncodeService encodeService) {
        super(offeredFmtp, encodeService);
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

        // Copy PCM into the pre-allocated shared-arena input segment.
        // The parent class allocates reusableInputSegment and reusableOutputSegment on
        // callArena (Arena.ofShared()), so the encoder thread can safely access them.
        MemorySegment.copy(pcmFrame, 0, this.reusableInputSegment, ValueLayout.JAVA_SHORT, 0, pcmFrame.length);

        /* Collect PCM input diagnostics for trace logging. */
        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
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
            logPcmInputDiagnostics(this.encodingMode, pcmFrame.length, minSample, maxSample, firstSample, lastSample);
        }

        int speechBytes = invokeEncode(this.reusableInputSegment, this.reusableOutputSegment);

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
        byte firstByte = this.reusableOutputSegment.get(ValueLayout.JAVA_BYTE, 0);
        byte secondByte;
        if (speechBytes > 1) {
            secondByte = this.reusableOutputSegment.get(ValueLayout.JAVA_BYTE, 1);
        } else {
            secondByte = 0;
        }
        byte thirdByte;
        if (speechBytes > 2) {
            thirdByte = this.reusableOutputSegment.get(ValueLayout.JAVA_BYTE, 2);
        } else {
            thirdByte = 0;
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            logEncoderOutputSample(firstByte, secondByte, thirdByte);
        }

        /* Sanity check: encoder should output octet-aligned ToC as first byte. */
        byte expectedOctetAlignedToC = (byte) ((this.encodingMode << 3) | 0x04);

        if (firstByte != expectedOctetAlignedToC) {
            logToCANVersionMismatch(firstByte, expectedOctetAlignedToC, this.encodingMode);
        } else if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            logToCANVersionMatch(firstByte);
        }

        /* Get encoder output as byte array. */
        byte[] encoderOutput =
                this.reusableOutputSegment.asSlice(0L, speechBytes).toArray(ValueLayout.JAVA_BYTE);

        /* Convert from octet-aligned to bandwidth-efficient format.
        See full format description in the previous encode() implementation. */
        byte[] bwEfficientPayload = new byte[encoderOutput.length];

        int ftFromEncoder = (firstByte >> 3) & 0x0F;
        int qFromEncoder = (firstByte >> 2) & 0x01;
        int ftHigh3 = (ftFromEncoder >> 1) & 0x07;
        int ftLow1 = ftFromEncoder & 0x01;

        int cmr = this.encodingMode;
        int f = 0;
        bwEfficientPayload[0] = (byte) (((cmr & 0x0F) << 4) | ((f & 0x01) << 3) | (ftHigh3 & 0x07));

        int speechBits6from1 = (encoderOutput[1] >> 2) & 0x3F;
        bwEfficientPayload[1] =
                (byte) (((ftLow1 & 0x01) << 7) | ((qFromEncoder & 0x01) << 6) | (speechBits6from1 & 0x3F));

        int carryover = encoderOutput[1] & 0x03;
        for (int i = 2; i < encoderOutput.length; i++) {
            bwEfficientPayload[i] = (byte) (((encoderOutput[i] & 0xFF) >> 2) | ((carryover & 0x03) << 6));
            carryover = (encoderOutput[i] & 0x03);
        }

        if (bwEfficientPayload.length > 0) {
            bwEfficientPayload[bwEfficientPayload.length - 1] &= 0xFE;
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            logBwEfficientToCANConversion(
                    firstByte,
                    bwEfficientPayload[0],
                    bwEfficientPayload[1],
                    this.encodingMode,
                    f,
                    ftFromEncoder,
                    qFromEncoder);
        }

        if (bwEfficientPayload.length > 1 && LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            byte bwByte0 = bwEfficientPayload[0];
            byte bwByte1 = bwEfficientPayload[1];
            int cmrBits = (bwByte0 >> 4) & 0x0F;
            int fBit = (bwByte0 >> 3) & 0x01;
            int ftHigh = bwByte0 & 0x07;
            int ftLow = (bwByte1 >> 7) & 0x01;
            int qualityBit = (bwByte1 >> 6) & 0x01;
            int frameType = (ftHigh << 1) | ftLow;
            logBwEfficientPayload(this.encodingMode, bwEfficientPayload, cmrBits, fBit, frameType, qualityBit);
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            logEncodeComplete(speechBytes, bwEfficientPayload.length);
        }

        return bwEfficientPayload;
    }
}
