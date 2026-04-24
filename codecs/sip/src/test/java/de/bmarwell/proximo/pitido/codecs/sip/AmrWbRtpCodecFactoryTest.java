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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

public class AmrWbRtpCodecFactoryTest {

    private final AmrWbRtpCodecFactory codec = new AmrWbRtpCodecFactory();

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
    void preferenceIsHigherThanG722() {
        // given
        var g722 = new G722RtpCodecFactory();

        // when / then
        assertEquals(
                true,
                codec.preference() < g722.preference(),
                "AMR-WB preference must be higher priority (lower number) than G.722");
    }

    // -------------------------------------------------------------------------
    // Tests that require libvo-amrwbenc — skipped automatically when not installed
    // -------------------------------------------------------------------------

    @Test
    void isAvailableAfterProbeWhenLibraryPresent() {
        // given
        AmrWbRtpCodecFactory factory = new AmrWbRtpCodecFactory();

        assumeTrue(factory.isAvailable(), "libvo-amrwbenc not available on this host — skipping");

        // when / then
        assertTrue(factory.isAvailable());
    }
}
