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
        RtpCodec callInstance = factory.forCall();

        // then
        assertNotSame(factory, callInstance);
    }

    @Test
    void closeOnPerCallInstance_preventsSubsequentEncode() {
        // given
        AmrWbRtpCodec factory = new AmrWbRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        RtpCodec callInstance = factory.forCall();

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

        RtpCodec encoder = factory.forCall();

        // when
        byte[] payload = encoder.encode(new short[320]);

        // then: at least CMR (1 byte) + ToC (1 byte) + speech data (≥ 1 byte)
        assertEquals(true, payload.length >= 3, "AMR-WB payload must contain CMR, ToC, and speech data");
        assertEquals((byte) 0xF0, payload[0], "CMR byte must be 0xF0 (no codec mode request)");
    }
}
