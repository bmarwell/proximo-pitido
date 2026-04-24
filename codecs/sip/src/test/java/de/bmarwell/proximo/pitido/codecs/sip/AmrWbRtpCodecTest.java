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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class AmrWbRtpCodecTest {

    private final AmrWbRtpCodec codec = new AmrWbRtpCodec();

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void payloadTypeIs98() {
        assertEquals(98, codec.payloadType());
    }

    @Test
    void rtpClockRateIs16000() {
        assertEquals(16_000, codec.rtpClockRate());
    }

    @Test
    void inputSampleRateIs16000() {
        assertEquals(16_000, codec.inputSampleRate());
    }

    @Test
    void samplesPerFrameIs320() {
        assertEquals(320, codec.samplesPerFrame());
    }

    @Test
    void rtpTimestampIncrementIs320() {
        assertEquals(320, codec.rtpTimestampIncrement());
    }

    @Test
    void sdpNameIsAmrWb() {
        assertEquals("AMR-WB", codec.sdpName());
    }

    @Test
    void fmtpParamsIsOctetAlign() {
        assertEquals("octet-align=1", codec.fmtpParams());
    }

    @Test
    void preferenceIsHigherThanG722() {
        // given
        var g722 = new G722RtpCodec();

        // when / then
        assertEquals(
                true,
                codec.preference() < g722.preference(),
                "AMR-WB preference must be higher priority (lower number) than G.722");
    }

    @Test
    void encodeThrowsOnFactoryBean() {
        assertThrows(IllegalStateException.class, () -> codec.encode(new short[320]));
    }

    @Test
    void closeOnFactoryBean_isNoOp() {
        // given: a fresh factory bean (no callArena, no stateSegment)
        var factory = new AmrWbRtpCodec();

        // when / then: close() must not throw on the CDI factory bean
        factory.close();
    }

    // -------------------------------------------------------------------------
    // Tests that require libvo-amrwbenc — skipped automatically when not installed
    // -------------------------------------------------------------------------

    @Test
    void isAvailableAfterProbeWhenLibraryPresent() {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        // when / then
        assertEquals(true, factory.isAvailable());
    }

    @Test
    void forCallReturnsDifferentInstance() {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        // when
        RtpCodecFactory callInstance = factory.forCall();

        // then
        assertNotSame(factory, callInstance);
    }

    @Test
    void closeOnPerCallInstance_preventsSubsequentEncode() {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodecFactory callInstance = factory.forCall();

        // when
        callInstance.close();

        // then: encoding after close should fail
        assertThrows(Exception.class, () -> callInstance.encode(new short[320]));
    }

    @Test
    void silenceFrameProducesOctetAlignedPayload() throws IOException {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodecFactory encoder = factory.forCall();

        // when
        byte[] payload = encoder.encode(new short[320]);

        // then: at least CMR (1 byte) + ToC (1 byte) + speech data (≥ 1 byte)
        assertEquals(true, payload.length >= 3, "AMR-WB payload must contain CMR, ToC, and speech data");
        assertEquals((byte) 0xF0, payload[0], "CMR byte must be 0xF0 (no codec mode request)");
    }

    @Test
    void mode2EncodingProducesCorrectToCByte() throws IOException {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        // Mode 2 (12.65 kbps) with telekom's offered fmtp
        RtpCodecFactory encoder = factory.forCall("octet-align=1;mode-set=0,1,2;mode-change-capability=2;max-red=0");

        // when
        byte[] payload = encoder.encode(new short[320]);

        // then
        // ToC = F(0) | FT(4bits=2) | Q(1) | P(0) | P(0) = 0001 0100 = 0x14
        byte tocByte = payload[1];
        assertEquals(
                (byte) 0x14, tocByte, String.format("ToC byte for mode 2 must be 0x14, got 0x%02x", tocByte & 0xFF));
        assertEquals(true, payload.length >= 3, "Payload must have CMR + ToC + speech data");
    }

    @Test
    void payloadStructureDoesNotHaveDoubleHeader() throws IOException {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodecFactory encoder = factory.forCall("octet-align=1;mode-set=0,1,2");

        // when
        byte[] payload = encoder.encode(new short[320]);

        // then
        // Payload structure: [CMR=0xF0][ToC][speech...]
        // If E_IF_encode prepends a mode byte, we'd see it at payload[2]
        // For mode 2, a valid mode byte would be 0x02, but speech data should not start with mode byte
        byte cmr = payload[0];
        byte toc = payload[1];
        byte firstSpeechByte = payload[2];

        assertEquals((byte) 0xF0, cmr, "CMR must be 0xF0");
        assertEquals((byte) 0x14, toc, "ToC must be 0x14 for mode 2");

        // Check: firstSpeechByte should not be 0x02 (a suspiciously mode-like value)
        // and should be within typical PCM/compressed audio range
        assertEquals(
                true,
                firstSpeechByte != 0x02,
                String.format(
                        "First speech byte is 0x%02x — suspicious pattern suggests encoder may have prepended a mode byte",
                        firstSpeechByte & 0xFF));
    }
}
