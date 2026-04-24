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
 * Opus RTP codec (RFC 7587, dynamic payload type 111).
 *
 * <p>Opus is a versatile open codec developed by the IETF Codec Working Group.
 * At 48 kHz mono it delivers transparent, wideband audio (20 Hz–20 kHz) in VBR mode,
 * making it the codec of choice for FLOSS softphones such as Linphone, Baresip, and Jitsi.
 *
 * <p>Deutsche Telekom, SIPGate, and other PSTN SIP trunks do not support Opus; they will never
 * offer it in an SDP INVITE, so this codec is automatically bypassed for those callers.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 7587 §5, the SDP attribute lines are:
 * <pre>
 * a=rtpmap:111 opus/48000/2
 * a=fmtp:111 useinbandfec=1
 * </pre>
 *
 * <p>The channel count of 2 in the {@code rtpmap} line is mandated by RFC 7587 regardless of
 * the actual channel count in the stream; this codec sends mono.
 *
 * <h2>Encoding parameters</h2>
 *
 * <ul>
 *   <li>Application: {@code OPUS_APPLICATION_VOIP} — optimised for voice; activates
 *       voice activity detection, noise suppression, and in-band FEC.</li>
 *   <li>In-band FEC: enabled via {@code OPUS_SET_INBAND_FEC_REQUEST = 1} — libopus embeds
 *       redundant recovery data for the previous packet inside the current packet, so receivers
 *       can conceal moderate random packet loss without retransmission.</li>
 *   <li>Bitrate: {@code OPUS_AUTO} (default) — libopus selects the optimal VBR bitrate for
 *       the signal content, typically 24–32 kbps for mono voice at 48 kHz.</li>
 *   <li>Frame size: 20 ms (960 samples at 48 kHz) — matches the standard RTP packet cadence.</li>
 * </ul>
 *
 * <h2>Native backend — libopus via FFM</h2>
 *
 * <p>Encoding is performed by {@code libopus} via the Foreign Function and Memory (FFM) API.
 * The same native library is used by
 * {@link de.bmarwell.proximo.pitido.codecs.input.OggOpusPcmDecoder} for audio file decoding;
 * it must be installed on the host system:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libopus0}</li>
 *   <li>Arch Linux: {@code pacman -S opus}</li>
 *   <li>RHEL / UBI 9: {@code microdnf install opus} (available in the base repository)</li>
 * </ul>
 *
 * <h2>Factory / per-call pattern</h2>
 *
 * <p>Opus ADPCM carries predictor state across packets.
 * This {@code @ApplicationScoped} CDI bean acts as a factory: {@link #forCall()} determines the
 * required encoder state size via {@code opus_encoder_get_size}, allocates a confined
 * {@link Arena}, initialises it with {@code opus_encoder_init}, and returns a plain (non-CDI)
 * {@code OpusRtpCodec} instance.
 * When the call ends, {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager} calls
 * {@link #close()}, which releases the confined arena and all native state immediately.
 *
 * @see G722RtpCodec
 * @see NativeRtpCodec
 */
@ApplicationScoped
public final class OpusRtpCodec extends NativeRtpCodec {

    private static final System.Logger LOGGER = System.getLogger(OpusRtpCodec.class.getName());

    /** libopus application mode optimised for voice; enables DTX, FEC, and noise suppression. */
    private static final int OPUS_APPLICATION_VOIP = 2048;

    /**
     * CTL request code for enabling in-band FEC.
     * Passed as the first variadic argument to {@code opus_encoder_ctl}.
     */
    private static final int OPUS_SET_INBAND_FEC_REQUEST = 4012;

    /** Mono encoding: one channel. */
    private static final int OPUS_CHANNELS = 1;

    /** Opus native sample rate: 48 000 Hz. */
    private static final int OPUS_SAMPLE_RATE = 48_000;

    /** 20 ms frame at 48 kHz: 960 samples. */
    private static final int FRAME_SAMPLES = 960;

    /**
     * Conservative upper bound for one encoded Opus frame.
     * 4 000 bytes vastly exceeds any realistic 20 ms voice frame;
     * libopus will never exceed this limit for the given frame size and bitrate.
     */
    private static final int MAX_ENCODED_BYTES = 4000;

    /**
     * Alignment for the {@code OpusEncoder} state struct.
     * On x86-64, libopus uses 8-byte-aligned double fields internally.
     */
    private static final long STATE_ALIGN = 8L;

    // -------------------------------------------------------------------------
    // CDI factory bean — set by @PostConstruct, null in per-call instances
    // -------------------------------------------------------------------------

    private MethodHandle opusEncoderGetSizeHandle;
    private MethodHandle opusEncoderInitHandle;
    /** Typed binding for {@code opus_encoder_ctl(encoder, request, intValue)} (variadic). */
    private MethodHandle opusEncoderCtlIntHandle;

    // -------------------------------------------------------------------------
    // Shared between factory and per-call instances
    // -------------------------------------------------------------------------

    private MethodHandle opusEncodeHandle;
    private MethodHandle opusEncoderDestroyHandle;

    // -------------------------------------------------------------------------
    // Per-call instance fields — null in the CDI factory bean
    // -------------------------------------------------------------------------

    /**
     * Pointer to the initialised {@code OpusEncoder} state, allocated inside
     * {@link NativeRtpCodec#callArena}.
     * {@code null} in the CDI factory bean.
     */
    private final MemorySegment stateSegment;

    /** CDI no-args constructor. */
    public OpusRtpCodec() {
        this.stateSegment = null;
    }

    /**
     * Per-call constructor — creates a non-CDI encoder instance for exactly one call leg.
     *
     * <p>Not intended for direct use; called only by {@link #forCall()}.
     *
     * @param opusEncodeHandle        downcall handle for {@code opus_encode}
     * @param opusEncoderDestroyHandle downcall handle for {@code opus_encoder_destroy}
     * @param callArena               confined arena owning the encoder state
     * @param stateSegment            initialised {@code OpusEncoder} state
     */
    OpusRtpCodec(
            MethodHandle opusEncodeHandle,
            MethodHandle opusEncoderDestroyHandle,
            Arena callArena,
            MemorySegment stateSegment) {
        super(callArena);
        this.opusEncodeHandle = opusEncodeHandle;
        this.opusEncoderDestroyHandle = opusEncoderDestroyHandle;
        this.stateSegment = stateSegment;
    }

    /**
     * Probes for {@code libopus.so.0} and binds all required FFM method handles.
     *
     * <p>Called once by the CDI container after construction.
     * Sets {@link NativeRtpCodec#available} to {@code true} when the library is found.
     * Uses {@link Arena#global()} so the library remains loaded for the lifetime of the JVM.
     */
    @PostConstruct
    @SuppressWarnings("restricted") // SymbolLookup.libraryLookup is FFM restricted — intentional use
    void probe() {
        try {
            SymbolLookup opus = SymbolLookup.libraryLookup("libopus.so.0", Arena.global());
            Linker linker = Linker.nativeLinker();

            this.opusEncoderGetSizeHandle = linker.downcallHandle(
                    opus.find("opus_encoder_get_size").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: size in bytes
                            ValueLayout.JAVA_INT // channels
                            ));

            this.opusEncoderInitHandle = linker.downcallHandle(
                    opus.find("opus_encoder_init").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: OPUS_OK (0) on success
                            ValueLayout.ADDRESS, // OpusEncoder* st
                            ValueLayout.JAVA_INT, // Fs (sample rate)
                            ValueLayout.JAVA_INT, // channels
                            ValueLayout.JAVA_INT // application
                            ));

            this.opusEncodeHandle = linker.downcallHandle(
                    opus.find("opus_encode").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: bytes encoded (>0) or error (<0)
                            ValueLayout.ADDRESS, // OpusEncoder* st
                            ValueLayout.ADDRESS, // const opus_int16* pcm
                            ValueLayout.JAVA_INT, // frame_size (samples)
                            ValueLayout.ADDRESS, // unsigned char* data (output)
                            ValueLayout.JAVA_INT // max_data_bytes
                            ));

            this.opusEncoderDestroyHandle = linker.downcallHandle(
                    opus.find("opus_encoder_destroy").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            // Variadic binding for opus_encoder_ctl(encoder, request, int_value).
            // firstVariadicArg(2) tells the linker that argument index 2 is the first variadic.
            this.opusEncoderCtlIntHandle = linker.downcallHandle(
                    opus.find("opus_encoder_ctl").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: OPUS_OK (0)
                            ValueLayout.ADDRESS, // OpusEncoder* st
                            ValueLayout.JAVA_INT, // request
                            ValueLayout.JAVA_INT // variadic int value
                            ),
                    Linker.Option.firstVariadicArg(2));

            this.available = true;
            LOGGER.log(System.Logger.Level.INFO, "libopus detected — Opus RTP codec available");

        } catch (IllegalArgumentException illegalArgumentException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "libopus not found — Opus RTP codec disabled: {0}",
                    illegalArgumentException.getMessage());
        }
    }

    @Override
    public int preference() {
        // Preferred over G.722 (50) when both are available: Opus delivers higher quality
        // and wider frequency response at equivalent or lower bitrate.
        // PSTN trunks never offer Opus, so this preference only activates for softphone callers.
        return 30;
    }

    /**
     * Returns a new per-call encoder instance with freshly initialised Opus encoder state.
     *
     * <p>Uses {@code opus_encoder_get_size(1)} to determine the required allocation, then
     * allocates a confined arena and calls {@code opus_encoder_init} followed by
     * {@code opus_encoder_ctl} to enable in-band FEC.
     *
     * @throws IllegalStateException if encoder initialisation fails
     */
    @Override
    public RtpCodecFactory forCall() {
        int stateSize = invokeGetSize();
        Arena arena = Arena.ofConfined();
        MemorySegment state = arena.allocate(stateSize, STATE_ALIGN);
        invokeInit(state);
        invokeCtlInt(state, OPUS_SET_INBAND_FEC_REQUEST, 1);

        return new OpusRtpCodec(this.opusEncodeHandle, this.opusEncoderDestroyHandle, arena, state);
    }

    @Override
    public int payloadType() {
        // De-facto standard dynamic payload type for Opus, used by virtually all softphones.
        return 111;
    }

    @Override
    public int rtpClockRate() {
        // RFC 7587 §4: Opus RTP clock rate is 48 000 Hz (no historical anomaly here).
        return OPUS_SAMPLE_RATE;
    }

    @Override
    public int inputSampleRate() {
        return OPUS_SAMPLE_RATE;
    }

    @Override
    public int samplesPerFrame() {
        // 20 ms × 48 000 Hz = 960 samples
        return FRAME_SAMPLES;
    }

    @Override
    public int rtpTimestampIncrement() {
        // 48 000 Hz / 50 packets per second = 960
        return FRAME_SAMPLES;
    }

    @Override
    public String sdpName() {
        return "opus";
    }

    /**
     * Returns {@code 2} as required by RFC 7587 §5, regardless of actual channel count.
     * The stream is mono; the SDP channel field is fixed at 2 for interoperability.
     */
    @Override
    public int sdpChannelCount() {
        return 2;
    }

    @Override
    public String fmtpParams() {
        // useinbandfec=1: instruct the remote to use in-band FEC recovery when available.
        return "useinbandfec=1";
    }

    /**
     * Encodes one frame of 960 mono PCM samples at 48 kHz to Opus wire format.
     *
     * <p>The returned byte array length varies per frame (VBR); the caller wraps it directly
     * as the RTP payload.
     * This method is only valid on per-call instances created by {@link #forCall()}.
     *
     * @param pcmFrame 960 mono PCM samples at 48 000 Hz; length must equal
     *                 {@link #samplesPerFrame()}
     * @return Opus-encoded bytes for one RTP packet
     * @throws IOException           if {@code opus_encode} returns a negative error code
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

            int bytesEncoded = invokeEncode(inputSeg, outputSeg);

            if (bytesEncoded < 0) {
                throw new IOException("opus_encode failed with error code " + bytesEncoded);
            }

            return outputSeg.asSlice(0L, bytesEncoded).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private int invokeGetSize() {
        try {
            return (int) this.opusEncoderGetSizeHandle.invoke(OPUS_CHANNELS);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("opus_encoder_get_size invocation failed", throwable);
        }
    }

    private void invokeInit(MemorySegment state) {
        try {
            int result = (int)
                    this.opusEncoderInitHandle.invoke(state, OPUS_SAMPLE_RATE, OPUS_CHANNELS, OPUS_APPLICATION_VOIP);

            if (result != 0) {
                throw new IllegalStateException("opus_encoder_init failed with error code " + result);
            }
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("opus_encoder_init invocation failed", throwable);
        }
    }

    private void invokeCtlInt(MemorySegment state, int request, int value) {
        try {
            this.opusEncoderCtlIntHandle.invoke(state, request, value);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IllegalStateException("opus_encoder_ctl invocation failed", throwable);
        }
    }

    private int invokeEncode(MemorySegment inputSeg, MemorySegment outputSeg) throws IOException {
        try {
            return (int) this.opusEncodeHandle.invoke(
                    this.stateSegment, inputSeg, FRAME_SAMPLES, outputSeg, MAX_ENCODED_BYTES);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("opus_encode invocation failed", throwable);
        }
    }
}
