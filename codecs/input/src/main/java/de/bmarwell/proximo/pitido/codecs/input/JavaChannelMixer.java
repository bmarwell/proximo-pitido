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

/**
 * Pure-Java stereo-to-mono channel mixer using the ITU-R BS.775 −3 dB coefficient.
 *
 * <p>Applies a 1/√N factor to the sum of N channels, which preserves perceived loudness
 * compared to a plain arithmetic average (−6 dB).
 * The result is clamped to the {@code short} range to protect against clipping when all
 * channels carry maximally loud, perfectly correlated content.
 *
 * <p>This implementation is always available and serves as the fallback when no
 * higher-quality native backend (e.g. {@link LibsoxrChannelMixer}) is found.
 */
@ApplicationScoped
public class JavaChannelMixer implements ChannelMixer {

    /** CDI no-args constructor. */
    public JavaChannelMixer() {}

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int preference() {
        return 100;
    }

    @Override
    public short mix(short[] channelSamples) {
        int sum = 0;

        for (short sample : channelSamples) {
            sum += sample;
        }

        int mixed = (int) (sum / Math.sqrt(channelSamples.length));

        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
    }
}
