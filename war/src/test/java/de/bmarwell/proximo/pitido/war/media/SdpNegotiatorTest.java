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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import java.util.Map;
import java.util.stream.Stream;
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

    /**
     * Real-world Deutsche Telekom VoLTE SDP offer (anonymised).
     * AMR-WB appears twice: PT 104 (bandwidth-efficient, no octet-align) and PT 110 (octet-aligned).
     * Our encoder only handles octet-aligned mode, so PT 110 must be selected.
     */
    private static final String SDP_OFFER_TELEKOM_VOLTE = """
            v=0\r
            o=- 3896854731 3896854731 IN IP4 10.20.30.40\r
            s=-\r
            c=IN IP4 10.20.30.40\r
            t=0 0\r
            m=audio 51944 RTP/AVP 109 104 110 9 102 108 8 0 100 105\r
            a=rtpmap:109 EVS/16000\r
            a=rtpmap:104 AMR-WB/16000\r
            a=fmtp:104 mode-set=0,1,2;mode-change-capability=2;max-red=0\r
            a=rtpmap:110 AMR-WB/16000\r
            a=fmtp:110 octet-align=1;mode-set=0,1,2;mode-change-capability=2;max-red=0\r
            a=rtpmap:9 G722/8000\r
            a=rtpmap:102 G722/8000\r
            a=rtpmap:108 AMR/8000\r
            a=rtpmap:8 PCMA/8000\r
            a=rtpmap:0 PCMU/8000\r
            a=rtpmap:100 telephone-event/16000\r
            a=rtpmap:105 telephone-event/8000\r
            a=fmtp:105 0-15\r
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

    @Test
    void parseRtpmap_withTelekomVolteSdp_extractsAmrWbAtBothPayloadTypes() {
        // given
        String sdp = SDP_OFFER_TELEKOM_VOLTE;

        // when
        Map<Integer, String> rtpmap = SdpNegotiator.parseRtpmap(sdp);

        // then
        assertEquals("AMR-WB/16000", rtpmap.get(104), "PT 104 must map to AMR-WB/16000");
        assertEquals("AMR-WB/16000", rtpmap.get(110), "PT 110 must map to AMR-WB/16000");
        assertEquals("G722/8000", rtpmap.get(9), "PT 9 must map to G722/8000");
    }

    @Test
    void selectCodec_withTelekomVolteSdp_selectsOctetAlignedAmrWbAtNegotiatedPayloadType() {
        // given
        String sdp = SDP_OFFER_TELEKOM_VOLTE;

        RtpCodec g722Stub = mock(RtpCodec.class);
        when(g722Stub.isAvailable()).thenReturn(true);
        when(g722Stub.preference()).thenReturn(50);
        when(g722Stub.sdpName()).thenReturn("G722");
        when(g722Stub.rtpClockRate()).thenReturn(8000);
        when(g722Stub.payloadType()).thenReturn(9);
        when(g722Stub.matchesFmtp(anyString())).thenReturn(true);
        when(g722Stub.forCall()).thenReturn(g722Stub);

        RtpCodec amrWbStub = mock(RtpCodec.class);
        when(amrWbStub.isAvailable()).thenReturn(true);
        when(amrWbStub.preference()).thenReturn(40);
        when(amrWbStub.sdpName()).thenReturn("AMR-WB");
        when(amrWbStub.rtpClockRate()).thenReturn(16000);
        when(amrWbStub.payloadType()).thenReturn(98);
        when(amrWbStub.matchesFmtp(anyString()))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).contains("octet-align=1"));
        when(amrWbStub.forCall()).thenReturn(amrWbStub);

        // when
        RtpCodec selected = SdpNegotiator.selectCodec(Stream.of(g722Stub, amrWbStub), sdp);

        // then — AMR-WB must be selected at the octet-aligned PT 110, not the BW-efficient PT 104
        assertEquals("AMR-WB", selected.sdpName(), "Selected codec must be AMR-WB");
        assertEquals(110, selected.payloadType(), "Negotiated PT must be 110 (octet-aligned), not 104 (BW-efficient)");
    }
}
