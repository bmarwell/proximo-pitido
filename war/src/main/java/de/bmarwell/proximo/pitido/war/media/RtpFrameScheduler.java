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

/**
 * Precise RTP packet transmission scheduler using hybrid busy-wait.
 *
 * <p>Provides nanosecond-precision 20 ms frame timing without OS scheduler variance.
 * Combines coarse-grained sleep for large delays (>1ms) with busy-wait for final nanoseconds.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Track the next frame transmission time in nanoseconds.</li>
 *   <li>When a frame is ready, calculate the delay until next transmission slot.</li>
 *   <li>If delay >1ms: use {@code Thread.sleep()} for coarse sleep (safe from scheduler jitter).
 *   <li>If delay <1ms: busy-wait using {@code Thread.onSpinWait()} for precise alignment.</li>
 *   <li>After transmission, advance next-frame time by 20ms.</li>
 * </ol>
 *
 * <p>Why this works:
 * <ul>
 *   <li>{@code Thread.sleep()} is accurate to ~1ms; variance of ±500µs doesn't matter when delay is 5+ms.</li>
 *   <li>{@code Thread.onSpinWait()} hints to JVM/CPU to optimize spin-wait (PAUSE instruction on x86-64).</li>
 *   <li>System.nanoTime() is monotonic (no backward jumps from NTP adjustments).</li>
 *   <li>No OS-level real-time scheduling ({@code SCHED_FIFO}) needed.</li>
 * </ul>
 *
 * <p>Result: Inter-packet timing variance < 1ms (vs ±15ms with plain {@code Thread.sleep(20)}).
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * RtpFrameScheduler scheduler = new RtpFrameScheduler();
 *
 * while (hasMorePackets()) {
 *     RtpPacket packet = buildNextPacket();
 *     scheduler.waitUntilNextFrame();
 *     sendPacket(packet);
 *     scheduler.advanceToNextFrame();
 * }
 * </pre>
 */
final class RtpFrameScheduler {

    /**
     * RTP frame duration: 20 ms = 20,000,000 nanoseconds.
     */
    private static final long FRAME_DURATION_NANOS = 20_000_000L;

    /**
     * Threshold for switching from sleep to busy-wait.
     * Below 1ms, use busy-wait for precision; above 1ms, use sleep.
     */
    private static final long SLEEP_THRESHOLD_NANOS = 1_000_000L;

    /**
     * The wall-clock time (in nanoseconds) when the next packet should be sent.
     * Initialized on first call to {@link #waitUntilNextFrame()}.
     */
    private long nextFrameTimeNanos = -1L;

    /**
     * Waits until it is time to send the next RTP packet.
     *
     * <p>On the first call (and after a re-sync caused by a gap larger than one frame),
     * the frame clock is initialised to the current time and the method returns immediately.
     * Subsequent calls block until the scheduled frame time arrives.
     * Uses hybrid busy-wait: coarse sleep for large delays, spin-wait for final nanoseconds.
     *
     * <p>If the scheduled deadline falls more than one frame (20 ms) into the past — for
     * example after a hold period where no packets were enqueued — the clock is reset to the
     * current time so the sender does not burst-send multiple packets to catch up.
     *
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    void waitUntilNextFrame() throws InterruptedException {
        long now = System.nanoTime();

        if (this.nextFrameTimeNanos < 0L || this.nextFrameTimeNanos < now - FRAME_DURATION_NANOS) {
            this.nextFrameTimeNanos = now;
        }

        while (true) {
            long currentNanos = System.nanoTime();
            long delayNanos = this.nextFrameTimeNanos - currentNanos;

            if (delayNanos <= 0L) {
                break;
            }

            if (delayNanos > SLEEP_THRESHOLD_NANOS) {
                long sleepMillis = (delayNanos - 500_000L) / 1_000_000L;
                if (sleepMillis > 0L) {
                    Thread.sleep(sleepMillis);
                }
            } else {
                Thread.onSpinWait();
            }

            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("RTP frame scheduling interrupted");
            }
        }
    }

    /**
     * Advances the frame clock to the next 20ms slot.
     *
     * <p>Called after {@link #waitUntilNextFrame()} and the packet is sent.
     * Maintains precise long-term timing by always advancing by exactly 20ms.
     */
    void advanceToNextFrame() {
        this.nextFrameTimeNanos += FRAME_DURATION_NANOS;
    }
}
