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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.bmarwell.proximo.pitido.codecs.sip.AmrWbMetadata;
import de.bmarwell.proximo.pitido.codecs.sip.G722Metadata;
import de.bmarwell.proximo.pitido.codecs.sip.PcmaMetadata;
import de.bmarwell.proximo.pitido.codecs.sip.PcmaRtpCodecFactory;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;
import java.util.Arrays;
import java.util.Map;
import javax.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
     * DTMF is offered at both 8000 Hz (PT 100) and 16000 Hz (PT 105).
     * Since AMR-WB is 16000 Hz, PT 105 should be selected per RFC 4733.
     */
    private static final String SDP_OFFER_TELEKOM_VOLTE = """
            v=0\r
            o=ccs-0-615-1 061211101626733 1878868699 IN IP4 203.0.113.1\r
            s=-\r
            c=IN IP4 203.0.113.1\r
            t=0 0\r
            a=sendrecv\r
            m=audio 51652 RTP/AVP 109 104 110 9 102 108 8 0 100 105\r
            a=sendrecv\r
            a=maxptime:40\r
            a=ptime:20\r
            a=rtpmap:109 EVS/16000\r
            a=fmtp:109 br=5.9-24.4;bw=nb-wb;cmr=1;ch-aw-recv=-1;max-red=0\r
            a=rtpmap:104 AMR-WB/16000\r
            a=fmtp:104 mode-set=0,1,2;mode-change-capability=2;max-red=0\r
            a=rtpmap:110 AMR-WB/16000\r
            a=fmtp:110 octet-align=1;mode-set=0,1,2;mode-change-capability=2;max-red=0\r
            a=rtpmap:9 G722/8000\r
            a=rtpmap:102 AMR/8000\r
            a=fmtp:102 mode-change-capability=2;max-red=0\r
            a=rtpmap:108 AMR/8000\r
            a=fmtp:108 octet-align=1;mode-change-capability=2;max-red=0\r
            a=rtpmap:8 PCMA/8000\r
            a=rtpmap:0 PCMU/8000\r
            a=rtpmap:100 telephone-event/8000\r
            a=fmtp:100 0-15\r
            a=rtpmap:105 telephone-event/16000\r
            a=fmtp:105 0-15\r
            """;

    final SdpNegotiator sdpNegotiator = new SdpNegotiator();

    @BeforeEach
    void setUp() {
        final RtpCodecFactory g722Stub = getG722Stub();
        final RtpCodecFactory amrWbStub = getAmrWbStub();

        // set fallback
        final Instance<PcmaRtpCodecFactory> pcmaFallback = mock(Instance.class);
        when(pcmaFallback.get()).thenReturn(new PcmaRtpCodecFactory());
        sdpNegotiator.setFallback(pcmaFallback);

        // set main codecs
        setSdpCodecs(g722Stub, amrWbStub);
    }

    @Test
    @DisplayName("Parse telephone-event from SDP offer")
    void parseTelephoneEventPayloadType_withTelephoneEvent_returnsPayloadType() {
        // given
        String sdp = SDP_OFFER_WITH_TELEPHONE_EVENT;

        // when
        int payloadType = SdpNegotiator.parseTelephoneEventPayloadType(sdp, 8000);

        // then
        assertEquals(101, payloadType);
    }

    @Test
    @DisplayName("Return -1 when telephone-event not in offer")
    void parseTelephoneEventPayloadType_withoutTelephoneEvent_returnsMinusOne() {
        // given
        String sdp = SDP_OFFER_WITHOUT_TELEPHONE_EVENT;

        // when
        int payloadType = SdpNegotiator.parseTelephoneEventPayloadType(sdp, 8000);

        // then
        assertEquals(-1, payloadType);
    }

    @Test
    @DisplayName("SDP answer includes telephone-event parameters when offered")
    void buildSdpAnswer_withTelephoneEvent_includesTelephoneEventLineAndSendonly() {
        // given
        String localIp = "192.168.1.1";
        int localPort = 20000;
        int telephoneEventPt = 101;

        // when
        String sdpAnswer = invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt);

        // then
        assertTrue(
                sdpAnswer.contains("a=rtpmap:101 telephone-event/8000"),
                "SDP answer must include telephone-event rtpmap");
        assertTrue(sdpAnswer.contains("a=fmtp:101 0-15"), "SDP answer must include telephone-event fmtp");
        assertTrue(sdpAnswer.contains("a=sendrecv"), "SDP answer must use sendrecv to enable DTMF reception");
    }

    @Test
    @DisplayName("SDP answer is sendrecv even without telephone-event")
    void buildSdpAnswer_withoutTelephoneEvent_isSendrecv() {
        // given
        String localIp = "192.168.1.1";
        int localPort = 20000;
        int telephoneEventPt = -1;

        // when
        String sdpAnswer = invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt);

        // then
        assertTrue(sdpAnswer.contains("a=sendrecv"), "SDP answer must use sendrecv to enable DTMF reception");
    }

    @Test
    @DisplayName("Select telephone-event PT matching audio codec sample rate (Telekom real-world test)")
    void parseTelephoneEventPayloadType_withTelekomVolte_selectsPtMatchingAudioSampleRate() {
        // given
        String sdp = SDP_OFFER_TELEKOM_VOLTE;
        int amrWbSampleRate = 16000;

        // when
        int selectedPt = SdpNegotiator.parseTelephoneEventPayloadType(sdp, amrWbSampleRate);

        // then
        assertEquals(105, selectedPt, "For 16000 Hz audio codec, should select PT 105 (telephone-event/16000)");
    }

    @Test
    @DisplayName("Fall back to 8000 Hz telephone-event when matching sample rate not available")
    void parseTelephoneEventPayloadType_withTelekomVolte_fallsBackTo8kHz() {
        // given
        String sdp = SDP_OFFER_TELEKOM_VOLTE;
        int unknownSampleRate = 32000;

        // when
        int selectedPt = SdpNegotiator.parseTelephoneEventPayloadType(sdp, unknownSampleRate);

        // then
        assertEquals(100, selectedPt, "When audio sample rate not in offer, should fall back to 8000 Hz (PT 100)");
    }

    /**
     * Calls the package-private {@code buildSdpAnswer} using PCMA (PT 8) as the codec.
     */
    private static String invokeBuildSdpAnswer(String localIp, int localPort, int telephoneEventPt) {
        return invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt, 8000);
    }

    /**
     * Calls the package-private {@code buildSdpAnswer} with specified audio codec sample rate.
     */
    private static String invokeBuildSdpAnswer(
            String localIp, int localPort, int telephoneEventPt, int audioCodecSampleRate) {
        try {
            var codecFactory = new PcmaRtpCodecFactory();
            var negotiatedCodecFactory = new NegotiatedRtpCodecFactory(codecFactory, 8, "");
            var method = SdpNegotiator.class.getDeclaredMethod(
                    "buildSdpAnswer", String.class, int.class, NegotiatedRtpCodecFactory.class, int.class, int.class);
            method.setAccessible(true);
            return (String) method.invoke(
                    null, localIp, localPort, negotiatedCodecFactory, telephoneEventPt, audioCodecSampleRate);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            throw new AssertionError("Could not invoke buildSdpAnswer", reflectiveOperationException);
        }
    }

    @Test
    @DisplayName("SDP answer with 16000 Hz audio codec uses matching telephone-event")
    void buildSdpAnswer_with16khzAudio_includsTelephoneEventAt16khz() {
        // given
        String localIp = "192.168.1.1";
        int localPort = 20000;
        int telephoneEventPt = 105;
        int audioCodecSampleRate = 16000;

        // when
        String sdpAnswer = invokeBuildSdpAnswer(localIp, localPort, telephoneEventPt, audioCodecSampleRate);

        // then
        assertTrue(
                sdpAnswer.contains("a=rtpmap:105 telephone-event/16000"),
                "SDP answer must include telephone-event/16000 at PT 105 when audio codec is 16000 Hz");
        assertTrue(sdpAnswer.contains("a=fmtp:105 0-15"), "SDP answer must include telephone-event fmtp at PT 105");
        assertTrue(sdpAnswer.contains("a=sendrecv"), "SDP answer must use sendrecv to enable DTMF reception");
    }

    @Test
    @DisplayName("Parse rtpmap lines for AMR-WB at both octet-aligned and bandwidth-efficient payload types")
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
    @DisplayName("Select octet-aligned AMR-WB at negotiated payload type")
    void selectCodec_withTelekomVolteSdp_selectsOctetAlignedAmrWbAtNegotiatedPayloadType() {
        // given
        String sdp = SDP_OFFER_TELEKOM_VOLTE;

        RtpCodec amrWbCodecStub = mock(RtpCodec.class);
        when(amrWbCodecStub.metadata()).thenReturn(new AmrWbMetadata());

        // when
        NegotiatedRtpCodecFactory selected = sdpNegotiator.selectCodec(sdp);

        // then
        assertEquals("AMR-WB", selected.metadata().sdpName(), "Selected codec must be AMR-WB");
        // NegotiatedRtpCodecFactory wraps the factory and overrides payloadType() to return negotiated PT
        assertEquals(
                110,
                selected.metadata().payloadType(),
                "Negotiated PT must be 110 (octet-aligned), not 104 (BW-efficient) or 98 (default)");
    }

    @Test
    @DisplayName("Codec selection returns factory wrapper for deferred codec creation")
    void selectCodec_returnsFactoryWrapper_deferresCodecCreation() {
        // given
        RtpCodecFactory descriptorStub = mock(RtpCodecFactory.class);
        when(descriptorStub.isAvailable()).thenReturn(true);
        when(descriptorStub.preference()).thenReturn(100);
        when(descriptorStub.metadata()).thenReturn(new PcmaMetadata());
        when(descriptorStub.matchesFmtp(anyString())).thenReturn(true);
        when(descriptorStub.fmtpAnswer(anyString())).thenReturn("fmtp-answer");

        setSdpCodecs(descriptorStub);

        // when
        NegotiatedRtpCodecFactory selected = sdpNegotiator.selectCodec(SDP_OFFER_WITH_TELEPHONE_EVENT);

        // then
        assertEquals(8, selected.metadata().payloadType());
        assertNotNull(selected.delegate(), "Selected codec must be a factory wrapper");
        // fmtpAnswer should return answer using negotiated fmtp (deferred to later call on executor thread)
        assertEquals("fmtp-answer", selected.fmtpAnswer());
    }

    private void setSdpCodecs(RtpCodecFactory... codecFactory) {
        final Instance<RtpCodecFactory> availableCodecFactories = mock(Instance.class);
        when(availableCodecFactories.stream()).thenReturn(Arrays.stream(codecFactory));
        sdpNegotiator.setAvailableCodecFactories(availableCodecFactories);
    }

    private static RtpCodecFactory getAmrWbStub() {
        final RtpCodecFactory amrWbStub = mock(RtpCodecFactory.class);

        when(amrWbStub.isAvailable()).thenReturn(true);
        when(amrWbStub.preference()).thenReturn(40);
        when(amrWbStub.metadata()).thenReturn(new AmrWbMetadata());
        when(amrWbStub.matchesFmtp(anyString()))
                .thenAnswer(invocation -> ((String) invocation.getArgument(0)).contains("octet-align=1"));
        when(amrWbStub.forCall(anyString())).thenReturn(mock(RtpCodec.class));

        return amrWbStub;
    }

    private static RtpCodecFactory getG722Stub() {
        final RtpCodecFactory g722Stub = mock(RtpCodecFactory.class);

        when(g722Stub.metadata()).thenReturn(new G722Metadata());
        when(g722Stub.isAvailable()).thenReturn(true);
        when(g722Stub.preference()).thenReturn(50);
        when(g722Stub.matchesFmtp(anyString())).thenReturn(true);

        return g722Stub;
    }
}
