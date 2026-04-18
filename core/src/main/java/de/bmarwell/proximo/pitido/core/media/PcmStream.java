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
package de.bmarwell.proximo.pitido.core.media;

import java.io.Closeable;
import java.io.IOException;

/**
 * A stream of decoded 8 kHz mono 16-bit PCM audio samples.
 *
 * <p>Callers should read in chunks of 160 samples (20 ms at 8 kHz) to match one RTP packet.
 * The last chunk of a file may be partially filled; the caller must zero-pad it to 160 samples
 * before encoding.
 */
public interface PcmStream extends Closeable {

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
