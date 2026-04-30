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

public class AmrWbRtpCodec extends NativeRtpCodec implements RtpCodec {

    private static final System.Logger LOGGER = System.getLogger(AmrWbRtpCodec.class.getName());

    /**
     * Default AMR-WB encoding mode: 2 (12.65 kbps).
     * Used when the caller's SDP offer does not include a {@code mode-set} constraint.
     * Mode 2 is the safest default — it is within the most restrictive mode-set seen in
     * practice (e.g. Deutsche Telekom's {@code mode-set=0,1,2}).
     * Passed as the {@code mode} argument to {@code E_IF_encode}.
     */
    private static final int DEFAULT_ENCODING_MODE = Integer.parseInt(System.getenv()
            .getOrDefault(
                    "AMR_WB_DEFAULT_MODE", "2")); // Can override with AMR_WB_DEFAULT_MODE=1 for lower bitrate testing

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
     * CMR byte prepended to every RTP payload (RFC 4867 §4.4.1).
     * {@code 0xF0} = "no codec mode request" (CMR field = 0xF, reserved bits = 0).
     */
    private static final byte CMR_NO_REQUEST = (byte) 0xF0;

    /**
     * Maximum output bytes from {@code E_IF_encode} at any mode.
     * The highest-rate AMR-WB frame (mode 8, 23.85 kbps) is 60 bytes of speech data.
     * 64 bytes is a rounded-up safe upper bound for all modes.
     */
    protected static final int MAX_ENCODED_BYTES = 64;

    private final MethodHandle eIfInitHandle;
    private final MethodHandle eIfExitHandle;

    protected MethodHandle eIfEncodeHandle;

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
     */
    protected final int encodingMode;

    /**
     * Reusable memory segment for FFM input allocation (PCM samples).
     * Created once per call to avoid Arena allocation overhead per 20ms frame.
     * Scope: per-call instance lifetime.
     * {@code null} in the CDI factory bean; non-null in per-call instances.
     */
    private MemorySegment reusableInputSegment;

    /**
     * Reusable memory segment for FFM output buffer (encoded speech bytes).
     * Created once per call to avoid per-frame allocation of {@link #MAX_ENCODED_BYTES}.
     * Scope: per-call instance lifetime.
     * {@code null} in the CDI factory bean; non-null in per-call instances.
     */
    private MemorySegment reusableOutputSegment;

    AmrWbRtpCodec(String offeredFmtp) {
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

        MemorySegment rawStatePtr = invokeInit();

        if (rawStatePtr.address() == 0L) {
            throw new IllegalStateException("E_IF_init returned null pointer — cannot create AMR-WB encoder");
        }

        this.encodingMode = extractBestMode(offeredFmtp);
        this.stateSegment = rawStatePtr.reinterpret(STATE_SIZE, callArena, this::invokeExit);

        this.reusableInputSegment =
                this.callArena.allocate(ValueLayout.JAVA_SHORT, metadata().samplesPerFrame());
        this.reusableOutputSegment = this.callArena.allocate(ValueLayout.JAVA_BYTE, MAX_ENCODED_BYTES);
    }

    /**
     * Builds the SDP answer fmtp parameters for octet-aligned AMR-WB.
     *
     * <p>When the caller's offer included an {@code a=fmtp} line (e.g.
     * {@code "mode-set=0,1,2;mode-change-capability=2;max-red=0"}),
     * we must echo the parameters in the answer but ensure {@code octet-align=1} is present
     * so the remote understands we are using octet-aligned format.
     * Falls back to {@link #fmtpParams()} when no fmtp was offered.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty
     * @return the fmtp string for the SDP answer, with {@code octet-align=1} guaranteed
     */
    private static final RtpCodecMetadata METADATA = new AmrWbMetadata();

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
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
     * This method is only valid on per-call instances created by {@link #forCall(String)}.
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

        // Copy PCM samples into pre-allocated reusable input segment to avoid per-frame allocation.
        // Pre-allocated segments live for the call duration; only the memory copy varies per frame.
        MemorySegment.copy(pcmFrame, 0, this.reusableInputSegment, ValueLayout.JAVA_SHORT, 0, pcmFrame.length);
        MemorySegment inputSeg = this.reusableInputSegment;
        MemorySegment outputSeg = this.reusableOutputSegment;

        // Log PCM input sample range for diagnostics
        short minSample = Short.MAX_VALUE;
        short maxSample = Short.MIN_VALUE;
        for (short sample : pcmFrame) {
            if (sample < minSample) minSample = sample;
            if (sample > maxSample) maxSample = sample;
        }
        short firstSample;
        if (pcmFrame.length > 0) {
            firstSample = pcmFrame[0];
        } else {
            firstSample = 0;
        }
        short lastSample;
        if (pcmFrame.length > 0) {
            lastSample = pcmFrame[pcmFrame.length - 1];
        } else {
            lastSample = 0;
        }

        LOGGER.log(
                System.Logger.Level.TRACE,
                "AMR-WB octet-aligned encode: encodingMode={0}, pcmSamples={1}, pcmRange=[{2},{3}], first={4}, last={5}",
                this.encodingMode,
                pcmFrame.length,
                minSample,
                maxSample,
                firstSample,
                lastSample);

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
            System.getLogger("AmrWbRtpCodec")
                    .log(
                            System.Logger.Level.TRACE,
                            "extractBestMode: blank fmtp, using DEFAULT_ENCODING_MODE={0}",
                            DEFAULT_ENCODING_MODE);
            return DEFAULT_ENCODING_MODE;
        }

        int selectedMode = Arrays.stream(offeredFmtp.split(";"))
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
                .filter(m -> m >= 0)
                .max()
                .orElse(DEFAULT_ENCODING_MODE);

        System.getLogger("AmrWbRtpCodec")
                .log(
                        System.Logger.Level.TRACE,
                        "extractBestMode: offeredFmtp=\"{0}\" selected mode={1}",
                        offeredFmtp,
                        selectedMode);

        return selectedMode;
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

    private void invokeExit(MemorySegment state) {
        try {
            this.eIfExitHandle.invoke(state);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("E_IF_exit invocation failed", throwable);
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

    protected int invokeEncode(MemorySegment inputSeg, MemorySegment outputSeg) throws IOException {
        try {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "invokeEncode: about to call E_IF_encode with encodingMode={0}",
                    this.encodingMode);
            return (int) this.eIfEncodeHandle.invoke(this.stateSegment, this.encodingMode, inputSeg, outputSeg, 0);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("E_IF_encode invocation failed", throwable);
        }
    }
}
