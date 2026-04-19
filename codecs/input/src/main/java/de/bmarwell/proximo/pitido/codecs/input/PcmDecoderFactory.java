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

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import org.apache.tika.Tika;
import org.apache.tika.mime.MediaType;

/**
 * Maps an audio resource path to the {@link PcmDecoder} that can decode it.
 *
 * <p>Decoder selection is driven by two signals:
 * <ol>
 *   <li>The file extension in {@code resourcePath} (case-insensitive).</li>
 *   <li>The MIME type returned by Apache Tika for the same path.</li>
 * </ol>
 * The first registered {@link PcmDecoder} whose {@link PcmDecoder#supports} method returns
 * {@code true} is used.
 * {@link MediaType#OCTET_STREAM} is Tika's fallback for unrecognised types and will not trigger
 * a MIME match.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code .opus} — Opus codec in an OGG container (preferred)</li>
 *   <li>{@code .wav} — WAV PCM ({@link WavPcmDecoder}, deprecated)</li>
 *   <li>{@code .flac} — FLAC (stub; throws {@link UnsupportedOperationException})</li>
 * </ul>
 */
@ApplicationScoped
public class PcmDecoderFactory {

    private static final System.Logger LOGGER = System.getLogger(PcmDecoderFactory.class.getName());

    private final Tika tika = new Tika();

    @Inject
    Instance<PcmDecoder> decoders;

    /** CDI no-args constructor. */
    public PcmDecoderFactory() {}

    /**
     * Returns a {@link PcmDecoder} suitable for the audio file at {@code resourcePath}.
     *
     * @param resourcePath the classpath resource path, used to determine the decoder
     * @return a {@link PcmDecoder} that supports the resource; never {@code null}
     * @throws IllegalArgumentException if no registered decoder supports the resource
     */
    public PcmDecoder forPath(String resourcePath) {
        MediaType mimeType = detectMimeType(resourcePath);
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Selecting decoder for [{0}] (detected MIME type: [{1}])",
                resourcePath,
                mimeType);

        for (PcmDecoder decoder : this.decoders) {
            if (decoder.supports(resourcePath, mimeType)) {
                LOGGER.log(
                        System.Logger.Level.DEBUG,
                        "Using decoder [{0}] for [{1}]",
                        decoder.getClass().getSimpleName(),
                        resourcePath);
                return decoder;
            }
        }

        throw new IllegalArgumentException("No decoder available for audio resource: " + resourcePath);
    }

    private MediaType detectMimeType(String resourcePath) {
        String detected = this.tika.detect(resourcePath);

        return MediaType.parse(detected);
    }
}
