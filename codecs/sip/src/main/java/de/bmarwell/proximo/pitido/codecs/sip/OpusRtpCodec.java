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

public class OpusRtpCodec extends NativeRtpCodec implements RtpCodec {

    /**
     * Conservative upper bound for one encoded Opus frame.
     * 4 000 bytes vastly exceeds any realistic 20 ms voice frame;
     * libopus will never exceed this limit for the given frame size and bitrate.
     */
    private static final int MAX_ENCODED_BYTES = 4000;

    /** Mono encoding: one channel. */
    private static final int OPUS_CHANNELS = 1;

    /** libopus application mode optimised for voice; enables DTX, FEC, and noise suppression. */
    private static final int OPUS_APPLICATION_VOIP = 2048;

    /**
     * CTL request code for enabling in-band FEC.
     * Passed as the first variadic argument to {@code opus_encoder_ctl}.
     */
    private static final int OPUS_SET_INBAND_FEC_REQUEST = 4012;

    /**
     * Alignment for the {@code OpusEncoder} state struct.
     * On x86-64, libopus uses 8-byte-aligned double fields internally.
     */
    private static final long STATE_ALIGN = 8L;

    /**
     * Pointer to the initialised {@code OpusEncoder} state, allocated inside
     * {@link NativeRtpCodec#callArena}.
     * {@code null} in the CDI factory bean.
     */
    private final MemorySegment stateSegment;

    private final int sampleRate;
    private final int frameSamples;

    private MethodHandle opusEncodeHandle;
    private MethodHandle opusEncoderDestroyHandle;

    private MethodHandle opusEncoderGetSizeHandle;
    private MethodHandle opusEncoderInitHandle;
    /** Typed binding for {@code opus_encoder_ctl(encoder, request, intValue)} (variadic). */
    private MethodHandle opusEncoderCtlIntHandle;

    protected OpusRtpCodec(int opusSampleRate, int frameSamples) {
        this.sampleRate = opusSampleRate;
        this.frameSamples = frameSamples;
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

        int stateSize = invokeGetSize();
        this.stateSegment = callArena.allocate(stateSize, STATE_ALIGN);
        invokeInit(this.stateSegment);
        invokeCtlInt(this.stateSegment, OPUS_SET_INBAND_FEC_REQUEST, 1);
    }

    private static final RtpCodecMetadata METADATA = new OpusMetadata();

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
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
     *
     * @param pcmFrame 960 mono PCM samples at 48 000 Hz; length must equal {@link #frameSamples}
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

    private int invokeEncode(MemorySegment inputSeg, MemorySegment outputSeg) throws IOException {
        try {
            return (int) this.opusEncodeHandle.invoke(
                    this.stateSegment, inputSeg, this.frameSamples, outputSeg, MAX_ENCODED_BYTES);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("opus_encode invocation failed", throwable);
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
                    this.opusEncoderInitHandle.invoke(state, this.sampleRate, OPUS_CHANNELS, OPUS_APPLICATION_VOIP);

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
}
