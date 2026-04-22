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
package de.bmarwell.proximo.pitido.codecs.input;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Locale;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import org.apache.tika.mime.MediaType;

/**
 * Decodes Opus audio stored in an OGG container to mono 16-bit PCM at the sample rate requested
 * by the caller.
 *
 * <p>OGG pages are read manually (no external OGG library required).
 * Opus packets are decoded via the Foreign Function and Memory (FFM) API,
 * calling the system {@code libopus} shared library directly.
 * The system library must be installed: {@code apt install libopus0} on Debian/Ubuntu,
 * or {@code pacman -S opus} on Arch Linux.
 *
 * <p>libopus natively supports multiple output rates (8, 12, 16, 24, or 48 kHz);
 * the rate is chosen at decoder-create time via
 * {@link #open(InputStream, int)}.
 * This allows wideband codecs such as G.722 to receive 16 kHz PCM directly,
 * avoiding the lossy linear-interpolation upsample that would otherwise be required.
 *
 * <p>The first two OGG logical bitstream packets (OpusHead and OpusTags) are silently skipped
 * as required by RFC 7845.
 */
@ApplicationScoped
public class OggOpusPcmDecoder implements PcmDecoder {

    private static final System.Logger LOGGER = System.getLogger(OggOpusPcmDecoder.class.getName());

    /**
     * Maximum Opus frame duration: 120 ms × sample rate / 1000.
     * Used as a multiplier when computing the per-call buffer size in {@link #open(InputStream, int)}.
     */
    private static final int MAX_FRAME_DURATION_MS = 120;

    // -------------------------------------------------------------------------
    // FFM binding to libopus
    // -------------------------------------------------------------------------

    /**
     * Downcall handle for {@code opus_decoder_create(int fs, int channels, int *error)}.
     * Returns a native pointer to the decoder state, or NULL on failure.
     * The error code is written to the {@code int *error} out-parameter.
     */
    private MethodHandle opusDecoderCreate;

    /**
     * Downcall handle for
     * {@code opus_decode(OpusDecoder *st, const unsigned char *data, int len,
     *                    opus_int16 *pcm, int frame_size, int decode_fec)}.
     * Returns the number of decoded samples per channel, or a negative error code.
     */
    private MethodHandle opusDecode;

    /** Downcall handle for {@code opus_decoder_destroy(OpusDecoder *st)}. */
    private MethodHandle opusDecoderDestroy;

    /** Set when {@code libopus} cannot be found at startup; propagated from {@link #open}. */
    private IOException loadError;

    /** CDI no-args constructor. */
    public OggOpusPcmDecoder() {}

    /**
     * Probes for {@code libopus.so.0} and binds all required FFM method handles.
     * Called once by the CDI container after construction.
     */
    @PostConstruct
    @SuppressWarnings("restricted") // SymbolLookup.libraryLookup is FFM restricted — intentional use
    void initFfm() {
        try {
            SymbolLookup opus = SymbolLookup.libraryLookup("libopus.so.0", Arena.global());
            Linker linker = Linker.nativeLinker();

            this.opusDecoderCreate = linker.downcallHandle(
                    opus.find("opus_decoder_create").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, // return: OpusDecoder*
                            ValueLayout.JAVA_INT, // fs
                            ValueLayout.JAVA_INT, // channels
                            ValueLayout.ADDRESS // int* error (out-param)
                            ));

            this.opusDecode = linker.downcallHandle(
                    opus.find("opus_decode").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, // return: samples decoded
                            ValueLayout.ADDRESS, // OpusDecoder* st
                            ValueLayout.ADDRESS, // const unsigned char* data
                            ValueLayout.JAVA_INT, // len
                            ValueLayout.ADDRESS, // opus_int16* pcm (out-param)
                            ValueLayout.JAVA_INT, // frame_size
                            ValueLayout.JAVA_INT // decode_fec
                            ));

            this.opusDecoderDestroy = linker.downcallHandle(
                    opus.find("opus_decoder_destroy").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            LOGGER.log(System.Logger.Level.DEBUG, "libopus.so.0 loaded via FFM");

        } catch (IllegalArgumentException illegalArgumentException) {
            this.loadError = new IOException(
                    "libopus not found — install it with: apt install libopus0 / pacman -S opus",
                    illegalArgumentException);
        }
    }

    @Override
    public boolean supports(String resourcePath, MediaType mimeType) {
        String lower = resourcePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".opus") || lower.endsWith(".ogg")) {
            return true;
        }

        return MediaType.audio("ogg").equals(mimeType)
                || MediaType.audio("opus").equals(mimeType);
    }

    @Override
    public PcmStream open(InputStream in) throws IOException {
        return open(in, 8_000);
    }

    @Override
    public PcmStream open(InputStream in, int targetSampleRate) throws IOException {
        if (this.loadError != null) {
            throw this.loadError;
        }

        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment errSeg = callArena.allocate(ValueLayout.JAVA_INT);
            MemorySegment decoder = invokeCreate(errSeg, targetSampleRate);
            int error = errSeg.get(ValueLayout.JAVA_INT, 0L);

            if (decoder == null || decoder.address() == 0L || error != 0) {
                throw new IOException("opus_decoder_create failed with error " + error);
            }

            int maxFrameSamples = MAX_FRAME_DURATION_MS * targetSampleRate / 1_000;

            return new OggOpusPcmStream(
                    in, this.opusDecode, this.opusDecoderDestroy, decoder, targetSampleRate, maxFrameSamples);
        }
    }

    private MemorySegment invokeCreate(MemorySegment errSeg, int sampleRate) throws IOException {
        try {
            return (MemorySegment) this.opusDecoderCreate.invoke(sampleRate, 1, errSeg);
        } catch (RuntimeException runtimeException) {
            throw runtimeException;
        } catch (Throwable throwable) {
            throw new IOException("opus_decoder_create invocation failed", throwable);
        }
    }

    // -------------------------------------------------------------------------
    // OGG packet reader + decoded PCM stream
    // -------------------------------------------------------------------------

    private static final class OggOpusPcmStream implements PcmStream {

        private final InputStream in;
        private final MethodHandle opusDecode;
        private final MethodHandle opusDecoderDestroy;

        /**
         * Native pointer to the libopus decoder state.
         * Allocated by {@code opus_decoder_create}; freed by {@code opus_decoder_destroy} in {@link #close()}.
         */
        private final MemorySegment decoder;

        private final int streamSampleRate;
        private final int maxFrameSamples;

        /** Packet queue: OGG packets waiting to be decoded. */
        private final Deque<byte[]> packetQueue = new ArrayDeque<>();

        /** Accumulates segments that belong to the same packet. */
        private byte[] partialPacket = new byte[0];

        /** Decoded PCM samples waiting to be consumed by {@link #readSamples}. */
        private short[] pcmBuffer = new short[0];

        private int pcmOffset = 0;
        private int pcmAvailable = 0;

        /** Number of OGG header packets already skipped (OpusHead + OpusTags = 2). */
        private int headerPacketsSkipped = 0;

        private boolean endOfStream = false;

        OggOpusPcmStream(
                InputStream in,
                MethodHandle opusDecode,
                MethodHandle opusDecoderDestroy,
                MemorySegment decoder,
                int sampleRate,
                int maxFrameSamples)
                throws IOException {
            this.in = in;
            this.opusDecode = opusDecode;
            this.opusDecoderDestroy = opusDecoderDestroy;
            this.decoder = decoder;
            this.streamSampleRate = sampleRate;
            this.maxFrameSamples = maxFrameSamples;
            readNextPage();
            skipHeaderPackets();
        }

        @Override
        public int sampleRate() {
            return this.streamSampleRate;
        }

        @Override
        public int readSamples(short[] buf, int off, int len) throws IOException {
            int written = 0;

            while (written < len) {
                if (this.pcmOffset < this.pcmAvailable) {
                    buf[off + written] = this.pcmBuffer[this.pcmOffset++];
                    written++;
                    continue;
                }

                if (!decodeNextPacket()) {
                    return written == 0 ? -1 : written;
                }
            }

            return written;
        }

        /** Decodes the next available Opus packet. Returns {@code false} on end of stream. */
        private boolean decodeNextPacket() throws IOException {
            while (this.packetQueue.isEmpty()) {
                if (this.endOfStream) {
                    return false;
                }

                readNextPage();
            }

            byte[] packet = this.packetQueue.poll();

            try (Arena callArena = Arena.ofConfined()) {
                MemorySegment inputSeg = callArena.allocateFrom(ValueLayout.JAVA_BYTE, packet);
                MemorySegment pcmSeg = callArena.allocate(ValueLayout.JAVA_SHORT, this.maxFrameSamples);

                int samples = invokeDecode(inputSeg, packet.length, pcmSeg);

                if (samples < 0) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "opus_decode returned error code [{0}]; skipping packet",
                            samples);
                    return true;
                }

                this.pcmBuffer =
                        pcmSeg.asSlice(0L, (long) samples * Short.BYTES).toArray(ValueLayout.JAVA_SHORT);
                this.pcmOffset = 0;
                this.pcmAvailable = samples;
            }

            return true;
        }

        private int invokeDecode(MemorySegment inputSeg, int packetLength, MemorySegment pcmSeg) throws IOException {
            try {
                return (int)
                        this.opusDecode.invoke(this.decoder, inputSeg, packetLength, pcmSeg, this.maxFrameSamples, 0);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Throwable throwable) {
                throw new IOException("opus_decode invocation failed", throwable);
            }
        }

        /**
         * Reads one OGG page from the stream and adds assembled packets to {@link #packetQueue}.
         *
         * <p>OGG page format:
         * <ol>
         *   <li>4 bytes: capture pattern {@code "OggS"}</li>
         *   <li>1 byte: stream structure version (always 0)</li>
         *   <li>1 byte: header type flags</li>
         *   <li>8 bytes: granule position</li>
         *   <li>4 bytes: bitstream serial number</li>
         *   <li>4 bytes: page sequence number</li>
         *   <li>4 bytes: CRC checksum</li>
         *   <li>1 byte: number of page segments</li>
         *   <li>N bytes: segment table</li>
         *   <li>sum(segments) bytes: page body</li>
         * </ol>
         */
        private void readNextPage() throws IOException {
            byte[] capture = this.in.readNBytes(4);

            if (capture.length == 0) {
                this.endOfStream = true;
                return;
            }

            if (capture.length < 4
                    || capture[0] != 'O'
                    || capture[1] != 'g'
                    || capture[2] != 'g'
                    || capture[3] != 'S') {
                throw new IOException("Expected OGG capture pattern 'OggS', got: " + Arrays.toString(capture));
            }

            // Skip version(1) + header_type(1) + granule(8) + serial(4) + seqno(4) + crc(4) = 22 bytes
            skipFully(22);

            int numSegments = readUnsignedByte();
            byte[] segTable = this.in.readNBytes(numSegments);

            // Assemble packets: a segment of 255 means continuation; < 255 ends the packet
            for (int s = 0; s < numSegments; s++) {
                int segLen = segTable[s] & 0xFF;
                byte[] segData = this.in.readNBytes(segLen);
                this.partialPacket = concat(this.partialPacket, segData);

                if (segLen < 255) {
                    this.packetQueue.add(this.partialPacket);
                    this.partialPacket = new byte[0];
                }
            }
        }

        /** Skips the first two OGG packets (OpusHead and OpusTags) as required by RFC 7845. */
        private void skipHeaderPackets() throws IOException {
            while (this.headerPacketsSkipped < 2) {
                while (this.packetQueue.isEmpty()) {
                    if (this.endOfStream) {
                        return;
                    }

                    readNextPage();
                }

                this.packetQueue.poll();
                this.headerPacketsSkipped++;
            }
        }

        private void skipFully(int bytes) throws IOException {
            long remaining = bytes;

            while (remaining > 0) {
                long skipped = this.in.skip(remaining);

                if (skipped <= 0) {
                    throw new IOException("Unexpected end of OGG stream while skipping header");
                }

                remaining -= skipped;
            }
        }

        private int readUnsignedByte() throws IOException {
            int b = this.in.read();

            if (b == -1) {
                throw new IOException("Unexpected end of OGG stream");
            }

            return b;
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] result = new byte[a.length + b.length];
            System.arraycopy(a, 0, result, 0, a.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }

        @Override
        public void close() throws IOException {
            try {
                this.opusDecoderDestroy.invoke(this.decoder);
            } catch (RuntimeException runtimeException) {
                throw runtimeException;
            } catch (Throwable throwable) {
                throw new IOException("opus_decoder_destroy invocation failed", throwable);
            }

            this.in.close();
        }
    }
}
