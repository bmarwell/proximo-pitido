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
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

/**
 * G.722 wideband audio codec for RTP transmission (payload type 9).
 *
 * <p>G.722 delivers wideband audio (50 Hz–7 kHz) at 64 kbps using sub-band ADPCM
 * (ITU-T G.722, 1988).
 * A QMF analysis filter splits the 16 kHz input signal into two 8 kHz sub-bands.
 * The lower sub-band (0–4 kHz) is encoded with a 6-bit ADPCM coder; the upper sub-band
 * (4–8 kHz) uses a 2-bit ADPCM coder, yielding 4 bits per input sample and 160 bytes per
 * 20 ms packet.
 *
 * <p>Per RFC 3551 §4.5.2, the RTP clock rate for G.722 is declared as 8 000 Hz — a historical
 * anomaly preserved for interoperability; actual processing is at 16 kHz.
 * The RTP timestamp increments by 160 per 20 ms packet, identical to PCMA.
 *
 * <h2>Native backend — libspandsp via FFM</h2>
 *
 * <p>Encoding is performed by {@code libspandsp} via the Foreign Function and Memory (FFM) API.
 * Install it on the host system before starting the server:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libspandsp2}</li>
 *   <li>Arch Linux: {@code pacman -S spandsp}</li>
 *   <li>RHEL / UBI 9: {@code rpm -i https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm && microdnf install spandsp}</li>
 * </ul>
 *
 * <p>The FFM binding calls two functions:
 * <ul>
 *   <li>{@code g722_encode_init(state*, rate, options)} — initialises the ADPCM encoder state
 *       in a pre-allocated segment; called once per call leg in {@link #forCall()}.</li>
 *   <li>{@code g722_encode(state*, out_bytes*, in_pcm*, len)} — encodes one frame; called per
 *       packet in {@link #encode(short[])}.</li>
 * </ul>
 *
 * <h2>Factory / per-call separation</h2>
 *
 * <p>G.722 ADPCM carries predictor state across packets; sharing encoder state between calls
 * would corrupt audio.
 * This {@code @ApplicationScoped} CDI bean acts as a factory: {@link #forCall()} allocates a
 * fresh {@link Arena} and {@code g722_encode_state_t} segment for each call leg and returns a
 * plain (non-CDI) {@code G722RtpCodec} instance.
 *
 * <p>TODO: extend {@link RtpCodec} with {@link AutoCloseable} so per-call arenas are released
 * promptly when the call ends rather than relying on GC.
 *
 * <h2>G.729 — will not be implemented</h2>
 *
 * <p>G.729 (CS-ACELP, payload type 18) will <em>not</em> be implemented in this project.
 * The algorithm complexity makes a pure-Java port impractical, and the codec is dying in practice —
 * Deutsche Telekom and most modern SIP providers do not offer it.
 *
 * @see PcmaRtpCodec
 */
@ApplicationScoped
public final class G722RtpCodec implements RtpCodec {

    private static final System.Logger LOGGER = System.getLogger(G722RtpCodec.class.getName());

    /**
     * Size of {@code g722_encode_state_t} in bytes on x86-64 with libspandsp 2.0.
     * Computed via {@code sizeof(struct g722_encode_state_s)} = 172.
     */
    private static final long STATE_SIZE = 172L;

    /** Alignment for the state struct: widest member is {@code int} (4 bytes). */
    private static final long STATE_ALIGN = 4L;

    /** Bit-rate argument to {@code g722_encode_init}: 64 000 bps (standard G.722). */
    private static final int G722_RATE = 64_000;

    /** Options argument to {@code g722_encode_init}: 0 = standard ITU-T G.722 mode. */
    private static final int G722_OPTIONS = 0;

    // CDI factory bean fields — set by @PostConstruct; null in per-call instances.
    private boolean available = false;
    private MethodHandle g722EncodeInitHandle;

    // Shared between factory and per-call instances.
    private MethodHandle g722EncodeHandle;

    // Per-call instance fields — null in the CDI factory bean.

    /**
     * GC-managed arena owning the native encoder state for this call leg.
     * {@code null} in the CDI factory bean.
     *
     * <p>Using {@link Arena#ofAuto()} avoids resource leaks until {@link RtpCodec} is extended
     * with {@link AutoCloseable} and the caller can close it explicitly.
     */
    private final Arena callArena;

    /**
     * Allocated and initialised {@code g722_encode_state_t} for one call leg.
     * {@code null} in the CDI factory bean.
     */
    private final MemorySegment stateSegment;

    /** CDI no-args constructor. */
    public G722RtpCodec() {
        this.callArena = null;
        this.stateSegment = null;
    }

    /**
     * Per-call constructor — creates a non-CDI encoder instance for exactly one call leg.
     *
     * <p>Not intended for direct use; called only by {@link #forCall()}.
     *
     * @param g722EncodeHandle downcall handle for {@code g722_encode}, resolved at probe time
     * @param callArena        GC-managed arena owning the encoder state for the call's lifetime
     * @param stateSegment     allocated and initialised {@code g722_encode_state_t}
     */
    G722RtpCodec(MethodHandle g722EncodeHandle, Arena callArena, MemorySegment stateSegment) {
        this.g722EncodeHandle = g722EncodeHandle;
        this.callArena = callArena;
        this.stateSegment = stateSegment;
        this.available = true;
    }

    /**
     * Probes for {@code libspandsp.so.2} and binds the required FFM method handles.
     *
     * <p>Called once by the CDI container after construction.
     * Sets {@link #available} to {@code true} when the library is found and all symbols resolve.
     * Uses {@link Arena#global()} so the library remains loaded for the lifetime of the JVM.
     */
    @PostConstruct
    @SuppressWarnings("restricted") // SymbolLookup.libraryLookup is FFM restricted — intentional use
    void probe() {
        try {
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

            this.available = true;
            LOGGER.log(System.Logger.Level.INFO, "libspandsp detected — G.722 wideband codec available");

        } catch (IllegalArgumentException illegalArgumentException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "libspandsp not found — G.722 wideband codec disabled: {0}",
                    illegalArgumentException.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    @Override
    public int preference() {
        // Preferred over PCMA when available: wideband audio sounds significantly better.
        return 50;
    }

    /**
     * Returns a new per-call encoder instance with freshly initialised G.722 ADPCM state.
     *
     * <p>Each call leg must use its own encoder state.
     * Sharing ADPCM predictor state across calls corrupts the audio stream.
     *
     * <p>The returned instance is not a CDI bean.
     * It holds a GC-managed {@link Arena} and an initialised {@code g722_encode_state_t}.
     *
     * @throws IllegalStateException if {@code g722_encode_init} returns a null pointer
     */
    @Override
    public RtpCodec forCall() {
        Arena arena = Arena.ofAuto();
        MemorySegment state = arena.allocate(STATE_SIZE, STATE_ALIGN);
        MemorySegment initialised = invokeEncodeInit(state);

        if (initialised.address() == 0L) {
            throw new IllegalStateException("g722_encode_init returned null pointer — cannot create per-call encoder");
        }

        return new G722RtpCodec(this.g722EncodeHandle, arena, state);
    }

    @Override
    public int payloadType() {
        return 9;
    }

    @Override
    public int rtpClockRate() {
        // RFC 3551 §4.5.2: G.722 uses an 8 000 Hz RTP clock despite 16 kHz processing.
        return 8000;
    }

    @Override
    public int inputSampleRate() {
        return 16_000;
    }

    @Override
    public int samplesPerFrame() {
        // 20 ms × 16 000 Hz = 320 samples
        return 320;
    }

    @Override
    public int rtpTimestampIncrement() {
        // rtpClockRate() / 50 packets per second = 160
        return 160;
    }

    /**
     * Encodes one frame of 320 mono PCM samples at 16 kHz to G.722 wire format.
     *
     * <p>G.722 encodes 2 input samples per output byte (4 bits each sub-band), so 320 samples
     * produce exactly 160 output bytes.
     * This method is only valid on per-call instances created by {@link #forCall()}.
     *
     * @param pcmFrame 320 mono PCM samples at 16 000 Hz
     * @return 160 bytes of G.722-encoded audio
     * @throws IOException           if the native {@code g722_encode} call fails
     * @throws IllegalStateException if called on the CDI factory bean (no encoder state)
     */
    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        if (this.stateSegment == null) {
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

    @Override
    public String sdpName() {
        return "G722";
    }

    @Override
    public String fmtpParams() {
        return "";
    }

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
