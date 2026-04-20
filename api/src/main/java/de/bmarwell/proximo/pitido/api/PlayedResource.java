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
package de.bmarwell.proximo.pitido.api;

import java.time.Duration;
import java.time.Instant;

/**
 * A single audio resource that was submitted for playback, together with its actual wall-clock timing.
 *
 * <p>Captured by {@link de.bmarwell.proximo.pitido.spi.AbstractTimeAnnouncement#play(String)}
 * immediately before and after each call to {@link AudioPlayer#playBlocking(String)}.
 * In production the duration reflects real RTP streaming time.
 * In unit tests, where {@link de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer}
 * returns instantly, the duration is effectively zero.
 *
 * <p>The end instant is always {@code start.plus(duration)}.
 * Use {@link #end()} rather than computing it manually.
 *
 * @param start        wall-clock instant at which {@code playBlocking} was called
 * @param duration     wall-clock time elapsed until {@code playBlocking} returned; never negative
 * @param resourceName classpath-relative path that was passed to the audio player
 */
public record PlayedResource(Instant start, Duration duration, String resourceName) {

    /** Canonical constructor — guards against negative durations. */
    public PlayedResource {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative: " + duration);
        }
    }

    /**
     * Returns the instant at which playback of this resource finished.
     *
     * @return {@code start.plus(duration)}; never {@code null}
     */
    public Instant end() {
        return this.start.plus(this.duration);
    }
}
