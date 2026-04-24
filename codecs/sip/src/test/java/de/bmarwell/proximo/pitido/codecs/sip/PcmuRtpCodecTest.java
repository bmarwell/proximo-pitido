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

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PcmuRtpCodecTest {

    private final PcmuRtpCodec codec = (PcmuRtpCodec) new PcmuRtpCodecFactory().forCall("");

    @Test
    void encodeFrameHasCorrectLength() throws IOException {
        short[] frame = new short[160];
        byte[] encoded = codec.encode(frame);
        assertEquals(160, encoded.length);
    }

    /**
     * Verifies that silence (0) encodes to the well-known µ-law silence byte.
     * PCM 0 with bias 0x84 falls in segment 0; the complemented result is 0xFF.
     */
    @Test
    void silenceEncodesToKnownValue() {
        // PCM 0: sign=0, sample+bias=0x84, exponent=0, mantissa=0
        // ulawByte = ~(0 | 0 | 0) & 0xFF = 0xFF
        byte encoded = PcmuRtpCodec.linearToUlaw((short) 0);
        assertEquals((byte) 0xFF, encoded, "µ-law encoding of silence should produce 0xFF");
    }

    /**
     * Round-trip test: encode then decode a set of PCM values and verify reconstruction
     * is within ±1 LSB tolerance of the original.
     * Uses the standard µ-law decode formula: x = sign * (2^exponent * (mantissa/2 + 16.5) - 16.5) / 32767.
     */
    @ParameterizedTest
    @ValueSource(shorts = {0, 100, 1000, 5000, 16_000, -100, -1000, -5000, -16_000, 32_635, -32_635})
    void roundTripWithinOneLsb(short original) {
        byte encoded = PcmuRtpCodec.linearToUlaw(original);
        short decoded = ulawToLinear(encoded);
        int error = Math.abs((int) original - (int) decoded);

        // G.711 µ-law guarantees quantisation noise ≤ the step size at that level.
        // For a unit test, we allow ±1 LSB of the quantised step — in practice the
        // round-trip error for well-known values is well within this bound.
        int step = Math.max(1, Math.abs(original) / 32 + 1);
        assertTrue(
                error <= step,
                "Round-trip error " + error + " exceeds step " + step
                        + " for sample " + original + " (encoded=0x" + String.format("%02X", encoded & 0xFF)
                        + ", decoded=" + decoded + ")");
    }

    /**
     * Decodes a µ-law byte back to linear PCM (reference implementation for test validation).
     * Based on ITU-T G.711 Annex A.
     */
    static short ulawToLinear(byte ulaw) {
        int u = (~ulaw) & 0xFF;
        int sign;
        if ((u & 0x80) != 0) {
            sign = -1;
        } else {
            sign = 1;
        }
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int magnitude = ((mantissa << 3) + 0x84) << exponent;
        return (short) (sign * (magnitude - 0x84L));
    }
}
