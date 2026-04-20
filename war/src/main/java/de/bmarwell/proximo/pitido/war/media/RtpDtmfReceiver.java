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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.function.IntConsumer;

/**
 * Receives RFC 2833 / RFC 4733 telephone-event RTP packets from the remote caller and fires a
 * callback with the DTMF event code when an end-of-event packet is detected.
 *
 * <p>Runs as a background task (Runnable) in a managed executor alongside the audio send path,
 * sharing the same {@link DatagramSocket}.
 * Java's {@link DatagramSocket} is safe for concurrent send (audio sender) and receive (this class)
 * from different threads.
 * Exits cleanly when the socket is closed (call ended) or the thread is interrupted.
 *
 * <p>RFC 4733 §2.3.1 telephone-event payload format (4 bytes after the 12-byte RTP header):
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     event     |E R| volume    |          duration             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 * Event codes: 0–9 = digits 0–9, 10 = *, 11 = #, 12–15 = A–D.
 * The E (end-of-event) bit is set in the final packet(s) of a key press.
 * The callback fires once per key press, on the first end-of-event packet received.
 */
public class RtpDtmfReceiver implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(RtpDtmfReceiver.class.getName());

    /** Poll interval; short enough not to delay digit detection, long enough not to spin-loop. */
    private static final int SOCKET_TIMEOUT_MS = 50;

    private static final int RTP_HEADER_SIZE = 12;
    private static final int TELEPHONE_EVENT_PAYLOAD_MIN_SIZE = 4;
    private static final int MIN_PACKET_SIZE = RTP_HEADER_SIZE + TELEPHONE_EVENT_PAYLOAD_MIN_SIZE;

    private final DatagramSocket socket;
    private final int telephoneEventPayloadType;
    private final IntConsumer onDigit;

    /** Last event code for which the callback was fired; -1 = no event in progress. */
    private int lastFiredEventCode = -1;

    /**
     * @param socket                    the shared RTP socket, also used by the audio sender
     * @param telephoneEventPayloadType the negotiated dynamic payload type for telephone-event
     * @param onDigit                   called with the RFC 4733 event code (0–15) on end-of-event;
     *                                  event code 0–9 maps directly to digit 0–9
     */
    public RtpDtmfReceiver(DatagramSocket socket, int telephoneEventPayloadType, IntConsumer onDigit) {
        this.socket = socket;
        this.telephoneEventPayloadType = telephoneEventPayloadType;
        this.onDigit = onDigit;
    }

    @Override
    public void run() {
        try {
            this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (IOException ioException) {
            LOGGER.log(System.Logger.Level.WARNING, "DTMF receiver: could not set socket timeout", ioException);
            return;
        }

        byte[] buf = new byte[256];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (!this.socket.isClosed() && !Thread.currentThread().isInterrupted()) {
            try {
                this.socket.receive(packet);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (IOException ioException) {
                if (!this.socket.isClosed()) {
                    LOGGER.log(System.Logger.Level.DEBUG, "DTMF receiver: socket error", ioException);
                }

                return;
            }

            processPacket(packet.getData(), packet.getLength());
        }
    }

    /**
     * Parses one UDP datagram as an RTP packet.
     * Fires {@link #onDigit} if it is a telephone-event end-of-event packet.
     */
    void processPacket(byte[] data, int length) {
        if (length < MIN_PACKET_SIZE) {
            return;
        }

        int payloadType = data[1] & 0x7F;

        if (payloadType != this.telephoneEventPayloadType) {
            return;
        }

        int eventCode = data[RTP_HEADER_SIZE] & 0xFF;
        boolean endOfEvent = (data[RTP_HEADER_SIZE + 1] & 0x80) != 0;

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "RFC 2833 telephone-event: code=[{0}], endOfEvent=[{1}]",
                eventCode,
                endOfEvent);

        if (!endOfEvent) {
            // Mid-event packet — a new key press has started; reset deduplication state.
            this.lastFiredEventCode = -1;
            return;
        }

        if (eventCode == this.lastFiredEventCode) {
            // RFC 4733 requires the sender to repeat the end-of-event packet at least three times.
            // Only the first one triggers the callback.
            return;
        }

        this.lastFiredEventCode = eventCode;
        this.onDigit.accept(eventCode);
    }
}
