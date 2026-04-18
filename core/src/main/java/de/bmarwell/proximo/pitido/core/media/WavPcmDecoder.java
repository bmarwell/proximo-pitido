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

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.enterprise.context.ApplicationScoped;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.tika.mime.MediaType;

/**
 * Decodes WAV audio to 8 kHz mono 16-bit PCM.
 *
 * <p>The WAV files used by this application are 8 kHz, 16-bit, stereo (two channels).
 * Stereo samples are mixed to mono using the ITU-R BS.775 −3 dB coefficient (1/√channels),
 * which matches broadcast-standard practice and sounds louder than a plain average.
 *
 * @deprecated WAV is a legacy format kept for backwards compatibility.
 *     New audio resources should use the Opus codec in an OGG container ({@code .opus}).
 *     WAV support will not be removed but will not receive further attention.
 */
@Deprecated
@ApplicationScoped
public class WavPcmDecoder implements PcmDecoder {

    private static final System.Logger LOGGER = System.getLogger(WavPcmDecoder.class.getName());

    private static final int REQUIRED_SAMPLE_RATE = 8_000;

    /** Ensures the deprecation warning is logged at most once per JVM lifetime. */
    private static final AtomicBoolean DEPRECATION_WARNED = new AtomicBoolean(false);

    @Override
    public boolean supports(String resourcePath, MediaType mimeType) {
        String lower = resourcePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".wav")) {
            return true;
        }

        return MediaType.audio("wav").equals(mimeType)
                || MediaType.audio("x-wav").equals(mimeType);
    }

    @Override
    public PcmStream open(InputStream in) throws IOException {
        if (DEPRECATION_WARNED.compareAndSet(false, true)) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Playing WAV audio (deprecated). Prefer .opus files in an OGG container.");
        }

        try {
            AudioInputStream raw = AudioSystem.getAudioInputStream(in);
            AudioFormat fmt = raw.getFormat();

            if ((int) fmt.getSampleRate() != REQUIRED_SAMPLE_RATE) {
                throw new IOException("WAV sample rate must be 8000 Hz, got: " + (int) fmt.getSampleRate());
            }

            int channels = fmt.getChannels();

            return new WavPcmStream(raw, channels);
        } catch (UnsupportedAudioFileException unsupportedAudioFileException) {
            throw new IOException("Not a valid WAV file", unsupportedAudioFileException);
        }
    }

    private static final class WavPcmStream implements PcmStream {

        private final AudioInputStream stream;
        private final int channels;

        /** Two bytes per 16-bit sample × number of channels. */
        private final byte[] rawBuf;

        WavPcmStream(AudioInputStream stream, int channels) {
            this.stream = stream;
            this.channels = channels;
            this.rawBuf = new byte[channels * 2];
        }

        @Override
        public int readSamples(short[] buf, int off, int len) throws IOException {
            int count = 0;

            while (count < len) {
                int bytesRead = this.stream.readNBytes(this.rawBuf, 0, this.rawBuf.length);

                if (bytesRead < this.rawBuf.length) {
                    return count == 0 ? -1 : count;
                }

                buf[off + count] = mixChannels();
                count++;
            }

            return count;
        }

        /**
         * Mixes all channels to mono using the ITU-R BS.775 −3 dB coefficient (1/√channels).
         * Little-endian 16-bit PCM.
         *
         * <p>This produces better perceived loudness than a simple arithmetic average (−6 dB),
         * matching broadcast-standard stereo-to-mono downmix practice.
         * Result is clamped to the {@code short} range to guard against rare clipping on
         * maximally loud multi-channel content.
         */
        private short mixChannels() {
            int sum = 0;

            for (int ch = 0; ch < this.channels; ch++) {
                int lo = this.rawBuf[ch * 2] & 0xFF;
                int hi = this.rawBuf[ch * 2 + 1];
                sum += (short) ((hi << 8) | lo);
            }

            int mixed = (int) (sum / Math.sqrt(this.channels));

            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }

        @Override
        public void close() throws IOException {
            this.stream.close();
        }
    }
}
