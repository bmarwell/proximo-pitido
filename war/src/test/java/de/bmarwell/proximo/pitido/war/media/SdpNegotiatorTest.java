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
package de.bmarwell.proximo.pitido.war.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SdpNegotiatorTest {

    // Typical Deutsche Telekom SDP offer with telephone-event at PT 101
    private static final String SDP_OFFER_WITH_TELEPHONE_EVENT = """
            v=0\r
            o=- 123 456 IN IP4 1.2.3.4\r
            s=-\r
            c=IN IP4 1.2.3.4\r
            t=0 0\r
            m=audio 49152 RTP/AVP 8 101\r
            a=rtpmap:8 PCMA/8000\r
            a=rtpmap:101 telephone-event/8000\r
            a=fmtp:101 0-15\r
            a=sendrecv\r
            """;

    private static final String SDP_OFFER_WITHOUT_TELEPHONE_EVENT = """
            v=0\r
            o=- 123 456 IN IP4 1.2.3.4\r
            s=-\r
            c=IN IP4 1.2.3.4\r
            t=0 0\r
            m=audio 49152 RTP/AVP 8\r
            a=rtpmap:8 PCMA/8000\r
            a=sendrecv\r
            """;

    @Test
    void parseTelephoneEventPayloadType_withTelephoneEvent_returnsPayloadType() {
        // given
        String sdp = SDP_OFFER_WITH_TELEPHONE_EVENT;

        // when
        int payloadType = SdpNegotiator.parseTelephoneEventPayloadType(sdp);

        // then
        assertEquals(101, payloadType);
    }

    @Test
    void parseTelephoneEventPayloadType_withoutTelephoneEvent_returnsMinusOne() {
        // given
        String sdp = SDP_OFFER_WITHOUT_TELEPHONE_EVENT;

        // when
        int payloadType = SdpNegotiator.parseTelephoneEventPayloadType(sdp);

        // then
        assertEquals(-1, payloadType);
    }

    @Test
    void buildSdpAnswer_withTelephoneEvent_includesTelephoneEventLineAndSendrecv() {
        // given — simulate the result of negotiate() for a simple PCMA + telephone-event offer
        // The static method is package-private; test via the observable SDP string built inline
        // using the same format as buildSdpAnswer (called reflectively through negotiate is heavier;
        // testing the known output format is sufficient).
        String localIp = "192.168.1.1";
        int localPort = 20000;
        int telephoneEventPt = 101;

        // when — call the package-private helper directly
        // (same package, so direct access is allowed)
        String sdpAnswer = invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt);

        // then
        assertTrue(
                sdpAnswer.contains("a=rtpmap:101 telephone-event/8000"),
                "SDP answer must include telephone-event rtpmap");
        assertTrue(sdpAnswer.contains("a=fmtp:101 0-15"), "SDP answer must include telephone-event fmtp");
        assertTrue(sdpAnswer.contains("a=sendrecv"), "SDP answer must use sendrecv when telephone-event is present");
    }

    @Test
    void buildSdpAnswer_withoutTelephoneEvent_isSendrecv() {
        // given
        String localIp = "192.168.1.1";
        int localPort = 20000;
        int telephoneEventPt = -1;

        // when
        String sdpAnswer = invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt);

        // then
        assertTrue(sdpAnswer.contains("a=sendrecv"), "SDP answer must use sendrecv even without telephone-event");
    }

    /**
     * Calls the package-private {@code buildSdpAnswer} using PCMA (PT 8) as the codec.
     */
    private static String invokeBuildSdpAnswer(String localIp, int localPort, int telephoneEventPt) {
        try {
            var codec = de.bmarwell.proximo.pitido.codecs.sip.PcmaRtpCodec.INSTANCE;
            var method = SdpNegotiator.class.getDeclaredMethod(
                    "buildSdpAnswer",
                    String.class,
                    int.class,
                    de.bmarwell.proximo.pitido.codecs.sip.RtpCodec.class,
                    int.class);
            method.setAccessible(true);
            return (String) method.invoke(null, localIp, localPort, codec, telephoneEventPt);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            throw new AssertionError("Could not invoke buildSdpAnswer", reflectiveOperationException);
        }
    }
}
