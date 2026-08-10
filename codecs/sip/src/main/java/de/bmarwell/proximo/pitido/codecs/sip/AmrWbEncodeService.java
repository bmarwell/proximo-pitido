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
package de.bmarwell.proximo.pitido.codecs.sip;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Serialises all {@code E_IF_encode} calls from {@code libvo-amrwbenc} onto a single
 * dedicated thread.
 *
 * <h2>Why a single thread?</h2>
 *
 * <p>{@code libvo-amrwbenc} has 120 KB of global/static mutable state ({@code .bss} + {@code .data}
 * sections) that is shared across all encoder instances in the process.
 * Calling {@code E_IF_encode} concurrently from two threads — even with distinct instance
 * pointers — produces data races on those globals, leading to null-dereference SIGSEGV
 * inside the library (confirmed in production crashes: two threads at the same
 * {@code RIP} inside {@code libvo-amrwbenc.so.0}).
 *
 * <p>The fix is to guarantee that at most one {@code E_IF_encode} call is in flight at any
 * time, by routing all encode calls through this single-thread executor.
 *
 * <h2>Performance</h2>
 *
 * <p>Each AMR-WB frame is 20 ms of audio, but the encoding itself takes roughly 50–200 µs of
 * CPU time.
 * At 15 concurrent callers × 50 frames/s = 750 frames/s, the encoder thread needs only
 * ~150 ms/s of CPU — well within a single core.
 * Each caller's encoder thread blocks on {@link Future#get()} during the encode, which
 * takes at most one frame-period in the worst case (200 µs, not 20 ms).
 *
 * <h2>Lifecycle</h2>
 *
 * <p>This class does not create its own thread.
 * The caller (typically a CDI producer in the {@code war} module) supplies a
 * single-thread {@link ExecutorService} backed by a Jakarta EE managed thread.
 * The executor must be shut down by the caller when the application stops.
 *
 * <h2>Memory safety</h2>
 *
 * <p>Input and output {@link MemorySegment}s passed to {@link #encode} must be backed by an
 * {@link java.lang.foreign.Arena#ofShared() Arena.ofShared()} arena so that the encoder
 * thread can safely access them.
 * The calling thread writes to the input segment <em>before</em> calling this method, and
 * reads from the output segment <em>after</em> {@link Future#get()} returns — the
 * submit/get pair provides the required happens-before guarantee.
 */
public class AmrWbEncodeService {

    private static final System.Logger LOGGER = System.getLogger(AmrWbEncodeService.class.getName());

    private final ExecutorService encoderThread;

    /**
     * No-args constructor for CDI proxy generation only.
     *
     * <p>Weld generates a proxy subclass for the {@code @ApplicationScoped} bean produced by
     * {@link de.bmarwell.proximo.pitido.war.AmrWbEncodeServiceProducer}.
     * The proxy never invokes application logic, so {@code encoderThread} is left {@code null}.
     */
    public AmrWbEncodeService() {
        this.encoderThread = null;
    }

    /**
     * Creates an encode service backed by the given executor.
     *
     * <p>The executor must be a <em>single-thread</em> executor so that at most one
     * {@code E_IF_encode} call is in flight at any time.
     * The executor is owned and managed by the caller; this class never shuts it down.
     *
     * @param encoderThread single-thread executor backed by a Jakarta EE managed thread
     */
    public AmrWbEncodeService(ExecutorService encoderThread) {
        this.encoderThread = encoderThread;
        LOGGER.log(System.Logger.Level.INFO, "AMR-WB encode service created");
    }

    /**
     * Submits one {@code E_IF_encode} call to the shared encoder thread and blocks until it
     * completes.
     *
     * <p>The caller's encoder thread is suspended on {@link Future#get()} for at most the time
     * of one encode operation (~50–200 µs), not a full 20 ms RTP frame period.
     *
     * @param encodeHandle FFM {@link MethodHandle} bound to {@code E_IF_encode}
     * @param stateSegment opaque encoder state pointer returned by {@code E_IF_init}
     * @param encodingMode AMR-WB mode index (0–8)
     * @param inputSeg     native segment containing 320 PCM samples (must be on a shared arena)
     * @param outputSeg    native segment to receive encoded bytes (must be on a shared arena)
     * @return number of bytes written to {@code outputSeg} by the native encoder
     * @throws IOException if the native call fails or is interrupted
     */
    int encode(
            MethodHandle encodeHandle,
            MemorySegment stateSegment,
            int encodingMode,
            MemorySegment inputSeg,
            MemorySegment outputSeg)
            throws IOException {
        Future<Integer> future = this.encoderThread.submit(() -> {
            try {
                return (int) encodeHandle.invoke(stateSegment, encodingMode, inputSeg, outputSeg, 0);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Throwable throwable) {
                throw new IOException("E_IF_encode invocation failed", throwable);
            }
        });

        try {
            return future.get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IOException("AMR-WB encode interrupted", interruptedException);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();

            if (cause instanceof IOException ioException) {
                throw ioException;
            }

            throw new IOException("AMR-WB E_IF_encode failed", cause);
        }
    }
}
