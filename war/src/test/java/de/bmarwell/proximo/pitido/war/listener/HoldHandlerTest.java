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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HoldHandlerTest {

    @Mock
    CallSessionManager callSessionManager;

    @Mock
    SipServletRequest req;

    @Mock
    SipSession sipSession;

    @Mock
    SipServletResponse response;

    @InjectMocks
    HoldHandler holdHandler;

    // ── handle() tests ────────────────────────────────────────────────────────

    @Test
    void handle_holdOffer_pausesMediaAndResponds200WithRecvonly() throws IOException {
        // given
        String sdpOffer = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=sendonly\r\n";
        String sdpAnswer = "v=0\r\nm=audio 5000 RTP/AVP 8\r\na=sendrecv\r\n";
        AtomicBoolean held = new AtomicBoolean(false);
        RtpCodec codec = mock(RtpCodec.class);
        DatagramSocket socket = mock(DatagramSocket.class);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 10000);
        CallMedia media = new CallMedia(socket, addr, sdpAnswer, codec, -1, held);
        CallState callState = new CallState(
                "test-call-id", sipSession, null, null, new LinkedHashMap<>(), media, Instant.now(), "caller");
        when(sipSession.getId()).thenReturn("sess-1");
        when(req.getSession()).thenReturn(sipSession);
        when(req.getContent()).thenReturn(sdpOffer.getBytes(StandardCharsets.UTF_8));
        when(req.createResponse(SipServletResponse.SC_OK)).thenReturn(response);
        when(callSessionManager.get("sess-1")).thenReturn(callState);

        // when
        holdHandler.handle(req);

        // then
        assertTrue(held.get(), "media must be held");
        ArgumentCaptor<byte[]> sdpCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(response).setContent(sdpCaptor.capture(), org.mockito.ArgumentMatchers.anyString());
        String responseSdp = new String(sdpCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(responseSdp.contains("a=recvonly"), "response SDP must contain a=recvonly");
        verify(response).send();
    }

    @Test
    void handle_inactiveOffer_pausesMediaAndResponds200WithInactive() throws IOException {
        // given
        String sdpOffer = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=inactive\r\n";
        String sdpAnswer = "v=0\r\nm=audio 5000 RTP/AVP 8\r\na=sendrecv\r\n";
        AtomicBoolean held = new AtomicBoolean(false);
        RtpCodec codec = mock(RtpCodec.class);
        DatagramSocket socket = mock(DatagramSocket.class);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 10000);
        CallMedia media = new CallMedia(socket, addr, sdpAnswer, codec, -1, held);
        CallState callState = new CallState(
                "test-call-id", sipSession, null, null, new LinkedHashMap<>(), media, Instant.now(), "caller");
        when(sipSession.getId()).thenReturn("sess-2");
        when(req.getSession()).thenReturn(sipSession);
        when(req.getContent()).thenReturn(sdpOffer.getBytes(StandardCharsets.UTF_8));
        when(req.createResponse(SipServletResponse.SC_OK)).thenReturn(response);
        when(callSessionManager.get("sess-2")).thenReturn(callState);

        // when
        holdHandler.handle(req);

        // then
        assertTrue(held.get(), "media must be held");
        ArgumentCaptor<byte[]> sdpCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(response).setContent(sdpCaptor.capture(), org.mockito.ArgumentMatchers.anyString());
        String responseSdp = new String(sdpCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(responseSdp.contains("a=inactive"), "response SDP must contain a=inactive");
        verify(response).send();
    }

    @Test
    void handle_resumeOffer_resumesMediaAndResponds200WithSendrecv() throws IOException {
        // given
        String sdpOffer = "v=0\r\nm=audio 10000 RTP/AVP 8\r\na=sendrecv\r\n";
        String sdpAnswer = "v=0\r\nm=audio 5000 RTP/AVP 8\r\na=recvonly\r\n";
        AtomicBoolean held = new AtomicBoolean(true);
        RtpCodec codec = mock(RtpCodec.class);
        DatagramSocket socket = mock(DatagramSocket.class);
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 10000);
        CallMedia media = new CallMedia(socket, addr, sdpAnswer, codec, -1, held);
        CallState callState = new CallState(
                "test-call-id", sipSession, null, null, new LinkedHashMap<>(), media, Instant.now(), "caller");
        when(sipSession.getId()).thenReturn("sess-3");
        when(req.getSession()).thenReturn(sipSession);
        when(req.getContent()).thenReturn(sdpOffer.getBytes(StandardCharsets.UTF_8));
        when(req.createResponse(SipServletResponse.SC_OK)).thenReturn(response);
        when(callSessionManager.get("sess-3")).thenReturn(callState);

        // when
        holdHandler.handle(req);

        // then
        assertFalse(held.get(), "media must be un-held");
        ArgumentCaptor<byte[]> sdpCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(response).setContent(sdpCaptor.capture(), org.mockito.ArgumentMatchers.anyString());
        String responseSdp = new String(sdpCaptor.getValue(), StandardCharsets.UTF_8);
        assertTrue(responseSdp.contains("a=sendrecv"), "response SDP must contain a=sendrecv");
        verify(response).send();
    }

    @Test
    void handle_unknownSession_responds481() throws IOException {
        // given
        when(sipSession.getId()).thenReturn("unknown-sess");
        when(req.getSession()).thenReturn(sipSession);
        when(callSessionManager.get("unknown-sess")).thenReturn(null);
        when(req.createResponse(SipServletResponse.SC_CALL_LEG_DONE)).thenReturn(response);

        // when
        holdHandler.handle(req);

        // then
        verify(response).send();
    }

    // ── isHoldOffer() static helper tests ─────────────────────────────────────

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

    // ── replaceDirection() static helper tests ────────────────────────────────

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
