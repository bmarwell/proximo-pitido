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
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public class G722RtpCodec extends NativeRtpCodec implements RtpCodec {

    /** Bit-rate argument to {@code g722_encode_init}: 64 000 bps (standard G.722). */
    private static final int G722_RATE = 64_000;

    /** Options argument to {@code g722_encode_init}: 0 = standard ITU-T G.722 mode. */
    private static final int G722_OPTIONS = 0;

    /**
     * Size of {@code g722_encode_state_t} in bytes on x86-64 with libspandsp 2.0.
     * Computed via {@code sizeof(struct g722_encode_state_s)} = 172.
     */
    private static final long STATE_SIZE = 172L;

    /** Alignment for the state struct: widest member is {@code int} (4 bytes). */
    private static final long STATE_ALIGN = 4L;

    // CDI factory bean fields — set by @PostConstruct; null in per-call instances.
    private MethodHandle g722EncodeInitHandle;

    // Shared between factory and per-call instances.
    private MethodHandle g722EncodeHandle;

    /**
     * Allocated and initialised {@code g722_encode_state_t} for one call leg.
     * {@code null} in the CDI factory bean.
     */
    private final MemorySegment stateSegment;

    /**
     * Per-call constructor — creates an instance for exactly one call leg.
     */
    G722RtpCodec() {
        SymbolLookup spandsp = SymbolLookup.libraryLookup("libspandsp.so.2", Arena.global());
        Linker linker = Linker.nativeLinker();

        this.g722EncodeInitHandle = linker.downcallHandle(
                spandsp.find("g722_encode_init").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS, // return: g722_encode_state_t*
                        ValueLayout.ADDRESS, // s: pre-allocated state (non-NULL)
                        ValueLayout.JAVA_INT, // rate (64000)
                        ValueLayout.JAVA_INT // options (0)
                        ));

        this.g722EncodeHandle = linker.downcallHandle(
                spandsp.find("g722_encode").orElseThrow(),
                FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, // return: bytes encoded
                        ValueLayout.ADDRESS, // s: encoder state
                        ValueLayout.ADDRESS, // g722_data: output buffer (uint8_t[])
                        ValueLayout.ADDRESS, // amp: input PCM (int16_t[])
                        ValueLayout.JAVA_INT // len: number of input samples
                        ));

        final MemorySegment state = callArena.allocate(STATE_SIZE, STATE_ALIGN);
        this.stateSegment = invokeEncodeInit(state);

        if (this.stateSegment.address() == 0L) {
            throw new IllegalStateException("g722_encode_init returned null pointer — cannot create per-call encoder");
        }
    }

    @Override
    public int payloadType() {
        return 9;
    }

    @Override
    public int rtpClockRate() {
        return 8000;
    }

    @Override
    public int inputSampleRate() {
        return 16_000;
    }

    @Override
    public int samplesPerFrame() {
        return 320;
    }

    @Override
    public int rtpTimestampIncrement() {
        return 160;
    }

    @Override
    public String fmtpParams() {
        return "";
    }

    /**
     * Encodes one frame of 320 mono PCM samples at 16 kHz to G.722 wire format.
     *
     * <p>G.722 encodes 2 input samples per output byte (4 bits each sub-band), so 320 samples
     * produce exactly 160 output bytes.
     *
     * @param pcmFrame 320 mono PCM samples at 16 000 Hz
     * @return 160 bytes of G.722-encoded audio
     * @throws IOException           if the native {@code g722_encode} call fails
     * @throws IllegalStateException if called on the CDI factory bean (no encoder state)
     */
    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        if (this.stateSegment == null
                || this.callArena == null
                || !this.callArena.scope().isAlive()) {
            throw new IllegalStateException(
                    "encode() must not be called on the CDI factory bean; obtain a per-call instance via forCall() first");
        }

        try (Arena frameArena = Arena.ofConfined()) {
            MemorySegment outputSeg = frameArena.allocate(ValueLayout.JAVA_BYTE, (long) pcmFrame.length / 2);
            MemorySegment inputSeg = frameArena.allocateFrom(ValueLayout.JAVA_SHORT, pcmFrame);

            int bytesEncoded = invokeEncode(outputSeg, inputSeg, pcmFrame.length);

            return outputSeg.asSlice(0L, bytesEncoded).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /**
     * Closes the confined arena, releasing the native {@code g722_encode_state_t} segment.
     * Inherited from {@link NativeRtpCodecFactory}; no-op on the CDI factory bean.
     */
    private MemorySegment invokeEncodeInit(MemorySegment state) {
        try {
            return (MemorySegment) this.g722EncodeInitHandle.invoke(state, G722_RATE, G722_OPTIONS);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("g722_encode_init invocation failed", throwable);
        }
    }

    private int invokeEncode(MemorySegment outputSeg, MemorySegment inputSeg, int sampleCount) throws IOException {
        try {
            return (int) this.g722EncodeHandle.invoke(this.stateSegment, outputSeg, inputSeg, sampleCount);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("g722_encode invocation failed", throwable);
        }
    }
}
