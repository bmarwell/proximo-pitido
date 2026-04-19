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
import de.bmarwell.proximo.pitido.codecs.input.PcmDecoderFactory;
import de.bmarwell.proximo.pitido.codecs.input.PcmStream;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Random;

/**
 * Sends audio to the remote caller as RTP packets over UDP.
 *
 * <p>Audio files are loaded from the classpath.
 * Supported formats:
 * <ul>
 *   <li>{@code .opus} — Opus codec in an OGG container (preferred); requires system {@code libopus0}</li>
 *   <li>{@code .wav} — WAV PCM (deprecated; use Opus instead)</li>
 *   <li>{@code .flac} — FLAC (not yet implemented; throws {@link UnsupportedOperationException})</li>
 * </ul>
 *
 * <p>RTP packet format:
 * <ul>
 *   <li>Version 2, no padding, no extension, CC = 0</li>
 *   <li>Payload type from the negotiated {@link RtpCodec}</li>
 *   <li>20 ms packets at {@link RtpCodec#samplesPerFrame()} samples per packet</li>
 *   <li>Sequence number and timestamp increment per packet</li>
 *   <li>SSRC is chosen randomly at construction time</li>
 * </ul>
 *
 * <p>The caller is responsible for closing the {@link DatagramSocket} that backs this player;
 * obtain the socket via {@link CallMedia#localSocket()}.
 */
public class RtpAudioPlayer implements AudioPlayer {

    private static final System.Logger LOGGER = System.getLogger(RtpAudioPlayer.class.getName());

    private final DatagramSocket socket;
    private final InetSocketAddress remoteRtp;
    private final PcmDecoderFactory pcmDecoderFactory;
    private final RtpCodec codec;
    private final int ssrc;
    private int seqNumber;
    private long timestamp;

    /**
     * Creates an {@link RtpAudioPlayer} bound to the media session in {@code callMedia}.
     *
     * @param callMedia         the negotiated call media; the socket must still be open
     * @param pcmDecoderFactory the factory used to select the decoder for each audio resource
     */
    public RtpAudioPlayer(CallMedia callMedia, PcmDecoderFactory pcmDecoderFactory) {
        this.socket = callMedia.localSocket();
        this.remoteRtp = callMedia.remoteRtp();
        this.pcmDecoderFactory = pcmDecoderFactory;
        this.codec = callMedia.codec();

        // TODO: when the decode pipeline supports configurable sample rates, pass
        // codec.inputSampleRate() to PcmDecoderFactory so decoders target the correct rate.
        // Currently the pipeline always outputs 8 kHz; this matters for G.722 (needs 16 kHz).

        Random rng = new Random();
        this.ssrc = rng.nextInt();
        this.seqNumber = rng.nextInt(0x10000);
        this.timestamp = rng.nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;
    }

    /**
     * Opens the classpath resource at {@code resourcePath}, decodes it to mono PCM at
     * {@link RtpCodec#inputSampleRate()} Hz, encodes each 20 ms frame via the negotiated codec,
     * and sends it as an RTP packet.
     *
     * <p>Playback stops immediately when the thread is interrupted.
     *
     * @param resourcePath classpath path of the audio file (e.g. {@code /audio/de/012.opus})
     * @throws IOException          if the resource cannot be opened or decoded
     * @throws InterruptedException if the calling thread is interrupted mid-playback
     */
    @Override
    public void playBlocking(String resourcePath) throws IOException, InterruptedException {
        LOGGER.log(System.Logger.Level.DEBUG, "RTP: playing [{0}] to {1}", resourcePath, this.remoteRtp);

        try (InputStream rawStream = openResource(resourcePath);
                PcmStream pcm = this.pcmDecoderFactory.forPath(resourcePath).open(rawStream)) {
            int samplesPerPacket = this.codec.samplesPerFrame();
            short[] frameBuf = new short[samplesPerPacket];

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("RTP playback interrupted");
                }

                int read = readFrame(pcm, frameBuf, samplesPerPacket);

                if (read == -1) {
                    break;
                }

                if (read < samplesPerPacket) {
                    // Zero-pad the last partial frame to a full 20 ms packet.
                    Arrays.fill(frameBuf, read, samplesPerPacket, (short) 0);
                }

                sendRtpPacket(this.codec.encode(frameBuf));
                Thread.sleep(20);
            }
        }
    }

    /**
     * Reads exactly {@code maxSamples} samples from {@code pcm}, handling short reads.
     * Returns -1 on immediate end-of-stream.
     */
    private int readFrame(PcmStream pcm, short[] buf, int maxSamples) throws IOException {
        int total = 0;

        while (total < maxSamples) {
            int n = pcm.readSamples(buf, total, maxSamples - total);

            if (n == -1) {
                return total == 0 ? -1 : total;
            }

            total += n;
        }

        return total;
    }

    private InputStream openResource(String resourcePath) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream stream = classLoader.getResourceAsStream(resourcePath);

        if (stream == null) {
            throw new IOException("Audio resource not found on classpath: " + resourcePath);
        }

        return stream;
    }

    private void sendRtpPacket(byte[] payload) throws IOException {
        byte[] packet = new byte[12 + payload.length];

        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0
        packet[1] = (byte) this.codec.payloadType(); // M=0, PT from negotiated codec
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

        this.socket.send(new DatagramPacket(packet, packet.length, this.remoteRtp));

        this.seqNumber = (this.seqNumber + 1) & 0xFFFF;
        timestamp += this.codec.rtpTimestampIncrement();
    }
}
