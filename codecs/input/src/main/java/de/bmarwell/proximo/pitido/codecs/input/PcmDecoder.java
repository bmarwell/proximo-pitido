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
import org.apache.tika.mime.MediaType;

/**
 * Opens a compressed audio stream and returns a {@link PcmStream} for reading decoded mono
 * 16-bit PCM samples.
 *
 * <p>Each implementation handles exactly one container/codec combination:
 * <ul>
 *   <li>{@link WavPcmDecoder} — WAV (deprecated, use Opus instead)</li>
 *   <li>{@link OggOpusPcmDecoder} — OGG container with Opus audio (preferred)</li>
 *   <li>{@link FlacPcmDecoder} — FLAC (stub; throws {@link UnsupportedOperationException})</li>
 * </ul>
 *
 * <p>Implementations are CDI {@code @ApplicationScoped} beans discovered by
 * {@link PcmDecoderFactory} at runtime via {@code Instance<PcmDecoder>}.
 */
public interface PcmDecoder {

    /**
     * Returns {@code true} if this decoder can handle the given audio resource.
     *
     * <p>Both the file extension of {@code resourcePath} and the detected {@code mimeType} are
     * checked.
     * The resource is accepted when either matches.
     * {@link MediaType#OCTET_STREAM} is treated as "unknown" and will not trigger a MIME match.
     *
     * @param resourcePath the full classpath resource path, e.g. {@code /audio/de/beep.opus}
     * @param mimeType     the MIME type detected by Apache Tika;
     *                     {@link MediaType#OCTET_STREAM} if the type could not be determined
     * @return {@code true} if this decoder can open and decode the resource
     */
    boolean supports(String resourcePath, MediaType mimeType);

    /**
     * Opens the given stream and prepares it for PCM sample decoding at the default 8 kHz rate.
     *
     * <p>Equivalent to {@code open(in, 8_000)}.
     *
     * @param in the raw audio data; ownership is transferred to the returned {@link PcmStream}
     * @return a {@link PcmStream} positioned at the first audio sample
     * @throws IOException if the stream cannot be read or is in an unexpected format
     */
    PcmStream open(InputStream in) throws IOException;

    /**
     * Opens the given stream and prepares it for PCM sample decoding at the requested sample rate.
     *
     * <p>Decoders that support multi-rate output (e.g. {@link OggOpusPcmDecoder} via libopus)
     * will honour {@code targetSampleRate}.
     * Decoders that do not support multi-rate output (e.g. {@link WavPcmDecoder}) ignore
     * {@code targetSampleRate} and always return 8 kHz samples; the caller must detect this via
     * {@link PcmStream#sampleRate()} and upsample if necessary.
     *
     * @param in               the raw audio data; ownership is transferred to the returned stream
     * @param targetSampleRate the desired output sample rate in Hz, e.g. {@code 8_000} or
     *                         {@code 16_000}
     * @return a {@link PcmStream} positioned at the first audio sample;
     *         {@link PcmStream#sampleRate()} reflects the actual rate chosen by the decoder
     * @throws IOException if the stream cannot be read or is in an unexpected format
     */
    default PcmStream open(InputStream in, int targetSampleRate) throws IOException {
        return open(in);
    }
}
