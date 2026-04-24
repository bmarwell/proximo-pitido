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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class G722RtpCodecTest {

    private final G722RtpCodec codec = new G722RtpCodecFactory().forCall("");

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void fmtpParamsIsEmpty() {
        assertEquals("", codec.fmtpParams());
    }

    @Test
    void payloadTypeIsNine() {
        assertEquals(9, codec.metadata().payloadType());
    }

    @Test
    void rtpClockRateIs8000() {
        assertEquals(8000, codec.metadata().rtpClockRate());
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
    void rtpTimestampIncrementIs160() {
        assertEquals(160, codec.metadata().rtpTimestampIncrement());
    }

    @Test
    void sdpNameIsG722() {
        assertEquals("G722", codec.metadata().sdpName());
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
        G722RtpCodecFactory factory = new G722RtpCodecFactory();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        G722RtpCodec callInstance = factory.forCall("");

        // when
        callInstance.close();

        // then: encoding after close should fail (arena is closed)
        assertThrows(Exception.class, () -> callInstance.encode(new short[320]));
    }
}
