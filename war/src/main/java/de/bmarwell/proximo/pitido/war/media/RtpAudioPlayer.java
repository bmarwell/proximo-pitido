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
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 *       of each talkspurt (after a clip ends), per RFC 3551 §2.3</li>
 * </ul>
 *
 * <h2>Threading model</h2>
 *
 * <p>Three threads cooperate per call:
 * <ol>
 *   <li><b>Caller thread</b> — calls {@link #playSilence} and {@link #playBlocking} in sequence;
 *       blocks on a {@link CompletableFuture} until the clip is fully sent, so clips play
 *       one at a time with no overlap.</li>
 *   <li><b>Encoder thread</b> — submitted to the managed executor for each clip; decodes PCM,
 *       encodes to RTP payload, and enqueues frames into {@link #packetQueue}.
 *       Only one encoder task runs at a time (the caller blocks until the previous clip ends).</li>
 *   <li><b>Sender thread</b> — a virtual thread started in the constructor; runs for the
 *       entire call, dequeuing frames at precise 20 ms intervals via {@link RtpFrameScheduler}
 *       and sending them as UDP packets.
 *       Terminates when the socket is closed or the thread is interrupted.</li>
 * </ol>
 *
 * <p>Clip sequencing uses a {@code ClipEnd} sentinel enqueued by the encoder after all frames.
 * The sender completes the per-clip {@link CompletableFuture} when it processes the sentinel,
 * which unblocks the caller thread.
 * A {@link #senderStopped} fallback future ensures the caller never blocks indefinitely if the
 * socket closes mid-clip.
 *
 * <p>The caller is responsible for closing the {@link DatagramSocket} that backs this player;
 * obtain the socket via {@link CallMedia#localSocket()}.
 */
public class RtpAudioPlayer implements AudioPlayer {

    private static final System.Logger LOGGER = System.getLogger(RtpAudioPlayer.class.getName());
    private static final int RTP_PACKETS_PER_SECOND = 50;
    private static final int QUEUE_CAPACITY = 100;

    // Queue item types: either a pre-encoded RTP payload or a clip-end sentinel.
    private interface QueueItem {}

    private record Packet(byte[] payload) implements QueueItem {}

    private record ClipEnd(CompletableFuture<Void> done) implements QueueItem {}

    private final DatagramSocket socket;
    private final InetSocketAddress remoteRtp;
    private final PcmDecoderFactory pcmDecoderFactory;
    private final RtpCodec codec;
    private final CallMedia callMedia;
    private final int ssrc;
    private final javax.enterprise.concurrent.ManagedExecutorService managedExecutorService;
    private final LinkedBlockingQueue<QueueItem> packetQueue;

    /**
     * Completed when the sender thread exits (socket closed or interrupted).
     * Used as a fallback to unblock any caller waiting on a per-clip {@code done} future.
     */
    private final CompletableFuture<Void> senderStopped;

    /** Long-lived sender task; started in the constructor, runs for the entire call. */
    final Future<?> senderFuture;

    /** The most recently submitted encoder task; cancelled on interruption. */
    private volatile Future<?> encoderFuture;

    // RTP header state — only accessed from the sender thread.
    private int seqNumber;
    private long timestamp;
    private boolean firstPacketOfTalkspurt = true;

    /**
     * Creates an {@link RtpAudioPlayer} bound to the media session in {@code callMedia}.
     * The sender thread starts immediately and runs until the socket is closed.
     *
     * @param callMedia              the negotiated call media; the socket must still be open
     * @param callCodec              the per-call codec instance; must be closed by the caller
     * @param pcmDecoderFactory      the factory used to select the decoder for each audio resource
     * @param encoderService         Jakarta EE managed executor for the encoder background thread
     * @param senderService          Jakarta EE managed executor for the long-lived sender thread;
     *                               should support I/O-bound virtual threads
     */
    public RtpAudioPlayer(
            CallMedia callMedia,
            RtpCodec callCodec,
            PcmDecoderFactory pcmDecoderFactory,
            javax.enterprise.concurrent.ManagedExecutorService encoderService,
            javax.enterprise.concurrent.ManagedExecutorService senderService) {
        this.callMedia = callMedia;
        this.socket = callMedia.localSocket();
        this.remoteRtp = callMedia.remoteRtp();
        this.pcmDecoderFactory = pcmDecoderFactory;
        this.codec = callCodec;
        this.managedExecutorService = encoderService;
        this.packetQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.senderStopped = new CompletableFuture<>();

        Random rng = new Random();
        this.ssrc = rng.nextInt();
        this.seqNumber = rng.nextInt(0x10000);
        this.timestamp = rng.nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;

        this.senderFuture = senderService.submit(this::senderLoop);
    }

    /**
     * Sends silence RTP packets for exactly {@code duration}, keeping the receiver's jitter
     * buffer alive so the next audio plays with the correct timing gap.
     *
     * <p>Each packet carries a zero-filled PCM frame encoded by the negotiated codec.
     * Packets are sent at the same 20 ms cadence as normal audio packets.
     * Non-positive durations are silently ignored.
     * Blocks until the sender thread has transmitted all silence packets.
     *
     * @param duration how long to send silence
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Override
    public void playSilence(Duration duration) throws InterruptedException {
        long packetCount = duration.toMillis() / 20L;

        if (packetCount <= 0) {
            return;
        }

        short[] silenceFrame = new short[this.codec.metadata().samplesPerFrame()];
        CompletableFuture<Void> done = new CompletableFuture<>();

        this.senderStopped.thenRun(() -> done.complete(null));

        this.encoderFuture =
                this.managedExecutorService.submit(() -> encodeAndQueueSilence(packetCount, silenceFrame, done));

        awaitClipEnd(done);
    }

    /**
     * Opens the classpath resource at {@code resourcePath}, decodes it to mono PCM at
     * {@link RtpCodecFactory#inputSampleRate()} Hz, encodes each 20 ms frame via the negotiated codec,
     * and transmits it as RTP packets.
     * Blocks until the sender thread has transmitted all audio frames.
     *
     * <p>Decoders that support multi-rate output (e.g. {@link de.bmarwell.proximo.pitido.codecs.input.OggOpusPcmDecoder})
     * will produce samples at exactly {@link RtpCodecFactory#inputSampleRate()}, avoiding any upsampling.
     * Decoders that do not (e.g. deprecated WAV) fall back to 8 kHz output; the pipeline then
     * upsamples to match the codec's expectation.
     *
     * @param resourcePath classpath path of the audio file (e.g. {@code /audio/de/012.opus})
     * @throws IOException          if the resource cannot be opened or decoded
     * @throws InterruptedException if the calling thread is interrupted mid-playback
     */
    @Override
    public void playBlocking(String resourcePath) throws IOException, InterruptedException {
        LOGGER.log(System.Logger.Level.DEBUG, "RTP: playing [{0}] to {1}", resourcePath, this.remoteRtp);

        InputStream rawStream = openResource(resourcePath);
        PcmStream pcm = this.pcmDecoderFactory
                .forPath(resourcePath)
                .open(rawStream, this.codec.metadata().inputSampleRate());

        int decoderSamplesPerPacket = pcm.sampleRate() / RTP_PACKETS_PER_SECOND;
        short[] decoderFrameBuf = new short[decoderSamplesPerPacket];
        short[] codecFrameBuf = new short[this.codec.metadata().samplesPerFrame()];
        validateFrameSizing(decoderSamplesPerPacket, codecFrameBuf.length);

        CompletableFuture<Void> done = new CompletableFuture<>();

        this.senderStopped.thenRun(() -> done.complete(null));

        this.encoderFuture = this.managedExecutorService.submit(() -> encodeAndQueueAudioAndCloseStream(
                pcm, rawStream, decoderFrameBuf, codecFrameBuf, decoderSamplesPerPacket, done));

        awaitClipEnd(done);

        if (this.socket.isClosed()) {
            throw new IOException("Socket closed during playback");
        }
    }

    /**
     * Blocks until the clip's {@code done} future is completed by the sender thread.
     * On interruption, cancels the encoder task and drains the queue.
     */
    private void awaitClipEnd(CompletableFuture<Void> done) throws InterruptedException {
        try {
            done.get();
        } catch (ExecutionException executionException) {
            LOGGER.log(System.Logger.Level.DEBUG, "Clip encoder failed", executionException.getCause());
        } catch (InterruptedException interruptedException) {
            cancelEncoderAndDrainQueue();
            throw interruptedException;
        }
    }

    /**
     * Cancels the current encoder task and clears the packet queue.
     * Called when the caller thread is interrupted mid-clip (e.g. DTMF digit received).
     */
    private void cancelEncoderAndDrainQueue() {
        Future<?> future = this.encoderFuture;

        if (future != null) {
            future.cancel(true);

            try {
                future.get(200, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Expected: CancellationException, InterruptedException, or timeout.
            }
        }

        this.packetQueue.clear();
    }

    /**
     * Sender loop: runs for the entire call on a dedicated virtual thread.
     *
     * <p>Dequeues {@link Packet} items at precise 20 ms intervals and sends them as UDP datagrams.
     * When a {@link ClipEnd} sentinel is dequeued, the per-clip {@code done} future is completed
     * to unblock the caller thread, and {@link #firstPacketOfTalkspurt} is reset for the next clip.
     * Exits when the socket is closed, the thread is interrupted, or a send error occurs.
     * On exit, drains any remaining items to complete pending {@code done} futures and signals
     * {@link #senderStopped} so no caller blocks indefinitely.
     */
    private void senderLoop() {
        RtpFrameScheduler frameScheduler = new RtpFrameScheduler();

        try {
            while (!Thread.currentThread().isInterrupted() && !this.socket.isClosed()) {
                QueueItem item = this.packetQueue.poll(50, TimeUnit.MILLISECONDS);

                if (item == null) {
                    continue;
                }

                if (item instanceof Packet packet) {
                    frameScheduler.waitUntilNextFrame();
                    sendRtpPacket(packet.payload());
                    frameScheduler.advanceToNextFrame();
                } else if (item instanceof ClipEnd clipEnd) {
                    clipEnd.done().complete(null);
                    this.firstPacketOfTalkspurt = true;
                }
            }
        } catch (IOException ioException) {
            if (!this.socket.isClosed()) {
                LOGGER.log(System.Logger.Level.DEBUG, "Socket error during packet send", ioException);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            drainQueueAndSignalEnd();
        }
    }

    /**
     * Drains any remaining queue items and completes all pending {@link ClipEnd} futures.
     * Called from the sender thread's {@code finally} block on exit.
     */
    private void drainQueueAndSignalEnd() {
        QueueItem item;

        while ((item = this.packetQueue.poll()) != null) {
            if (item instanceof ClipEnd clipEnd) {
                clipEnd.done().complete(null);
            }
        }

        this.senderStopped.complete(null);
    }

    private void encodeAndQueueSilence(long packetCount, short[] silenceFrame, CompletableFuture<Void> done) {
        try {
            for (long i = 0; i < packetCount; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                if (this.callMedia.isHeld()) {
                    continue;
                }

                byte[] payload = this.codec.encode(silenceFrame);
                putInQueue(payload);
            }
        } catch (IOException ioException) {
            LOGGER.log(System.Logger.Level.DEBUG, "Failed to encode silence frame", ioException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.TRACE, "Silence encoder interrupted");
        } finally {
            enqueueClipEnd(done);
        }
    }

    private void encodeAndQueueAudioAndCloseStream(
            PcmStream pcm,
            InputStream rawStream,
            short[] decoderFrameBuf,
            short[] codecFrameBuf,
            int decoderSamplesPerPacket,
            CompletableFuture<Void> done) {
        try {
            encodeAndQueueAudio(pcm, decoderFrameBuf, codecFrameBuf, decoderSamplesPerPacket);
        } finally {
            try {
                pcm.close();
                rawStream.close();
            } catch (IOException ioException) {
                LOGGER.log(System.Logger.Level.DEBUG, "Failed to close PCM stream", ioException);
            }

            enqueueClipEnd(done);
        }
    }

    private void encodeAndQueueAudio(
            PcmStream pcm, short[] decoderFrameBuf, short[] codecFrameBuf, int decoderSamplesPerPacket) {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                if (this.callMedia.isHeld()) {
                    continue;
                }

                int read = readFrame(pcm, decoderFrameBuf, decoderSamplesPerPacket);

                if (read == -1) {
                    break;
                }

                if (read < decoderSamplesPerPacket) {
                    Arrays.fill(decoderFrameBuf, read, decoderSamplesPerPacket, (short) 0);
                }

                adaptPcmFrameForCodec(decoderFrameBuf, codecFrameBuf);
                byte[] payload = this.codec.encode(codecFrameBuf);
                putInQueue(payload);
            }
        } catch (IOException ioException) {
            LOGGER.log(System.Logger.Level.DEBUG, "Failed to encode audio frame", ioException);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.log(System.Logger.Level.TRACE, "Audio encoder interrupted");
        }
    }

    /**
     * Enqueues a pre-encoded RTP payload.
     * Blocks if the queue is full (capacity {@value QUEUE_CAPACITY}), which naturally paces the
     * encoder to the sender's 50-packets-per-second drain rate.
     */
    private void putInQueue(byte[] payload) throws InterruptedException {
        this.packetQueue.put(new Packet(payload));
    }

    /**
     * Enqueues the {@link ClipEnd} sentinel with up to three retries.
     * If all retries fail (queue full and cannot drain because sender has exited),
     * the {@code done} future is completed directly so the caller does not block forever.
     */
    private void enqueueClipEnd(CompletableFuture<Void> done) {
        ClipEnd sentinel = new ClipEnd(done);
        boolean offered = false;

        for (int attempt = 0; attempt < 3 && !offered; attempt++) {
            try {
                offered = this.packetQueue.offer(sentinel, 100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (!offered) {
            done.complete(null);
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
