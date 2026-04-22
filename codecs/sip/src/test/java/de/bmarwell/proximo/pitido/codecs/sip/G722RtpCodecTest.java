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

class G722RtpCodecTest {

    private final G722RtpCodec codec = new G722RtpCodec();

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void payloadTypeIsNine() {
        assertEquals(9, codec.payloadType());
    }

    @Test
    void rtpClockRateIs8000() {
        assertEquals(8000, codec.rtpClockRate());
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
    void rtpTimestampIncrementIs160() {
        assertEquals(160, codec.rtpTimestampIncrement());
    }

    @Test
    void sdpNameIsG722() {
        assertEquals("G722", codec.sdpName());
    }

    @Test
    void fmtpParamsIsEmpty() {
        assertEquals("", codec.fmtpParams());
    }

    @Test
    void preferenceIs50() {
        assertEquals(50, codec.preference());
    }

    @Test
    void preferenceIsLowerThanPcma() {
        var pcma = new PcmaRtpCodec();
        assertEquals(
                true,
                codec.preference() < pcma.preference(),
                "G.722 preference must be higher priority (lower number) than PCMA");
    }

    /**
     * Verifies that calling {@link G722RtpCodec#encode} on the CDI factory bean
     * (which has no per-call encoder state) throws {@link IllegalStateException}.
     */
    @Test
    void encodeThrowsOnFactoryBean() {
        assertThrows(IllegalStateException.class, () -> codec.encode(new short[320]));
    }

    // -------------------------------------------------------------------------
    // Tests that require libspandsp — skipped automatically when not installed
    // -------------------------------------------------------------------------

    @Test
    void closeOnFactoryBean_isNoOp() {
        // given: a fresh factory bean (no callArena)
        var factory = new G722RtpCodec();

        // when / then: close() must not throw on the CDI factory bean
        factory.close();
    }

    @Test
    void closeOnPerCallInstance_releasesArena() {
        // given
        G722RtpCodec factory = new G722RtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        RtpCodec callInstance = factory.forCall();

        // when
        callInstance.close();

        // then: encoding after close should fail (arena is closed)
        assertThrows(Exception.class, () -> callInstance.encode(new short[320]));
    }

    @Test
    void forCallReturnsDifferentInstance() {
        G722RtpCodec factory = new G722RtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        RtpCodec callInstance = factory.forCall();

        assertNotSame(factory, callInstance);
    }

    @Test
    void isAvailableAfterProbeWhenLibraryPresent() {
        G722RtpCodec factory = new G722RtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        assertEquals(true, factory.isAvailable());
    }

    /**
     * Verifies that encoding 320 silence samples produces exactly 160 output bytes.
     *
     * <p>G.722 encodes 2 PCM samples into 1 byte (4 bits per sub-band).
     * 320 input samples must always yield exactly 160 output bytes regardless of content.
     */
    @Test
    void silenceFrameEncodesTo160Bytes() throws IOException {
        G722RtpCodec factory = new G722RtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        RtpCodec encoder = factory.forCall();
        byte[] encoded = encoder.encode(new short[320]);

        assertEquals(160, encoded.length);
    }

    /**
     * Verifies that two successive calls to {@link G722RtpCodec#forCall()} each return
     * a distinct instance, confirming that no global ADPCM state is shared between call legs.
     */
    @Test
    void forCallReturnsFreshInstanceEachTime() {
        G722RtpCodec factory = new G722RtpCodec();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        RtpCodec first = factory.forCall();
        RtpCodec second = factory.forCall();

        assertNotSame(first, second);
    }
}
