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

import java.io.IOException;

/**
 * Plays the current time announcement for one language.
 *
 * <p>Implementations obtain the current time from the {@link java.time.Clock} that is
 * passed to them at construction time (via
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory#createTimeAnnouncement}).
 * They must not call {@code ZonedDateTime.now()} or {@code LocalTime.now()} without a clock
 * argument, as that makes the time non-deterministic and untestable.
 *
 * <p>For production use, pass {@link java.time.Clock#systemDefaultZone()} (or a zone-aware
 * clock such as {@code Clock.system(ZoneId.of("Europe/Berlin"))}) to the factory.
 * For tests, pass {@link java.time.Clock#fixed(java.time.Instant, java.time.ZoneId)} to pin
 * the announcement to a specific moment in time.
 */
public interface TimeAnnouncement {

    /**
     * Plays the time announcement to completion, or until the calling thread is interrupted.
     *
     * <p>Returns a {@link PlaybackReceipt} listing every resource path submitted to the
     * {@link AudioPlayer} in playback order.
     * The receipt is intended for testing on headless servers where audio hardware is absent.
     *
     * @return receipt of the audio files played, in order; never {@code null}
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stop playback and propagate
     */
    PlaybackReceipt announce() throws IOException, InterruptedException;
}
