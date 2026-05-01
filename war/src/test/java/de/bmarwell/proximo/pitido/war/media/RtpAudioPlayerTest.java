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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.bmarwell.proximo.pitido.codecs.input.PcmDecoderFactory;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecMetadata;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RtpAudioPlayerTest {

    @Mock
    DatagramSocket socket;

    @Mock
    RtpCodec codec;

    @Mock
    RtpCodecMetadata metadata;

    @Mock
    PcmDecoderFactory pcmDecoderFactory;

    @Mock
    CallMedia callMedia;

    @Mock
    javax.enterprise.concurrent.ManagedExecutorService encoderService;

    @Mock
    javax.enterprise.concurrent.ManagedExecutorService senderService;

    private RtpAudioPlayer player;

    @AfterEach
    void tearDown() throws Exception {
        // Signal the sender loop to exit, then wait for it to finish.
        lenient().when(this.socket.isClosed()).thenReturn(true);

        try {
            this.player.senderFuture.get(200, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.CancellationException ignored) {
            // Best-effort cleanup.
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        when(this.callMedia.localSocket()).thenReturn(this.socket);
        when(this.callMedia.remoteRtp()).thenReturn(new InetSocketAddress("127.0.0.1", 5004));
        lenient().when(this.callMedia.isHeld()).thenReturn(false);
        when(this.codec.metadata()).thenReturn(this.metadata);
        when(this.metadata.payloadType()).thenReturn(8); // PCMA
        when(this.metadata.rtpTimestampIncrement()).thenReturn(160); // 20ms at 8kHz
        when(this.metadata.samplesPerFrame()).thenReturn(160);
        when(this.codec.encode(any())).thenReturn(new byte[20]);

        // Encoder tasks run synchronously on the test thread.
        lenient().when(this.encoderService.submit(any(Runnable.class))).thenAnswer(invocation -> {
            var runnable = (Runnable) invocation.getArgument(0);
            runnable.run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        // Sender task runs asynchronously so the sender loop does not block setUp().
        when(this.senderService.submit(any(Runnable.class))).thenAnswer(invocation -> {
            var runnable = (Runnable) invocation.getArgument(0);
            return java.util.concurrent.CompletableFuture.runAsync(runnable);
        });

        this.player = new RtpAudioPlayer(
                this.callMedia, this.codec, this.pcmDecoderFactory, this.encoderService, this.senderService);
    }

    // ── Marker bit tests ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("First packet of call has marker bit M=1")
    void playSilence_firstPacket_hasMarkerBit() throws InterruptedException, IOException {
        // given: fresh RtpAudioPlayer (firstPacketOfTalkspurt = true)
        // when: send first silence packet
        this.player.playSilence(Duration.ofMillis(20));

        // then: first packet has M=1
        ArgumentCaptor<DatagramPacket> captor = ArgumentCaptor.forClass(DatagramPacket.class);
        verify(this.socket).send(captor.capture());

        byte[] firstPacket = captor.getValue().getData();
        byte markerByte = firstPacket[1];
        int markerBit = (markerByte & 0x80) >> 7;

        assertEquals(1, markerBit, "First packet should have marker bit M=1");
    }

    @Test
    @DisplayName("Subsequent packets in same talkspurt have marker bit M=0")
    void playSilence_subsequentPackets_noMarkerBit() throws InterruptedException, IOException {
        // given: fresh RtpAudioPlayer
        // when: send three silence packets
        this.player.playSilence(Duration.ofMillis(60));

        // then: first packet has M=1, second and third have M=0
        ArgumentCaptor<DatagramPacket> captor = ArgumentCaptor.forClass(DatagramPacket.class);
        verify(this.socket, times(3)).send(captor.capture());

        java.util.List<DatagramPacket> packets = captor.getAllValues();

        // First packet: M=1
        int firstMarkerBit = (packets.get(0).getData()[1] & 0x80) >> 7;
        assertEquals(1, firstMarkerBit, "First packet should have M=1");

        // Second packet: M=0
        int secondMarkerBit = (packets.get(1).getData()[1] & 0x80) >> 7;
        assertEquals(0, secondMarkerBit, "Second packet should have M=0");

        // Third packet: M=0
        int thirdMarkerBit = (packets.get(2).getData()[1] & 0x80) >> 7;
        assertEquals(0, thirdMarkerBit, "Third packet should have M=0");
    }

    @Test
    @DisplayName("Marker bit is correctly positioned in byte 1 without overwriting payload type")
    void sendRtpPacket_markerBitPreservesPayloadType() throws InterruptedException, IOException {
        // given: codec with payload type 120 (Opus)
        when(this.metadata.payloadType()).thenReturn(120);

        // when: send first packet
        this.player.playSilence(Duration.ofMillis(20));

        // then: byte 1 = M(1) + PT(120) = 0x80 | 120 = 0xF8
        ArgumentCaptor<DatagramPacket> captor = ArgumentCaptor.forClass(DatagramPacket.class);
        verify(this.socket).send(captor.capture());

        byte[] packet = captor.getValue().getData();
        byte byte1 = packet[1];

        int markerBit = (byte1 & 0x80) >> 7;
        int payloadType = byte1 & 0x7F;

        assertEquals(1, markerBit, "Marker bit should be set");
        assertEquals(120, payloadType, "Payload type should be 120 (Opus), not modified by marker bit");
    }

    @Test
    @DisplayName("Silence encoder does not advance packet count while held; sends correct number after hold releases")
    void playSilence_whenHeldThenReleased_sendsCorrectPacketCount() throws InterruptedException, IOException {
        // given: held on first encoder check, not held on second+
        when(this.callMedia.isHeld()).thenReturn(true, false);

        // when: request exactly 1 silence packet (20ms)
        this.player.playSilence(Duration.ofMillis(20));

        // then: exactly 1 packet sent (hold did not consume the counter)
        verify(this.socket, times(1)).send(any());
    }

    @Test
    @DisplayName("playSilence throws InterruptedException when caller thread is interrupted mid-wait")
    void playSilence_whenInterrupted_throwsInterruptedException() throws IOException {
        // given: encoder runs asynchronously so the caller thread blocks in done.get() —
        // with a sync encoder the future completes before awaitClipEnd() is reached,
        // so done.get() would return immediately without ever seeing the interruption.
        when(this.encoderService.submit(any(Runnable.class))).thenAnswer(invocation -> {
            var runnable = (Runnable) invocation.getArgument(0);
            return java.util.concurrent.CompletableFuture.runAsync(runnable);
        });

        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // Interrupter was itself interrupted — stop silently.
            }
            testThread.interrupt();
        });
        interrupter.start();

        try {
            // when/then: caller blocks on a 10-second silence and is interrupted
            org.junit.jupiter.api.Assertions.assertThrows(
                    InterruptedException.class, () -> this.player.playSilence(Duration.ofSeconds(10)));
        } finally {
            interrupter.interrupt();
            Thread.interrupted(); // clear interrupt flag for teardown
        }
    }
}
