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

import java.util.Locale;

/**
 * Maps an audio resource path to the {@link PcmDecoder} that can decode it.
 *
 * <p>Extension matching is case-insensitive, so both {@code .wav} and {@code .WAV} are accepted.
 *
 * <p>Supported formats:
 * <ul>
 *   <li>{@code .opus} — Opus codec in an OGG container (preferred)</li>
 *   <li>{@code .wav} — WAV PCM ({@link WavPcmDecoder}, deprecated)</li>
 *   <li>{@code .flac} — FLAC (stub; throws {@link UnsupportedOperationException})</li>
 * </ul>
 */
final class PcmDecoderFactory {

    private PcmDecoderFactory() {
        // Utility class — do not instantiate.
    }

    /**
     * Returns a {@link PcmDecoder} suitable for the audio file at {@code resourcePath}.
     *
     * @param resourcePath the classpath resource path, used only to determine the file extension
     * @return a fresh {@link PcmDecoder} instance; never {@code null}
     * @throws IllegalArgumentException if the extension is not recognised
     */
    static PcmDecoder forPath(String resourcePath) {
        String lower = resourcePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".wav")) {
            return new WavPcmDecoder();
        }

        if (lower.endsWith(".opus")) {
            return new OggOpusPcmDecoder();
        }

        if (lower.endsWith(".flac")) {
            return new FlacPcmDecoder();
        }

        throw new IllegalArgumentException("No decoder available for audio resource: " + resourcePath);
    }
}
