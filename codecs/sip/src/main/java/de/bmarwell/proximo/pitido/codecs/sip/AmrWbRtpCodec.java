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
import java.util.Arrays;
import java.util.Locale;
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
public class AmrWbRtpCodec extends NativeRtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbRtpCodec.class.getName());

    /**
     * Default AMR-WB encoding mode: 2 (12.65 kbps).
     * Used when the caller's SDP offer does not include a {@code mode-set} constraint.
     * Mode 2 is the safest default — it is within the most restrictive mode-set seen in
     * practice (e.g. Deutsche Telekom's {@code mode-set=0,1,2}).
     * When a {@code mode-set} is offered, {@link #forCall(String)} extracts the maximum
     * allowed mode from that set instead.
     * Passed as the {@code mode} argument to {@code E_IF_encode}.
     */
    private static final int DEFAULT_ENCODING_MODE = 2;

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
     * Maximum output bytes from {@code E_IF_encode} at any mode.
     * The highest-rate AMR-WB frame (mode 8, 23.85 kbps) is 60 bytes of speech data.
     * 64 bytes is a rounded-up safe upper bound for all modes.
     */
    protected static final int MAX_ENCODED_BYTES = 64;

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

    protected MethodHandle eIfEncodeHandle;

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
    protected final MemorySegment stateSegment;

    /**
     * AMR-WB encoding mode for this per-call instance (0–8).
     * Derived from the caller's offered {@code mode-set} by {@link #forCall(String)};
     * defaults to {@link #DEFAULT_ENCODING_MODE} when no mode-set constraint is present.
     */
    protected final int encodingMode;

    /** CDI no-args constructor. */
    public AmrWbRtpCodec() {
        this.stateSegment = null;
        this.encodingMode = DEFAULT_ENCODING_MODE;
    }

    /**
     * Per-call constructor — creates a non-CDI encoder instance for exactly one call leg.
     *
     * <p>Not intended for direct use; called only by {@link #forCall(String)}.
     * The {@code stateSegment} must already be reinterpreted to the given arena so that
     * {@code E_IF_exit} is called automatically when the arena closes.
     *
     * @param eIfEncodeHandle downcall handle for {@code E_IF_encode}
     * @param callArena       confined arena that owns the encoder state lifetime
     * @param stateSegment    arena-scoped encoder state (from {@code E_IF_init} + reinterpret)
     * @param encodingMode    AMR-WB encoding mode (0–8) derived from the caller's mode-set
     */
    AmrWbRtpCodec(MethodHandle eIfEncodeHandle, Arena callArena, MemorySegment stateSegment, int encodingMode) {
        super(callArena);
        this.eIfEncodeHandle = eIfEncodeHandle;
        this.stateSegment = stateSegment;
        this.encodingMode = encodingMode;
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
        // Preference is 41 (octet-aligned): tried second, after bandwidth-efficient (preference 40).
        // RFC 4867 specifies bandwidth-efficient as the DEFAULT packetisation format.
        return 41;
    }

    /**
     * Returns a new per-call encoder instance initialised at the best mode allowed by the
     * caller's offered {@code mode-set}.
     *
     * <p>Parses the {@code mode-set} parameter from {@code offeredFmtp} (e.g.
     * {@code "octet-align=1;mode-set=0,1,2"}) and selects the highest mode index present.
     * Falls back to {@link #DEFAULT_ENCODING_MODE} when no {@code mode-set} is offered.
     *
     * <p>RFC 4867 §8.3.2 requires that both parties only use modes from the negotiated set;
     * using a mode outside the set produces frames the remote decoder will refuse.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty
     * @return a fully initialised per-call {@link AmrWbRtpCodec} with the negotiated mode
     * @throws IllegalStateException if the codec is not available or {@code E_IF_init} fails
     */
    @Override
    public RtpCodec forCall(String offeredFmtp) {
        if (!this.available) {
            throw new IllegalStateException(
                    "AMR-WB codec is not available — libvo-amrwbenc was not loaded; check probe() logs");
        }

        MemorySegment rawStatePtr = invokeInit();

        if (rawStatePtr.address() == 0L) {
            throw new IllegalStateException("E_IF_init returned null pointer — cannot create AMR-WB encoder");
        }

        int mode = extractBestMode(offeredFmtp);

        Arena arena = Arena.ofConfined();
        MemorySegment stateBoundToArena = rawStatePtr.reinterpret(STATE_SIZE, arena, this::invokeExit);

        return new AmrWbRtpCodec(this.eIfEncodeHandle, arena, stateBoundToArena, mode);
    }

    /**
     * Returns a new per-call encoder instance using the {@link #DEFAULT_ENCODING_MODE}.
     *
     * <p>Prefer {@link #forCall(String)} when the caller's offered fmtp is available, so that
     * the encoder uses the best mode allowed by the caller's {@code mode-set}.
     */
    @Override
    public RtpCodec forCall() {
        return forCall("");
    }

    /**
     * Echoes the caller's offered fmtp in the SDP answer, as required by RFC 4867 §8.3.2.
     *
     * <p>When the caller's offer included an {@code a=fmtp} line (e.g.
     * {@code "octet-align=1;mode-set=0,1,2;mode-change-capability=2;max-red=0"}),
     * the entire parameter string must be echoed unchanged in the answer so that the remote
     * IMS media proxy accepts the session.
     * Falls back to {@link #fmtpParams()} (just {@code "octet-align=1"}) when no fmtp was
     * offered.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty
     * @return the fmtp string for the SDP answer
     */
    @Override
    public String fmtpAnswer(String offeredFmtp) {
        if (offeredFmtp.isEmpty()) {
            return fmtpParams();
        } else {
            return offeredFmtp;
        }
    }

    /**
     * Extracts the highest AMR-WB mode index from the {@code mode-set} parameter in the offered
     * fmtp string.
     *
     * <p>For example, {@code "octet-align=1;mode-set=0,1,2"} yields {@code 2}.
     * Returns {@link #DEFAULT_ENCODING_MODE} when no {@code mode-set} is present or parseable.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty
     * @return the highest allowed AMR-WB mode index (0–8)
     */
    private static int extractBestMode(String offeredFmtp) {
        if (offeredFmtp.isBlank()) {
            return DEFAULT_ENCODING_MODE;
        }

        return Arrays.stream(offeredFmtp.split(";"))
                .map(String::strip)
                .filter(part -> part.toLowerCase(Locale.ROOT).startsWith("mode-set="))
                .findFirst()
                .map(part -> part.substring("mode-set=".length()))
                .stream()
                .flatMap(modes -> Arrays.stream(modes.split(",")))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .mapToInt(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException numberFormatException) {
                        return -1;
                    }
                })
                .filter(mode -> mode >= 0)
                .max()
                .orElse(DEFAULT_ENCODING_MODE);
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
     * This implementation requires octet-aligned packetisation (RFC 4867 §4.4).
     *
     * <p>Callers may advertise AMR-WB under multiple dynamic payload types or modes:
     * <ul>
     *   <li>Octet-aligned: requires {@code a=fmtp} with {@code octet-align=1} parameter.
     *       This is the preferred RFC 4867 §4.4 format; the codec will only accept this variant.
     *   <li>Bandwidth-efficient (RFC 4867 §4.3): indicated by the {@code /1} suffix in
     *       {@code a=rtpmap} (e.g. {@code a=rtpmap:104 AMR-WB/16000/1}) with no {@code a=fmtp}
     *       or {@code a=fmtp} omitting the {@code octet-align} parameter.
     *       This codec does not support bandwidth-efficient packetisation; callers offering only
     *       this variant will fall back to G.722 or other available codecs.
     * </ul>
     *
     * <p>Parameters are parsed as semicolon-separated {@code key=value} pairs per RFC 4867 §8.2,
     * so values such as {@code octet-align=10} are not falsely matched.
     */
    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        return Arrays.stream(offeredFmtp.split(";")).map(String::strip).anyMatch(AmrWbRtpCodec::isOctetAlignParam);
    }

    private static boolean isOctetAlignParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip()) && "1".equals(kv[1].strip());
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

            // libvo-amrwbenc outputs bandwidth-efficient format (ToC + speech) even though we
            // request octet-aligned. The first byte of encoder output is the ToC byte.
            // For octet-aligned payloads, we must strip it and prepend our own ToC.
            byte expectedToC = (byte) ((this.encodingMode << 3) | 0x04);
            byte firstEncoderByte = outputSeg.get(ValueLayout.JAVA_BYTE, 0);
            int offset = 0;
            int actualSpeechBytes = speechBytes;

            if (firstEncoderByte == expectedToC && speechBytes >= 33) {
                offset = 1;
                actualSpeechBytes = speechBytes - 1;
            }

            byte[] payload = buildOctetAlignedPayload(outputSeg, actualSpeechBytes, this.encodingMode, offset);

            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AMR-WB octet-aligned encode: encodingMode={0} encoderOutputBytes={1} offset={2} tocDetected={3} payloadBytes={4}",
                    this.encodingMode,
                    speechBytes,
                    offset,
                    firstEncoderByte == expectedToC,
                    payload.length);

            return payload;
        }
    }

    /**
     * Builds the complete RFC 4867 §4.4 octet-aligned RTP payload for one frame.
     *
     * <p>Layout: {@code [CMR][ToC][speech bytes…]}
     * <ul>
     *   <li>CMR = {@code 0xF0} (no codec mode request from sender).</li>
     *   <li>ToC = {@code F=0, FT=mode, Q=1, P=0, P=0} where FT is the AMR-WB frame type index
     *       (0–8); for mode 2 this yields {@code 0x14}.</li>
     * </ul>
     *
     * @param speechSeg encoder output segment containing potentially offset speech bytes
     * @param speechBytes number of speech bytes to copy (after offset applied)
     * @param encodingMode AMR-WB mode (0–8) to encode in the ToC byte
     * @param offset byte offset into speechSeg where actual speech begins (0 for normal, 1 if encoder
     *     prepended a ToC)
     */
    private static byte[] buildOctetAlignedPayload(
            MemorySegment speechSeg, int speechBytes, int encodingMode, int offset) {
        // ToC byte: F(0) | FT(4 bits) | Q(1) | P(1) | P(1)
        // F=0 (no further frames), Q=1 (good quality frame), P=0 (padding).
        byte toc = (byte) ((encodingMode << 3) | 0x04);
        byte[] payload = new byte[2 + speechBytes];
        payload[0] = CMR_NO_REQUEST;
        payload[1] = toc;
        byte[] speechData = speechSeg.asSlice(offset, speechBytes).toArray(ValueLayout.JAVA_BYTE);
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

    protected int invokeEncode(MemorySegment inputSeg, MemorySegment outputSeg) throws IOException {
        try {
            return (int) this.eIfEncodeHandle.invoke(this.stateSegment, this.encodingMode, inputSeg, outputSeg, 0);
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
