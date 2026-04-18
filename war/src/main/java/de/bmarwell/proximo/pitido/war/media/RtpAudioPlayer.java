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

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * Sends audio to the remote caller as RTP packets over UDP.
 *
 * <p>This initial implementation ignores the {@code resourcePath} parameter and generates
 * a 1 kHz sine-wave tone encoded in G.711 A-law (PCMA, payload type 8) instead.
 * MP3 decoding and playback of real audio files will be added once the call-acceptance
 * signalling is confirmed to work end-to-end.
 *
 * <p>RTP packet format:
 * <ul>
 *   <li>Version 2, no padding, no extension, CC = 0</li>
 *   <li>Payload type 8 (PCMA)</li>
 *   <li>20 ms packets, 160 samples at 8 000 Hz, sent at 50 packets/second</li>
 *   <li>Sequence number and timestamp increment per packet</li>
 *   <li>SSRC is chosen randomly at construction time</li>
 * </ul>
 *
 * <p>The caller is responsible for closing the {@link DatagramSocket} that backs this player;
 * obtain the socket via {@link CallMedia#localSocket()}.
 */
public class RtpAudioPlayer implements AudioPlayer {

    private static final System.Logger LOGGER = System.getLogger(RtpAudioPlayer.class.getName());

    /** RTP sample rate for PCMA (G.711 A-law). */
    private static final int SAMPLE_RATE = 8_000;

    /** Samples per 20 ms RTP packet. */
    private static final int SAMPLES_PER_PACKET = 160;

    /** Packets per second = SAMPLE_RATE / SAMPLES_PER_PACKET. */
    private static final int PACKETS_PER_SECOND = SAMPLE_RATE / SAMPLES_PER_PACKET;

    /** Tone frequency in Hz for the generated beep. */
    private static final double TONE_HZ = 1_000.0;

    /** Amplitude scale (0.0–1.0) to avoid encoder clipping. */
    private static final double AMPLITUDE = 0.5;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteRtp;
    private final int ssrc;
    private int seqNumber;
    private long timestamp;

    /**
     * Creates an {@link RtpAudioPlayer} bound to the media session in {@code callMedia}.
     *
     * @param callMedia the negotiated call media; the socket must still be open
     */
    public RtpAudioPlayer(CallMedia callMedia) {
        this.socket = callMedia.localSocket();
        this.remoteRtp = callMedia.remoteRtp();

        Random rng = new Random();
        this.ssrc = rng.nextInt();
        this.seqNumber = rng.nextInt(0x10000);
        this.timestamp = rng.nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;
    }

    /**
     * Generates one second of a 1 kHz PCMA tone and sends it as RTP packets.
     *
     * <p>The {@code resourcePath} parameter is accepted for API compatibility but is currently
     * ignored; a generated sine-wave beep is always played instead.
     * Playback stops immediately when the thread is interrupted.
     *
     * @param resourcePath ignored; reserved for future MP3 playback support
     * @throws IOException          if a UDP send fails
     * @throws InterruptedException if the calling thread is interrupted mid-playback
     */
    @Override
    public void playBlocking(String resourcePath) throws IOException, InterruptedException {
        LOGGER.log(System.Logger.Level.DEBUG, "RTP: sending 1 s tone to {0} (resource={1})", remoteRtp, resourcePath);

        for (int i = 0; i < PACKETS_PER_SECOND; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("RTP playback interrupted");
            }

            byte[] payload = generatePcmaPacket();
            sendRtpPacket(payload);
            Thread.sleep(20);
        }
    }

    private byte[] generatePcmaPacket() {
        byte[] samples = new byte[SAMPLES_PER_PACKET];

        for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
            long sampleIndex = (timestamp + i) % SAMPLE_RATE;
            double angle = 2.0 * Math.PI * TONE_HZ * sampleIndex / SAMPLE_RATE;
            short pcm = (short) (Math.sin(angle) * Short.MAX_VALUE * AMPLITUDE);
            samples[i] = linearToAlaw(pcm);
        }

        return samples;
    }

    private void sendRtpPacket(byte[] payload) throws IOException {
        byte[] packet = new byte[12 + payload.length];

        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        packet[1] = 8; // M=0, PT=8 (PCMA)
        packet[2] = (byte) (seqNumber >> 8);
        packet[3] = (byte) (seqNumber & 0xFF);
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) (timestamp & 0xFF);
        packet[8] = (byte) (ssrc >> 24);
        packet[9] = (byte) (ssrc >> 16);
        packet[10] = (byte) (ssrc >> 8);
        packet[11] = (byte) (ssrc & 0xFF);
        System.arraycopy(payload, 0, packet, 12, payload.length);

        socket.send(new DatagramPacket(packet, packet.length, remoteRtp));

        seqNumber = (seqNumber + 1) & 0xFFFF;
        timestamp += SAMPLES_PER_PACKET;
    }

    /**
     * Encodes a 16-bit linear PCM sample to G.711 A-law (PCMA).
     * Based on the ITU-T G.711 specification.
     *
     * @param pcm the signed 16-bit PCM input sample
     * @return the A-law encoded byte
     */
    static byte linearToAlaw(short pcm) {
        final int[] segEnd = {0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF};

        int aval = pcm;
        int mask;

        if (aval >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            aval = -aval - 1;
        }

        if (aval > Short.MAX_VALUE) {
            aval = Short.MAX_VALUE;
        }

        int seg = 0;

        while (seg < 8 && aval > segEnd[seg]) {
            seg++;
        }

        if (seg >= 8) {
            return (byte) (0x7F ^ mask);
        }

        int alaw = seg << 4;

        if (seg < 2) {
            alaw |= (aval >> 1) & 0x0F;
        } else {
            alaw |= (aval >> seg) & 0x0F;
        }

        return (byte) (alaw ^ mask);
    }
}
