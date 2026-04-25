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
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
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
 *   <li>Payload type from the negotiated {@link RtpCodecFactory}</li>
 *   <li>20 ms packets at {@link RtpCodecFactory#samplesPerFrame()} samples per packet</li>
 *   <li>Sequence number and timestamp increment per packet</li>
 *   <li>SSRC is chosen randomly at construction time</li>
 *   <li>Marker bit (M) is set to 1 on the first packet of the call and on the first packet
 *       of each talkspurt (after silence ≥10 ms), per RFC 3551 §2.3</li>
 * </ul>
 *
 * <p>The caller is responsible for closing the {@link DatagramSocket} that backs this player;
 * obtain the socket via {@link CallMedia#localSocket()}.
 */
public class RtpAudioPlayer implements AudioPlayer {

    private static final System.Logger LOGGER = System.getLogger(RtpAudioPlayer.class.getName());
    private static final int RTP_PACKETS_PER_SECOND = 50;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteRtp;
    private final PcmDecoderFactory pcmDecoderFactory;
    private final RtpCodec codec;
    private final CallMedia callMedia;
    private final int ssrc;
    private int seqNumber;
    private long timestamp;
    private Instant lastPacketSentAt;
    private boolean firstPacketOfTalkspurt = true;

    /**
     * Creates an {@link RtpAudioPlayer} bound to the media session in {@code callMedia}.
     *
     * @param callMedia         the negotiated call media; the socket must still be open
     * @param callCodec         the per-call codec instance obtained by calling
     *                          {@code callMedia.codec().forCall()} on the announcement thread;
     *                          must be closed by the caller after the call ends
     * @param pcmDecoderFactory the factory used to select the decoder for each audio resource
     */
    public RtpAudioPlayer(CallMedia callMedia, RtpCodec callCodec, PcmDecoderFactory pcmDecoderFactory) {
        this.callMedia = callMedia;
        this.socket = callMedia.localSocket();
        this.remoteRtp = callMedia.remoteRtp();
        this.pcmDecoderFactory = pcmDecoderFactory;
        this.codec = callCodec;

        Random rng = new Random();
        this.ssrc = rng.nextInt();
        this.seqNumber = rng.nextInt(0x10000);
        this.timestamp = rng.nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;
    }

    /**
     * Sends silence RTP packets for exactly {@code duration}, keeping the receiver's jitter
     * buffer alive so the next audio plays with the correct timing gap.
     *
     * <p>Each packet carries a zero-filled PCM frame encoded by the negotiated codec.
     * Packets are sent at the same 20 ms cadence as normal audio packets.
     * Non-positive durations are silently ignored.
     *
     * @param duration how long to send silence
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Override
    public void playSilence(Duration duration) throws InterruptedException {
        long packets = duration.toMillis() / 20L;

        if (packets <= 0) {
            return;
        }

        short[] silenceFrame = new short[this.codec.metadata().samplesPerFrame()];

        for (long i = 0; i < packets; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("RTP silence interrupted");
            }

            if (this.callMedia.isHeld()) {
                this.timestamp += this.codec.metadata().rtpTimestampIncrement();
                Thread.sleep(20);
                continue;
            }

            try {
                sendRtpPacket(this.codec.encode(silenceFrame));
            } catch (IOException ioException) {
                LOGGER.log(System.Logger.Level.DEBUG, "Socket closed during silence playback", ioException);
                return;
            }

            this.lastPacketSentAt = Instant.now();
            // Throttle to the 20 ms RTP packet cadence so the remote end is not flooded.
            Thread.sleep(20);
        }
    }

    /**
     * Opens the classpath resource at {@code resourcePath}, decodes it to mono PCM at
     * {@link RtpCodecFactory#inputSampleRate()} Hz, encodes each 20 ms frame via the negotiated codec,
     * and sends it as an RTP packet.
     *
     * <p>Decoders that support multi-rate output (e.g. {@link de.bmarwell.proximo.pitido.codecs.input.OggOpusPcmDecoder})
     * will produce samples at exactly {@link RtpCodecFactory#inputSampleRate()}, avoiding any upsampling.
     * Decoders that do not (e.g. deprecated WAV) fall back to 8 kHz output; the pipeline then
     * upsamples to match the codec's expectation.
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
        advanceTimestampForSilence();

        try (InputStream rawStream = openResource(resourcePath);
                PcmStream pcm = this.pcmDecoderFactory
                        .forPath(resourcePath)
                        .open(rawStream, this.codec.metadata().inputSampleRate())) {
            int decoderSamplesPerPacket = pcm.sampleRate() / RTP_PACKETS_PER_SECOND;
            short[] decoderFrameBuf = new short[decoderSamplesPerPacket];
            short[] codecFrameBuf = new short[this.codec.metadata().samplesPerFrame()];
            validateFrameSizing(decoderSamplesPerPacket, codecFrameBuf.length);

            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("RTP playback interrupted");
                }

                if (this.callMedia.isHeld()) {
                    // Call is on hold: pause PCM consumption and RTP sending.
                    // Advance the RTP timestamp so the timeline stays consistent on resume.
                    this.timestamp += this.codec.metadata().rtpTimestampIncrement();
                    Thread.sleep(20);
                    continue;
                }

                int read = readFrame(pcm, decoderFrameBuf, decoderSamplesPerPacket);

                if (read == -1) {
                    break;
                }

                if (read < decoderSamplesPerPacket) {
                    // Zero-pad the last partial frame to a full 20 ms packet.
                    Arrays.fill(decoderFrameBuf, read, decoderSamplesPerPacket, (short) 0);
                }

                adaptPcmFrameForCodec(decoderFrameBuf, codecFrameBuf);
                sendRtpPacket(this.codec.encode(codecFrameBuf));
                this.lastPacketSentAt = Instant.now();
                Thread.sleep(20);
            }
        }
    }

    /**
     * Advances {@link #timestamp} to account for any silence between the last sent packet and now.
     *
     * <p>After {@link #playBlocking(String)} returns, {@code this.timestamp} points 20 ms ahead of
     * the last packet (the natural next-packet position).
     * If the caller sleeps before the next {@code playBlocking} call, the wall clock advances but
     * {@code this.timestamp} does not, causing the receiver to see no gap in the RTP stream.
     * This method calculates the extra elapsed time and advances the timestamp by the equivalent
     * number of silent 20 ms frames so that the receiver hears genuine silence.
     * When silence of 10 ms or more is detected, the {@link #firstPacketOfTalkspurt} flag is
     * set to true, which causes the next RTP packet to have the marker bit (M) set per RFC 3551 §2.3.
     */
    private void advanceTimestampForSilence() {
        if (this.lastPacketSentAt == null) {
            return;
        }

        long elapsedMs = Instant.now().toEpochMilli() - this.lastPacketSentAt.toEpochMilli();
        // this.timestamp already points 20ms past the last packet (the Thread.sleep(20)
        // after the last sendRtpPacket has run). Any elapsed time beyond that 20ms is silence.
        long extraSilenceMs = elapsedMs - 20L;

        if (extraSilenceMs >= 10L) {
            long silencePackets = extraSilenceMs / 20L;
            this.timestamp += silencePackets * this.codec.metadata().rtpTimestampIncrement();
            this.firstPacketOfTalkspurt = true;
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

    private static void validateFrameSizing(int decoderSamplesPerPacket, int codecSamplesPerPacket) throws IOException {
        if (decoderSamplesPerPacket == codecSamplesPerPacket) {
            return;
        }

        if (codecSamplesPerPacket == decoderSamplesPerPacket * 2) {
            // Decoder is at 8 kHz (e.g. deprecated WAV), codec needs 16 kHz — will upsample.
            return;
        }

        throw new IOException("Codec frame size mismatch: decoder provides " + decoderSamplesPerPacket
                + " samples but codec expects " + codecSamplesPerPacket);
    }

    private static void adaptPcmFrameForCodec(short[] decoderFrameBuf, short[] codecFrameBuf) {
        if (decoderFrameBuf.length == codecFrameBuf.length) {
            System.arraycopy(decoderFrameBuf, 0, codecFrameBuf, 0, decoderFrameBuf.length);
            return;
        }

        upsample8kTo16k(decoderFrameBuf, codecFrameBuf);
    }

    /**
     * Upsamples one 20 ms mono frame from 8 kHz (160 samples) to 16 kHz (320 samples).
     *
     * <p>Uses linear interpolation:
     * each source sample is copied, and the inserted sample between two points is the mean of
     * current and next source sample.
     * The final inserted sample repeats the last source sample.
     */
    private static void upsample8kTo16k(short[] source8k, short[] target16k) {
        for (int sourceIndex = 0; sourceIndex < source8k.length; sourceIndex++) {
            int targetIndex = sourceIndex * 2;
            short currentSample = source8k[sourceIndex];
            target16k[targetIndex] = currentSample;

            short nextSample = currentSample;

            if (sourceIndex + 1 < source8k.length) {
                nextSample = source8k[sourceIndex + 1];
            }

            target16k[targetIndex + 1] = (short) ((currentSample + nextSample) / 2);
        }
    }

    private void sendRtpPacket(byte[] payload) throws IOException {
        byte[] packet = new byte[12 + payload.length];

        packet[0] = (byte) 0x80; // V=2, P=0, X=0, CC=0

        int markerBit = this.firstPacketOfTalkspurt ? 0x80 : 0x00;
        packet[1] = (byte) (markerBit | this.codec.metadata().payloadType());

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

        boolean markerBitSet = (packet[1] & 0x80) != 0;

        LOGGER.log(
                System.Logger.Level.TRACE,
                "Sending RTP packet: seqNum={0} ts={1} marker={2} payloadBytes={3}",
                this.seqNumber,
                timestamp,
                markerBitSet,
                payload.length);

        this.socket.send(new DatagramPacket(packet, packet.length, this.remoteRtp));

        this.seqNumber = (this.seqNumber + 1) & 0xFFFF;
        timestamp += this.codec.metadata().rtpTimestampIncrement();
        this.firstPacketOfTalkspurt = false;
    }
}
