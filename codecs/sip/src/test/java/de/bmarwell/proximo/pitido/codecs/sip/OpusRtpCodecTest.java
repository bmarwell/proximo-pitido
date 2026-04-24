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

class OpusRtpCodecTest {

    private final OpusRtpCodec codec = (OpusRtpCodec) new OpusRtpCodecFactory().forCall("");

    @Test
    void fmtpParamsContainsFec() {
        assertEquals("useinbandfec=1", codec.fmtpParams());
    }

    // -------------------------------------------------------------------------
    // Tests that require libopus — skipped automatically when not installed
    // -------------------------------------------------------------------------

    @Test
    void closeOnFactoryBean_isNoOp() {
        // given: a fresh factory
        var factory = new OpusRtpCodecFactory().forCall("");

        // when / then: close() must not throw
        factory.close();
    }

    @Test
    void closeOnPerCallInstance_releasesArena() {
        // given
        OpusRtpCodecFactory factory = new OpusRtpCodecFactory();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        RtpCodec callInstance = factory.forCall("");

        // when
        callInstance.close();

        // then: encoding after close should fail (arena is closed)
        assertThrows(Exception.class, () -> callInstance.encode(new short[960]));
    }

    @Test
    void silenceFrameEncodesSuccessfully() throws Exception {
        // given
        OpusRtpCodecFactory factory = new OpusRtpCodecFactory();
        factory.probe();

        assumeTrue(factory.isAvailable(), "libopus not available on this host — skipping");

        RtpCodec encoder = factory.forCall("");
        short[] silence = new short[960];

        // when
        byte[] encoded = encoder.encode(silence);

        // then: Opus should always produce at least a minimal comfort-noise frame
        assertEquals(true, encoded.length > 0, "Encoded frame must not be empty");
    }
}
