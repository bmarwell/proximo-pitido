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

/**
 * Mixes one PCM frame of {@code N} channels down to a single mono sample.
 *
 * <p>Implementations are CDI beans discovered at runtime.
 * {@link #isAvailable()} guards whether a particular backend (e.g. a native library via FFM)
 * is present on the current host.
 * {@link WavPcmDecoder} selects the implementation with the lowest {@link #preference()} value
 * that returns {@code true} from {@link #isAvailable()}, falling back to {@link JavaChannelMixer}
 * if no higher-quality backend is found.
 *
 * @see JavaChannelMixer
 * @see LibsoxrChannelMixer
 */
public interface ChannelMixer {

    /**
     * Returns {@code true} if this mixer can be used on the current host.
     * Pure-Java implementations always return {@code true}.
     * Native-library implementations return {@code false} when the library is not installed.
     */
    boolean isAvailable();

    /**
     * Mixer priority for selection: lower value = tried first (higher quality preferred).
     *
     * <p>Example assignments:
     * <ul>
     *   <li>{@link LibsoxrChannelMixer} — 10 (high-quality resampling via libsoxr)</li>
     *   <li>{@link JavaChannelMixer} — 100 (always-available pure-Java fallback)</li>
     * </ul>
     */
    int preference();

    /**
     * Mixes {@code channelSamples.length} PCM channels down to one mono sample.
     *
     * @param channelSamples one 16-bit signed PCM sample per input channel, at the same time step
     * @return the mixed mono sample
     */
    short mix(short[] channelSamples);
}
