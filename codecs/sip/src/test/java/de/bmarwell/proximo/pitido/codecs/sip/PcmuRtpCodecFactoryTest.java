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

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PcmuRtpCodecFactoryTest {

    private final PcmuRtpCodecFactory codecFactory = new PcmuRtpCodecFactory();

    @Test
    void isAlwaysAvailable() {
        assertTrue(codecFactory.isAvailable());
    }

    @Test
    void preferenceIsLowerThanPcma() {
        var pcma = new PcmaRtpCodecFactory();

        assertTrue(
                codecFactory.preference() > pcma.preference(),
                "PCMU preference must be lower priority (higher number) than PCMA");
    }
}
