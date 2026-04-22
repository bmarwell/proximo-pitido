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
 * AMR-WB (Adaptive Multi-Rate Wideband, G.722.2) RTP codec (dynamic payload type 98).
 *
 * <p>AMR-WB delivers wideband audio (50 Hz–7 kHz) at 6.60–23.85 kbps.
 * Deutsche Telekom and most German mobile carriers use AMR-WB as the primary VoLTE codec.
 * Without this codec, mobile VoLTE callers fall back to G.711 PCMA (narrowband 8 kHz),
 * even though their device is capable of HD voice.
 *
 * <p>This implementation uses mode 8 (23.85 kbps) — the highest quality AMR-WB mode — so that
 * all mobile callers receive maximum audio fidelity.
 * The SDP negotiation selects this codec only when the caller's SIP INVITE offers AMR-WB.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 4867 §8.1, the SDP attribute lines for octet-aligned AMR-WB are:
 * <pre>
 * a=rtpmap:98 AMR-WB/16000
 * a=fmtp:98 octet-align=1
 * </pre>
 *
 * <h2>RTP payload format (RFC 4867 §4.4 octet-aligned)</h2>
 *
 * <p>Each RTP packet contains exactly one AMR-WB frame in octet-aligned mode:
 * <ol>
 *   <li>1-byte CMR header — {@code 0xF0} ("no codec mode request").</li>
 *   <li>1-byte Table of Contents (ToC) — frame type index (bits 6–3), quality flag
 *       {@code Q=1} (bit 2), and two padding bits; no continuation flag ({@code F=0}).</li>
 *   <li>Speech data bytes produced by {@code E_IF_encode}.</li>
 * </ol>
 *
 * <h2>Native backend — libvo-amrwbenc via FFM</h2>
 *
 * <p>Encoding is performed by {@code libvo-amrwbenc} via the Foreign Function and Memory (FFM)
 * API.
 * Install the library on the host system before starting the server:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libvo-amrwbenc0}</li>
 *   <li>Arch Linux: {@code yay -S vo-amrwbenc} (AUR)</li>
 *   <li>RHEL / UBI 9: {@code microdnf install vo-amrwbenc} (after EPEL)</li>
 * </ul>
 *
 * <p>Note: {@code libopencore-amrwb} (part of the {@code opencore-amr} package) only provides
 * an AMR-WB <em>decoder</em>.
 * AMR-WB <em>encoding</em> requires the separate {@code libvo-amrwbenc} library.
 *
 * <p>Three FFM functions are bound:
 * <ul>
 *   <li>{@code E_IF_init()} — allocates and returns an opaque encoder state pointer;
 *       called once per call leg.</li>
 *   <li>{@code E_IF_encode(state, mode, speech, serial, allow_dtx)} — encodes one 20 ms frame;
 *       called per RTP packet.</li>
 *   <li>{@code E_IF_exit(state)} — releases the encoder state; called when the call ends.</li>
 * </ul>
 *
 * <h2>Factory / per-call separation</h2>
 *
 * <p>AMR-WB ACELP carries pitch and gain predictor state across packets; sharing encoder state
 * between calls corrupts audio.
 * This {@code @ApplicationScoped} CDI bean acts as a factory: {@link #forCall()} calls
 * {@code E_IF_init()} to obtain a fresh encoder state, then returns a plain (non-CDI)
 * {@code AmrWbRtpCodec} instance.
 * When the call ends, {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager}
 * calls {@link #close()}, which calls {@code E_IF_exit(state)} to release the native state.
 *
 * @see G722RtpCodec
 * @see NativeRtpCodec
 */
@ApplicationScoped
public final class AmrWbRtpCodec extends NativeRtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbRtpCodec.class.getName());

    /**
     * AMR-WB encoding mode 8: 23.85 kbps — the highest quality mode.
     * Passed as the {@code mode} argument to {@code E_IF_encode}.
     */
    private static final int MODE_23850 = 8;

    /** Dynamic payload type for AMR-WB; conventional value used by all major VoLTE stacks. */
    private static final int PAYLOAD_TYPE = 98;

    /** AMR-WB processes audio at 16 000 Hz. */
    private static final int SAMPLE_RATE = 16_000;

    /** 20 ms frame at 16 000 Hz: 320 samples. */
    private static final int FRAME_SAMPLES = 320;

    /**
     * Opaque encoder state size sentinel for the pointer returned by {@code E_IF_init()}.
     * The native library owns this allocation, and its size is not part of the public API contract.
     * Java never dereferences the state memory, so it must not depend on a malloc-derived size
     * from a specific {@code libvo-amrwbenc} build.
     * Used with {@link MemorySegment#reinterpret(long, Arena, java.util.function.Consumer)}
     * only to associate the opaque handle with the call's arena lifetime;
     * resource release happens via {@code E_IF_exit(state)} registered as the cleanup action.
     */
    private static final long STATE_SIZE = 0L;

    /**
     * Maximum output bytes from {@code E_IF_encode} at mode 8 (23.85 kbps).
     * The highest-rate AMR-WB frame is 60 bytes of speech data.
     * 64 bytes is a rounded-up safe upper bound.
     */
    private static final int MAX_ENCODED_BYTES = 64;

    /**
     * CMR byte prepended to every RTP payload (RFC 4867 §4.4.1).
     * {@code 0xF0} = "no codec mode request" (CMR field = 0xF, reserved bits = 0).
     */
    private static final byte CMR_NO_REQUEST = (byte) 0xF0;

    // -------------------------------------------------------------------------
    // CDI factory bean fields — set by @PostConstruct; null in per-call instances
    // -------------------------------------------------------------------------

    private MethodHandle eIfInitHandle;
    private MethodHandle eIfExitHandle;

    // -------------------------------------------------------------------------
    // Shared between factory and per-call instances
    // -------------------------------------------------------------------------

    private MethodHandle eIfEncodeHandle;

    // -------------------------------------------------------------------------
    // Per-call instance field — null in the CDI factory bean
    // -------------------------------------------------------------------------

    /**
     * Encoder state reinterpreted to be scoped to the call's confined arena.
     *
     * <p>{@code null} in the CDI factory bean.
     * In per-call instances, holds the pointer returned by {@code E_IF_init()},
     * reinterpreted so that:
     * <ul>
     *   <li>{@code E_IF_exit(state)} is called automatically when the arena closes.</li>
     *   <li>Passing this segment to FFM after {@link #close()} throws
     *       {@link IllegalStateException} (FFM scope check), preventing use-after-free crashes.</li>
     * </ul>
     */
    private final MemorySegment stateSegment;

    /** CDI no-args constructor. */
    public AmrWbRtpCodec() {
        this.stateSegment = null;
    }

    /**
     * Per-call constructor — creates a non-CDI encoder instance for exactly one call leg.
     *
     * <p>Not intended for direct use; called only by {@link #forCall()}.
     * The {@code stateSegment} must already be reinterpreted to the given arena so that
     * {@code E_IF_exit} is called automatically when the arena closes.
     *
     * @param eIfEncodeHandle downcall handle for {@code E_IF_encode}
     * @param callArena       confined arena that owns the encoder state lifetime
     * @param stateSegment    arena-scoped encoder state (from {@code E_IF_init} + reinterpret)
     */
    AmrWbRtpCodec(MethodHandle eIfEncodeHandle, Arena callArena, MemorySegment stateSegment) {
        super(callArena);
        this.eIfEncodeHandle = eIfEncodeHandle;
        this.stateSegment = stateSegment;
    }

    /**
     * Probes for {@code libvo-amrwbenc.so.0} and binds all required FFM method handles.
     *
     * <p>Called once by the CDI container after construction.
     * Sets {@link NativeRtpCodec#available} to {@code true} when the library is found.
     * Uses {@link Arena#global()} so the library remains loaded for the lifetime of the JVM.
     */
    @PostConstruct
    @SuppressWarnings("restricted") // SymbolLookup.libraryLookup is FFM restricted — intentional use
    void probe() {
        try {
            SymbolLookup amrwb = SymbolLookup.libraryLookup("libvo-amrwbenc.so.0", Arena.global());
            Linker linker = Linker.nativeLinker();

            this.eIfInitHandle = linker.downcallHandle(
                    amrwb.find("E_IF_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS) // returns void* (encoder state)
                    );

            this.eIfEncodeHandle = linker.downcallHandle(
                    amrwb.find("E_IF_encode").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: bytes written to out
                            ValueLayout.ADDRESS, // void* state (encoder state)
                            ValueLayout.JAVA_INT, // int mode (0–8)
                            ValueLayout.ADDRESS, // const short* speech (input PCM)
                            ValueLayout.ADDRESS, // unsigned char* out (output bytes)
                            ValueLayout.JAVA_INT // int dtx (0 = disabled)
                            ));

            this.eIfExitHandle = linker.downcallHandle(
                    amrwb.find("E_IF_exit").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS) // void E_IF_exit(void*)
                    );

            this.available = true;
            LOGGER.log(System.Logger.Level.INFO, "libvo-amrwbenc detected — AMR-WB RTP codec available");

        } catch (IllegalArgumentException illegalArgumentException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "libvo-amrwbenc not found — AMR-WB RTP codec disabled: {0}",
                    illegalArgumentException.getMessage());
        }
    }

    @Override
    public int preference() {
        // Preferred over G.722 (50) for mobile VoLTE callers: lower bitrate, same wideband quality.
        // PSTN trunks never offer AMR-WB, so this codec activates only for mobile callers.
        return 40;
    }

    /**
     * Returns a new per-call encoder instance with a freshly initialised AMR-WB encoder state.
     *
     * <p>Calls {@code E_IF_init()} to allocate native encoder state, then reinterprets the
     * returned pointer to be scoped to a new confined arena.
     * When the arena closes (via {@link #close()}), {@code E_IF_exit(state)} is called
     * automatically, and the segment becomes invalid so that any attempt to encode after close
     * throws {@link IllegalStateException} rather than crashing the JVM.
     *
     * @throws IllegalStateException if the codec is not available (library not loaded),
     *                               or if {@code E_IF_init} returns a null pointer
     */
    @Override
    public RtpCodec forCall() {
        if (!this.available) {
            throw new IllegalStateException(
                    "AMR-WB codec is not available — libvo-amrwbenc was not loaded; check probe() logs");
        }

        MemorySegment rawStatePtr = invokeInit();

        if (rawStatePtr.address() == 0L) {
            throw new IllegalStateException("E_IF_init returned null pointer — cannot create AMR-WB encoder");
        }

        Arena arena = Arena.ofConfined();
        // Bind the library-owned state to the arena's lifetime so that:
        //  1. E_IF_exit() is called automatically when the arena closes.
        //  2. Passing the segment to FFM after close() throws IllegalStateException.
        MemorySegment stateBoundToArena = rawStatePtr.reinterpret(STATE_SIZE, arena, this::invokeExit);

        return new AmrWbRtpCodec(this.eIfEncodeHandle, arena, stateBoundToArena);
    }

    @Override
    public int payloadType() {
        return PAYLOAD_TYPE;
    }

    @Override
    public int rtpClockRate() {
        return SAMPLE_RATE;
    }

    @Override
    public int inputSampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public int samplesPerFrame() {
        // 20 ms × 16 000 Hz = 320 samples
        return FRAME_SAMPLES;
    }

    @Override
    public int rtpTimestampIncrement() {
        // 16 000 Hz / 50 packets per second = 320
        return FRAME_SAMPLES;
    }

    @Override
    public String sdpName() {
        return "AMR-WB";
    }

    @Override
    public String fmtpParams() {
        // octet-align=1: use the simpler octet-aligned packetisation (RFC 4867 §4.4).
        return "octet-align=1";
    }

    /**
     * Encodes one frame of 320 mono PCM samples at 16 kHz to AMR-WB octet-aligned RTP payload.
     *
     * <p>The returned array contains the complete RFC 4867 §4.4 octet-aligned payload:
     * a 1-byte CMR header, a 1-byte Table of Contents entry, and the encoded speech bytes.
     * This method is only valid on per-call instances created by {@link #forCall()}.
     *
     * @param pcmFrame 320 mono PCM samples at 16 000 Hz; length must equal
     *                 {@link #samplesPerFrame()}
     * @return RFC 4867 octet-aligned RTP payload bytes for one packet
     * @throws IOException           if {@code E_IF_encode} returns a negative error code
     * @throws IllegalStateException if called on the CDI factory bean (no encoder state)
     */
    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        if (this.stateSegment == null) {
            throw new IllegalStateException(
                    "encode() must not be called on the CDI factory bean; obtain a per-call instance via forCall() first");
        }

        try (Arena frameArena = Arena.ofConfined()) {
            MemorySegment inputSeg = frameArena.allocateFrom(ValueLayout.JAVA_SHORT, pcmFrame);
            MemorySegment outputSeg = frameArena.allocate(ValueLayout.JAVA_BYTE, MAX_ENCODED_BYTES);

            int speechBytes = invokeEncode(inputSeg, outputSeg);

            if (speechBytes < 0) {
                throw new IOException("E_IF_encode failed with error code " + speechBytes);
            }

            return buildOctetAlignedPayload(outputSeg, speechBytes);
        }
    }

    /**
     * Builds the complete RFC 4867 §4.4 octet-aligned RTP payload for one frame.
     *
     * <p>Layout: {@code [CMR][ToC][speech bytes…]}
     * <ul>
     *   <li>CMR = {@code 0xF0} (no codec mode request from sender).</li>
     *   <li>ToC = {@code F=0, FT=mode, Q=1, P=0, P=0} where FT is the AMR-WB frame type index
     *       (0–8); for mode 8 this yields {@code 0x44}.</li>
     * </ul>
     */
    private static byte[] buildOctetAlignedPayload(MemorySegment speechSeg, int speechBytes) {
        // ToC byte: F(0) | FT(4 bits) | Q(1) | P(1) | P(1)
        // F=0 (no further frames), Q=1 (good quality frame), P=0 (padding).
        byte toc = (byte) ((MODE_23850 << 3) | 0x04);
        byte[] payload = new byte[2 + speechBytes];
        payload[0] = CMR_NO_REQUEST;
        payload[1] = toc;
        byte[] speechData = speechSeg.asSlice(0L, speechBytes).toArray(ValueLayout.JAVA_BYTE);
        System.arraycopy(speechData, 0, payload, 2, speechBytes);

        return payload;
    }

    private MemorySegment invokeInit() {
        try {
            return (MemorySegment) this.eIfInitHandle.invoke();
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("E_IF_init invocation failed", throwable);
        }
    }

    private int invokeEncode(MemorySegment inputSeg, MemorySegment outputSeg) throws IOException {
        try {
            return (int) this.eIfEncodeHandle.invoke(this.stateSegment, MODE_23850, inputSeg, outputSeg, 0);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("E_IF_encode invocation failed", throwable);
        }
    }

    private void invokeExit(MemorySegment state) {
        try {
            this.eIfExitHandle.invoke(state);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("E_IF_exit invocation failed", throwable);
        }
    }
}
