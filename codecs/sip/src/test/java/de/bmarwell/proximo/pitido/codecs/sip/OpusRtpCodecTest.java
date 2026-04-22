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

import org.junit.jupiter.api.Test;

class OpusRtpCodecTest {

    private final OpusRtpCodec codec = new OpusRtpCodec();

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void payloadTypeIs111() {
        assertEquals(111, codec.payloadType());
    }

    @Test
    void rtpClockRateIs48000() {
        assertEquals(48_000, codec.rtpClockRate());
    }

    @Test
    void inputSampleRateIs48000() {
        assertEquals(48_000, codec.inputSampleRate());
    }

    @Test
    void samplesPerFrameIs960() {
        assertEquals(960, codec.samplesPerFrame());
    }

    @Test
    void rtpTimestampIncrementIs960() {
        assertEquals(960, codec.rtpTimestampIncrement());
    }

    @Test
    void sdpNameIsOpus() {
        assertEquals("opus", codec.sdpName());
    }

    @Test
    void sdpChannelCountIsTwo() {
        // given: RFC 7587 §5 mandates 2 in SDP regardless of actual channel count
        // when / then
        assertEquals(2, codec.sdpChannelCount());
    }

    @Test
    void fmtpParamsContainsFec() {
        assertEquals("useinbandfec=1", codec.fmtpParams());
    }

    @Test
    void preferenceIs30() {
        assertEquals(30, codec.preference());
    }

    @Test
    void preferenceIsHigherThanG722() {
        // given
        var g722 = new G722RtpCodec();

        // when / then: Opus (30) must beat G.722 (50) — lower number = higher priority
        assertEquals(
                true,
                codec.preference() < g722.preference(),
                "Opus preference must be higher priority (lower number) than G.722");
    }

    @Test
    void encodeThrowsOnFactoryBean() {
        // given: an un-probed CDI factory bean with no encoder state
        // when / then
        assertThrows(IllegalStateException.class, () -> codec.encode(new short[960]));
    }

    // -------------------------------------------------------------------------
    // Tests that require libopus — skipped automatically when not installed
    // -------------------------------------------------------------------------

    @Test
    void closeOnFactoryBean_isNoOp() {
        // given: a fresh factory bean (no callArena)
        var factory = new OpusRtpCodec();

        // when / then: close() must not throw on the CDI factory bean
        factory.close();
    }

    @Test
    void forCallReturnsDifferentInstance() {
        // given
        OpusRtpCodec factory = new OpusRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        // when
        RtpCodec callInstance = factory.forCall();

        // then
        assertNotSame(factory, callInstance);
    }

    @Test
    void forCallReturnsFreshInstanceEachTime() {
        // given
        OpusRtpCodec factory = new OpusRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        // when
        RtpCodec first = factory.forCall();
        RtpCodec second = factory.forCall();

        // then: each call leg must have independent encoder state
        assertNotSame(first, second);
    }

    @Test
    void closeOnPerCallInstance_releasesArena() {
        // given
        OpusRtpCodec factory = new OpusRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        RtpCodec callInstance = factory.forCall();

        // when
        callInstance.close();

        // then: encoding after close should fail (arena is closed)
        assertThrows(Exception.class, () -> callInstance.encode(new short[960]));
    }

    @Test
    void silenceFrameEncodesSuccessfully() throws Exception {
        // given
        OpusRtpCodec factory = new OpusRtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        RtpCodec encoder = factory.forCall();
        short[] silence = new short[960];

        // when
        byte[] encoded = encoder.encode(silence);

        // then: Opus should always produce at least a minimal comfort-noise frame
        assertEquals(true, encoded.length > 0, "Encoded frame must not be empty");
    }
}
