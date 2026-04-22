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
package de.bmarwell.proximo.pitido.war.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HoldHandlerTest {

    @Test
    void isHoldOffer_sendonly_returnsTrue() {
        // given
        String sdp = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=sendonly\r\n";

        // when
        boolean result = HoldHandler.isHoldOffer(sdp);

        // then
        assertTrue(result);
    }

    @Test
    void isHoldOffer_inactive_returnsTrue() {
        // given
        String sdp = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=inactive\r\n";

        // when
        boolean result = HoldHandler.isHoldOffer(sdp);

        // then
        assertTrue(result);
    }

    @Test
    void isHoldOffer_sendrecv_returnsFalse() {
        // given
        String sdp = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=sendrecv\r\n";

        // when
        boolean result = HoldHandler.isHoldOffer(sdp);

        // then
        assertFalse(result);
    }

    @Test
    void isHoldOffer_noDirectionAttribute_returnsFalse() {
        // given
        String sdp = "v=0\r\nm=audio 10000 RTP/AVP 8\r\n";

        // when
        boolean result = HoldHandler.isHoldOffer(sdp);

        // then
        assertFalse(result);
    }

    @Test
    void replaceDirection_replacesExistingSendrecv() {
        // given
        String sdpAnswer = "v=0\r\na=sendrecv\r\n";

        // when
        String result = HoldHandler.replaceDirection(sdpAnswer, "recvonly");

        // then
        assertEquals("v=0\r\na=recvonly\r\n", result);
    }

    @Test
    void replaceDirection_replacesExistingSendonly() {
        // given
        String sdpAnswer = "v=0\r\na=sendonly\r\n";

        // when
        String result = HoldHandler.replaceDirection(sdpAnswer, "sendrecv");

        // then
        assertEquals("v=0\r\na=sendrecv\r\n", result);
    }

    @Test
    void replaceDirection_appendsWhenNoDirectionPresent() {
        // given
        String sdpAnswer = "v=0\r\nm=audio 5000 RTP/AVP 8\r\n";

        // when
        String result = HoldHandler.replaceDirection(sdpAnswer, "recvonly");

        // then
        assertEquals("v=0\r\nm=audio 5000 RTP/AVP 8\r\na=recvonly\r\n", result);
    }
}
