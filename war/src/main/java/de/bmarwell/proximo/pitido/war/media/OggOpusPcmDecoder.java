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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Decodes Opus audio stored in an OGG container to 8 kHz mono 16-bit PCM.
 *
 * <p>OGG pages are read manually (no external OGG library required).
 * Opus packets are decoded via JNA bindings to the system {@code libopus} library.
 * The system library must be installed: {@code apt install libopus0} on Debian/Ubuntu.
 *
 * <p>The decoder is initialised at 8 000 Hz to avoid resampling before RTP/PCMA encoding.
 *
 * <p>The first two OGG logical bitstream packets (OpusHead and OpusTags) are silently skipped
 * as required by RFC 7845.
 */
class OggOpusPcmDecoder implements PcmDecoder {

    private static final System.Logger LOGGER = System.getLogger(OggOpusPcmDecoder.class.getName());

    /** 8 kHz decoder sample rate — matches RTP/PCMA requirement. */
    private static final int SAMPLE_RATE = 8_000;

    /** Maximum decoded samples for one Opus frame (120 ms × 8 000 Hz). */
    private static final int MAX_FRAME_SAMPLES = 960;

    // -------------------------------------------------------------------------
    // JNA binding to libopus
    // -------------------------------------------------------------------------

    private interface LibOpus extends Library {

        /** Creates a new Opus decoder. @return pointer to decoder state, or NULL on failure. */
        Pointer opus_decoder_create(int fs, int channels, int[] error);

        /**
         * Decodes an Opus packet to 16-bit PCM.
         *
         * @param st         the decoder state
         * @param data       compressed data; {@code null} triggers PLC (packet loss concealment)
         * @param len        number of bytes in {@code data}
         * @param pcm        output buffer, interleaved; length ≥ {@code frameSize × channels}
         * @param frameSize  maximum number of samples per channel to decode
         * @param decodeFec  {@code 0} for normal, {@code 1} for FEC
         * @return samples decoded per channel, or a negative error code
         */
        int opus_decode(Pointer st, byte[] data, int len, short[] pcm, int frameSize, int decodeFec);

        /** Frees the decoder state. */
        void opus_decoder_destroy(Pointer st);
    }

    /** Lazily loaded library. Null until first {@link #open} call. */
    private static LibOpus libOpus;

    private static IOException loadError;

    static {
        try {
            libOpus = Native.load("opus", LibOpus.class);
        } catch (UnsatisfiedLinkError e) {
            loadError = new IOException("libopus not found. Install it with: apt install libopus0", e);
        }
    }

    @Override
    public PcmStream open(InputStream in) throws IOException {
        if (loadError != null) {
            throw loadError;
        }

        int[] error = new int[1];
        Pointer decoder = libOpus.opus_decoder_create(SAMPLE_RATE, 1, error);

        if (decoder == null || error[0] != 0) {
            throw new IOException("opus_decoder_create failed with error " + error[0]);
        }

        return new OggOpusPcmStream(in, libOpus, decoder);
    }

    // -------------------------------------------------------------------------
    // OGG packet reader + decoded PCM stream
    // -------------------------------------------------------------------------

    private static final class OggOpusPcmStream implements PcmStream {

        private final InputStream in;
        private final LibOpus opus;
        private final Pointer decoder;

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

        OggOpusPcmStream(InputStream in, LibOpus opus, Pointer decoder) throws IOException {
            this.in = in;
            this.opus = opus;
            this.decoder = decoder;
            readNextPage();
            skipHeaderPackets();
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
            short[] decoded = new short[MAX_FRAME_SAMPLES];
            int samples = this.opus.opus_decode(this.decoder, packet, packet.length, decoded, MAX_FRAME_SAMPLES, 0);

            if (samples < 0) {
                LOGGER.log(
                        System.Logger.Level.WARNING, "opus_decode returned error code [{0}]; skipping packet", samples);
                return true;
            }

            this.pcmBuffer = decoded;
            this.pcmOffset = 0;
            this.pcmAvailable = samples;

            return true;
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
            this.opus.opus_decoder_destroy(this.decoder);
            this.in.close();
        }
    }
}
