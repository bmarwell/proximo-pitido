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

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Pre-encoded RTP packet buffer for decoupling audio encoding from packet transmission.
 *
 * <p>Prevents encoder jitter from affecting RTP packet timing by buffering pre-calculated frames.
 * Encoder thread fills this queue ahead of transmission schedule; sender thread polls on precise 20ms cadence.
 *
 * <p>Capacity: 5 packets (~100ms preload).
 * When queue is full, encoder blocks until sender drains packets.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * RtpPacketQueue queue = new RtpPacketQueue();
 *
 * // Encoder thread (background)
 * while (encoder.hasMoreFrames()) {
 *     byte[] rtpPayload = encoder.encodeNextFrame();
 *     queue.put(rtpPayload);  // blocks if queue is full
 * }
 * queue.signalEnd();
 *
 * // Sender thread (main)
 * while (!queue.isEnded() || !queue.isEmpty()) {
 *     byte[] payload = queue.take();
 *     if (payload != null) {
 *         scheduler.waitUntilNextFrame();
 *         sendRtpPacket(payload);
 *         scheduler.advanceToNextFrame();
 *     }
 * }
 * </pre>
 */
final class RtpPacketQueue {

    /**
     * Pre-encoded RTP packet buffer.
     * Capacity 5 allows ~100ms preload at 50 packets/second.
     */
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(5);

    /**
     * Signals that no more packets will be enqueued.
     * Used by encoder thread after all frames are processed.
     */
    private volatile boolean ended = false;

    /**
     * Enqueues a pre-encoded RTP packet.
     *
     * <p>Blocks if the queue is at capacity (5 packets).
     * Encoder thread should call this from a background task.
     *
     * @param rtpPacket the encoded RTP payload bytes
     * @throws InterruptedException if the encoder thread is interrupted
     */
    void put(byte[] rtpPacket) throws InterruptedException {
        this.queue.put(rtpPacket);
    }

    /**
     * Dequeues the next pre-encoded RTP packet.
     *
     * <p>Blocks until a packet is available or the queue is ended and empty.
     * Sender thread should call this just before transmission.
     *
     * @return the encoded RTP payload, or {@code null} if queue is ended and empty
     * @throws InterruptedException if the sender thread is interrupted
     */
    byte[] take() throws InterruptedException {
        if (this.ended && this.queue.isEmpty()) {
            return null;
        }

        byte[] packet = this.queue.poll();
        if (packet != null) {
            return packet;
        }

        if (this.ended) {
            return null;
        }

        return this.queue.take();
    }

    /**
     * Signals that the encoder has finished and will not enqueue more packets.
     *
     * <p>Should be called by the encoder thread after all frames are processed.
     * Allows the sender thread to exit gracefully when the queue is drained.
     */
    void signalEnd() {
        this.ended = true;
    }

    /**
     * Checks if the queue is empty.
     *
     * @return {@code true} if no packets are currently buffered
     */
    boolean isEmpty() {
        return this.queue.isEmpty();
    }

    /**
     * Checks if the encoder has signalled completion.
     *
     * @return {@code true} if {@link #signalEnd()} has been called
     */
    boolean isEnded() {
        return this.ended;
    }

    /**
     * Returns the number of pre-encoded packets currently in the buffer.
     *
     * @return queue size (0–5)
     */
    int size() {
        return this.queue.size();
    }
}
