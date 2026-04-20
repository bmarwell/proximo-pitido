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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RtpDtmfReceiverTest {

    private static final int TELEPHONE_EVENT_PT = 101;

    /** Builds a minimal RTP + telephone-event payload for one key press. */
    private static byte[] buildRtpTelephoneEvent(int payloadType, int eventCode, boolean endOfEvent) {
        byte[] packet = new byte[16]; // 12 RTP header + 4 telephone-event payload
        // RTP version=2, padding=0, extension=0, CC=0
        packet[0] = (byte) 0x80;
        // marker=0, payload type
        packet[1] = (byte) (payloadType & 0x7F);
        // sequence number (2 bytes) — arbitrary
        packet[2] = 0x00;
        packet[3] = 0x01;
        // timestamp (4 bytes) — arbitrary
        packet[4] = 0x00;
        packet[5] = 0x00;
        packet[6] = 0x00;
        packet[7] = 0x00;
        // SSRC (4 bytes) — arbitrary
        packet[8] = 0x00;
        packet[9] = 0x00;
        packet[10] = 0x00;
        packet[11] = 0x01;
        // telephone-event payload
        packet[12] = (byte) eventCode;
        packet[13] = (byte) (endOfEvent ? 0x80 : 0x00); // E-bit
        packet[14] = 0x00; // duration high
        packet[15] = 0x50; // duration low (arbitrary)
        return packet;
    }

    @Test
    void processPacket_endOfEvent_firesCallback() {
        // given
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] packet = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 5, true);

        // when
        receiver.processPacket(packet, packet.length);

        // then
        assertEquals(List.of(5), received, "Callback must fire with event code 5 on end-of-event");
    }

    @Test
    void processPacket_midEvent_doesNotFireCallback() {
        // given
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] packet = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 5, false);

        // when
        receiver.processPacket(packet, packet.length);

        // then
        assertEquals(List.of(), received, "Callback must not fire for mid-event (E-bit not set) packet");
    }

    @Test
    void processPacket_endOfEventSentThreeTimes_firesCallbackOnce() {
        // given — RFC 4733 requires sender to repeat end-of-event at least 3 times
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] packet = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 5, true);

        // when
        receiver.processPacket(packet, packet.length);
        receiver.processPacket(packet, packet.length);
        receiver.processPacket(packet, packet.length);

        // then
        assertEquals(List.of(5), received, "Callback must fire only once for repeated end-of-event packets");
    }

    @Test
    void processPacket_twoDifferentDigits_firesBothCallbacks() {
        // given — press 5, then press 2
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] midEvent5 = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 5, false);
        byte[] endEvent5 = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 5, true);
        byte[] midEvent2 = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 2, false);
        byte[] endEvent2 = buildRtpTelephoneEvent(TELEPHONE_EVENT_PT, 2, true);

        // when
        receiver.processPacket(midEvent5, midEvent5.length);
        receiver.processPacket(endEvent5, endEvent5.length);
        receiver.processPacket(midEvent2, midEvent2.length);
        receiver.processPacket(endEvent2, endEvent2.length);

        // then
        assertEquals(List.of(5, 2), received, "Callback must fire once per distinct digit");
    }

    @Test
    void processPacket_wrongPayloadType_ignored() {
        // given
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] packet = buildRtpTelephoneEvent(8, 5, true); // PT 8 = PCMA, not telephone-event

        // when
        receiver.processPacket(packet, packet.length);

        // then
        assertEquals(List.of(), received, "Callback must not fire for packets with a different payload type");
    }

    @Test
    void processPacket_tooShort_ignored() {
        // given
        List<Integer> received = new ArrayList<>();
        RtpDtmfReceiver receiver = new RtpDtmfReceiver(null, TELEPHONE_EVENT_PT, received::add);
        byte[] packet = new byte[10]; // less than 12 + 4 = 16 bytes minimum

        // when
        receiver.processPacket(packet, packet.length);

        // then
        assertEquals(List.of(), received, "Callback must not fire for truncated packets");
    }
}
