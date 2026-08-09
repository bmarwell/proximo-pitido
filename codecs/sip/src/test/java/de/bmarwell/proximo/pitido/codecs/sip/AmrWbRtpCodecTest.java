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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.bmarwell.proximo.pitido.codecs.sip.extension.NativeCodec;
import java.io.IOException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

@NativeCodec(AmrWbRtpCodecFactory.class)
class AmrWbRtpCodecTest {

    private final AmrWbRtpCodec codec = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor()).forCall("");

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void fmtpParamsIsOctetAlign() {
        assertEquals("octet-align=1", codec.fmtpParams());
    }

    @Test
    void payloadTypeIs98() {
        assertEquals(98, codec.metadata().payloadType());
    }

    @Test
    void rtpClockRateIs16000() {
        assertEquals(16_000, codec.metadata().rtpClockRate());
    }

    @Test
    void inputSampleRateIs16000() {
        assertEquals(16_000, codec.metadata().inputSampleRate());
    }

    @Test
    void samplesPerFrameIs320() {
        assertEquals(320, codec.metadata().samplesPerFrame());
    }

    @Test
    void rtpTimestampIncrementIs320() {
        assertEquals(320, codec.metadata().rtpTimestampIncrement());
    }

    @Test
    void sdpNameIsAmrWb() {
        assertEquals("AMR-WB", codec.metadata().sdpName());
    }

    @Test
    void closeOnFactoryBean_isNoOp() {
        // given: a fresh per-call instance from the factory
        var factory = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor());
        var instance = factory.forCall("");

        // when / then: close() must not throw
        instance.close();
    }

    @Test
    void closeOnPerCallInstance_preventsSubsequentEncode() {
        // given
        AmrWbRtpCodecFactory factory = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor());

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodec callInstance = factory.forCall("");

        // when
        callInstance.close();

        // then: encoding after close should fail
        assertThrows(Exception.class, () -> callInstance.encode(new short[320]));
    }

    @Test
    void silenceFrameProducesOctetAlignedPayload() throws IOException {
        // given
        AmrWbRtpCodecFactory factory = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor());

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodec encoder = factory.forCall("");

        // when
        byte[] payload = encoder.encode(new short[320]);

        // then: at least CMR (1 byte) + ToC (1 byte) + speech data (≥ 1 byte)
        assertTrue(payload.length >= 3, "AMR-WB payload must contain CMR, ToC, and speech data");
        assertEquals((byte) 0xF0, payload[0], "CMR byte must be 0xF0 (no codec mode request)");
    }

    @Test
    void mode2EncodingProducesCorrectToCByte() throws IOException {
        // given
        AmrWbRtpCodecFactory factory = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor());

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        // Mode 2 (12.65 kbps) with telekom's offered fmtp
        RtpCodec encoder = factory.forCall("octet-align=1;mode-set=0,1,2;mode-change-capability=2;max-red=0");

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
        AmrWbRtpCodecFactory factory = new AmrWbRtpCodecFactory(Executors.newSingleThreadExecutor());

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodec encoder = factory.forCall("octet-align=1;mode-set=0,1,2");

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
