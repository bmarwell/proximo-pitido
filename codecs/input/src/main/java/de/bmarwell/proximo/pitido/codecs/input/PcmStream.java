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

import java.io.Closeable;
import java.io.IOException;

/**
 * A stream of decoded mono 16-bit PCM audio samples.
 *
 * <p>The actual sample rate is reported by {@link #sampleRate()}.
 * The caller should read in chunks of {@code sampleRate() / 50} samples (20 ms per RTP packet).
 * The last chunk of a file may be partially filled; the caller must zero-pad it to a full frame
 * before encoding.
 */
public interface PcmStream extends Closeable {

    /**
     * Returns the PCM sample rate in Hz of this stream.
     *
     * <p>The default is 8 000 Hz, which suits G.711 A-law (PCMA) and G.711 μ-law (PCMU).
     * Decoders that support multiple output rates (e.g. {@link OggOpusPcmDecoder}) may return
     * a higher rate when the caller requests one via
     * {@link PcmDecoder#open(java.io.InputStream, int)}.
     *
     * @return sample rate in Hz, e.g. {@code 8_000} or {@code 16_000}
     */
    default int sampleRate() {
        return 8_000;
    }

    /**
     * Reads up to {@code len} decoded PCM samples into {@code buf} starting at {@code off}.
     *
     * @param buf the destination array
     * @param off offset into {@code buf} at which to start writing
     * @param len maximum number of samples to read
     * @return the number of samples actually read, or {@code -1} on end-of-stream
     * @throws IOException if decoding fails
     */
    int readSamples(short[] buf, int off, int len) throws IOException;
}
