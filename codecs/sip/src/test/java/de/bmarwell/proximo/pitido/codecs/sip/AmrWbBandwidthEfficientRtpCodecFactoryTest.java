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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AmrWbBandwidthEfficientRtpCodecFactoryTest {

    private final AmrWbBandwidthEfficientRtpCodecFactory codec = new AmrWbBandwidthEfficientRtpCodecFactory();

    // -------------------------------------------------------------------------
    // Codec constants — no native library needed
    // -------------------------------------------------------------------------

    @Test
    void preferenceIs41() {
        // Given, When
        int pref = codec.preference();

        // Then
        assertEquals(41, pref, "BW-efficient is lower priority (higher number) than octet-aligned (40)");
    }

    @Test
    void matchesFmtpWithOctetAlignZero() {
        // Given
        String offeredFmtp = "octet-align=0";

        // When
        boolean matches = codec.matchesFmtp(offeredFmtp);

        // Then
        assertTrue(matches, "BW-efficient should match explicit octet-align=0");
    }

    @Test
    void matchesFmtpWithModeParamsNoAlignment() {
        // Given
        String offeredFmtp = "mode-set=0,1,2;mode-change-capability=2;max-red=0";

        // When
        boolean matches = codec.matchesFmtp(offeredFmtp);

        // Then
        assertTrue(matches, "BW-efficient should match mode params without octet-align (1und1 pattern)");
    }

    @Test
    void rejectsFmtpEmpty() {
        // Given
        String offeredFmtp = "";

        // When
        boolean matches = codec.matchesFmtp(offeredFmtp);

        // Then
        assertFalse(matches, "BW-efficient should reject empty fmtp (octet-aligned is default)");
    }

    @Test
    void rejectsFmtpWithOctetAlignOne() {
        // Given
        String offeredFmtp = "octet-align=1";

        // When
        boolean matches = codec.matchesFmtp(offeredFmtp);

        // Then
        assertFalse(matches, "BW-efficient should reject explicit octet-align=1 (use octet-aligned codec instead)");
    }
}
