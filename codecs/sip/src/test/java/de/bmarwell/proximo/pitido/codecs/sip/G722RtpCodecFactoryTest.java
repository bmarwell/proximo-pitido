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

import java.io.IOException;
import org.junit.jupiter.api.Test;

public class G722RtpCodecFactoryTest {

    private final G722RtpCodecFactory codec = new G722RtpCodecFactory();

    @Test
    void preferenceIs50() {
        assertEquals(50, codec.preference());
    }

    @Test
    void preferenceIsLowerThanPcma() {
        var pcma = new PcmaRtpCodecFactory();
        assertEquals(
                true,
                codec.preference() < pcma.preference(),
                "G.722 preference must be higher priority (lower number) than PCMA");
    }

    @Test
    void isAvailableAfterProbeWhenLibraryPresent() {
        G722RtpCodecFactory factory = new G722RtpCodecFactory();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        assertTrue(factory.isAvailable());
    }

    /**
     * Verifies that encoding 320 silence samples produces exactly 160 output bytes.
     *
     * <p>G.722 encodes 2 PCM samples into 1 byte (4 bits per sub-band).
     * 320 input samples must always yield exactly 160 output bytes regardless of content.
     */
    @Test
    void silenceFrameEncodesTo160Bytes() throws IOException {
        G722RtpCodecFactory factory = new G722RtpCodecFactory();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libspandsp not available on this host — skipping");

        G722RtpCodec encoder = factory.forCall("");
        byte[] encoded = encoder.encode(new short[320]);

        assertEquals(160, encoded.length);
    }
}
