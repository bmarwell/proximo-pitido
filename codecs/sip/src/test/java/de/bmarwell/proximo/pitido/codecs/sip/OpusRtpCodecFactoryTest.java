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

import org.junit.jupiter.api.Test;

public class OpusRtpCodecFactoryTest {

    private final OpusRtpCodecFactory codecFactory = new OpusRtpCodecFactory();

    @Test
    void preferenceIs30() {
        assertEquals(30, codecFactory.preference());
    }

    @Test
    void preferenceIsHigherThanG722() {
        // given
        var g722 = new G722RtpCodecFactory();

        // when / then: Opus (30) must beat G.722 (50) — lower number = higher priority
        assertEquals(
                true,
                codecFactory.preference() < g722.preference(),
                "Opus preference must be higher priority (lower number) than G.722");
    }
}
