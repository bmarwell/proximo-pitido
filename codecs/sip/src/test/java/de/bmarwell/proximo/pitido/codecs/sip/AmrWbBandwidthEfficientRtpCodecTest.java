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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for bandwidth-efficient AMR-WB codec.
 *
 * <p>These tests validate the bandwidth-efficient RTP payload format (RFC 4867 §4.3).
 */
class AmrWbBandwidthEfficientRtpCodecTest {

    private final AmrWbBandwidthEfficientRtpCodec codec = new AmrWbBandwidthEfficientRtpCodecFactory().forCall("");

    @Test
    void encodeMultipleFramesProduceConsistentPayloads() throws IOException {
        // Given
        assumeTrue(isLibVoAmrwbencAvailable(), "libvo-amrwbenc not available");

        var codecForCall = codec;
        short[] pcmFrame = generateTestFrame();

        // When
        byte[] payload1 = codecForCall.encode(pcmFrame);
        byte[] payload2 = codecForCall.encode(pcmFrame);

        // Then
        assertEquals(payload1.length, payload2.length, "Payload lengths must match");
        assertEquals(payload1[0], payload2[0], "ToC bytes must match");
    }

    @Test
    void payloadTypeIs104() {
        // Given, When
        int pt = codec.metadata().payloadType();

        // Then
        assertEquals(104, pt, "BW-efficient AMR-WB uses dynamic payload type 104");
    }

    @Test
    void rtpClockRateIs16000() {
        assertEquals(16_000, codec.metadata().rtpClockRate());
    }

    @Test
    void sdpNameIsAmrWb() {
        assertEquals("AMR-WB", codec.metadata().sdpName());
    }

    // -------------------------------------------------------------------------
    // Comparison with octet-aligned codec
    // -------------------------------------------------------------------------

    @Test
    void bandwidthEfficientPayloadIsShorterThanOctetAligned() throws IOException {
        // Given
        assumeTrue(isLibVoAmrwbencAvailable(), "libvo-amrwbenc not available");

        var octetAlignedCodec = new AmrWbRtpCodec("");
        // Ensure probe() has run to load libvo-amrwbenc

        short[] pcmFrame = generateTestFrame();

        // When
        byte[] bwPayload = codec.encode(pcmFrame);
        byte[] octetPayload = octetAlignedCodec.encode(pcmFrame);

        // Then
        // Given: When: Then:
        assertEquals(
                octetPayload.length - 1, bwPayload.length, "BW-efficient should be 1 byte shorter (no CMR header)");
    }

    // -------------------------------------------------------------------------
    // Payload format validation — requires native libvo-amrwbenc
    // -------------------------------------------------------------------------

    @Test
    void encodeProducesValidBandwidthEfficientPayload() throws IOException {
        // Given
        assumeTrue(isLibVoAmrwbencAvailable(), "libvo-amrwbenc not available");

        short[] pcmFrame = generateTestFrame();

        // When
        byte[] payload = codec.encode(pcmFrame);

        // Then
        // Given, When
        assertNotNull(payload, "Payload must not be null");
        assertEquals(33, payload.length, "BW-efficient payload for mode 2 should be 33 bytes");

        // RFC 4867 §4.3: Byte 0 = [CMR(4)][F(1)][FT_high(3)]
        byte byte0 = payload[0];
        int cmr = (byte0 >> 4) & 0x0F;
        int f = (byte0 >> 3) & 0x01;
        int ftHigh3 = byte0 & 0x07;

        // Byte 1 = [FT_low(1)][Q(1)][speech(6)]
        byte byte1 = payload[1];
        int ftLow1 = (byte1 >> 7) & 0x01;
        int qualityBit = (byte1 >> 6) & 0x01;

        // Reconstruct full FT from high 3 + low 1
        int frameType = (ftHigh3 << 1) | ftLow1;

        // Then
        assertEquals(2, cmr, "CMR should be 2 (encoding mode)");
        assertEquals(0, f, "Follow bit should be 0 (single frame)");
        assertEquals(2, frameType, "Frame type should be 2 (mode 2)");
        assertEquals(1, qualityBit, "Quality bit should be 1 (good)");
    }

    @Test
    void encodePayloadStartsWithValidToC() throws IOException {
        // Given
        assumeTrue(isLibVoAmrwbencAvailable(), "libvo-amrwbenc not available");

        short[] pcmFrame = generateTestFrame();

        // When
        byte[] payload = codec.encode(pcmFrame);

        // Then
        // RFC 4867 §4.3 bandwidth-efficient format:
        // Byte 0: [CMR(4)][F(1)][FT_high(3)]
        byte byte0 = payload[0];
        int cmr = (byte0 >> 4) & 0x0F;
        int f = (byte0 >> 3) & 0x01;
        int ftHigh3 = byte0 & 0x07;

        // For mode 2 with CMR set to encoding mode (2):
        // Byte 0 should be: [0010][0][001] = 0010 0001 = 0x21
        int expectedByte0 = (2 << 4) | (0 << 3) | 1; // CMR=2, F=0, FT_high=001
        assertEquals(
                expectedByte0,
                byte0 & 0xFF,
                String.format(
                        "BW-efficient byte 0 should be CMR+F+FT_high; expected 0x%02x but got 0x%02x",
                        expectedByte0, byte0 & 0xFF));

        // Byte 1 should have FT_low + Q (and 6 bits of speech):
        // [FT_low(1)][Q(1)][speech(6)]
        byte byte1 = payload[1];
        int ftLow1 = (byte1 >> 7) & 0x01;
        int qualityBit = (byte1 >> 6) & 0x01;

        assertEquals(0, ftLow1, "FT_low bit (bit 7 of byte 1) should be 0 for mode 2");
        assertEquals(1, qualityBit, "Quality bit (bit 6 of byte 1) should be 1 (good frame)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isLibVoAmrwbencAvailable() {
        try {
            final var factory = new AmrWbBandwidthEfficientRtpCodecFactory();

            return factory.isAvailable();
        } catch (UnsatisfiedLinkError | NoClassDefFoundError exception) {
            return false;
        } catch (Exception exception) {
            return true;
        }
    }

    private short[] generateTestFrame() {
        // Generate a simple test frame: 320 samples of varying amplitude (0x1000, 0x2000, ...)
        short[] frame = new short[320];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) (((i % 8) + 1) * 0x1000);
        }

        return frame;
    }
}
