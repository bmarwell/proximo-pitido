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
package de.bmarwell.proximo.pitido.spi;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.api.PlayedResource;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for {@link TimeAnnouncement} implementations that play audio files from the classpath.
 *
 * <p>Owns the running list of {@link PlayedResource} entries for the current announcement.
 * Subclasses call {@link #play(String)} for each file and {@link #buildReceipt()} at the end of
 * {@code announce()}.
 * The base class wraps each {@link AudioPlayer#playBlocking(String)} call with wall-clock
 * timestamps, so the receipt carries actual start instants and durations with no extra code
 * in the subclass.
 *
 * <p>Subclasses must supply an {@link #audioBase()} that is the classpath prefix for their
 * audio resources, e.g. {@code "de/bmarwell/proximo/pitido/languages/de/de/audio/de/"}.
 *
 * <p>Each instance is intended for a single {@code announce()} call.
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} creates a fresh instance per call.
 */
public abstract class AbstractTimeAnnouncement implements TimeAnnouncement {

    /** The audio player to use for all playback operations. */
    protected final AudioPlayer audioPlayer;

    private final List<PlayedResource> played = new ArrayList<>();

    /**
     * Constructs an announcement backed by the given player.
     *
     * @param audioPlayer the player to use; must not be {@code null}
     */
    protected AbstractTimeAnnouncement(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Returns the classpath prefix for all audio resources used by this implementation.
     *
     * <p>The returned string must end with {@code "/"}.
     * For example: {@code "de/bmarwell/proximo/pitido/languages/de/de/audio/de/"}.
     */
    protected abstract String audioBase();

    /**
     * Plays the resource at {@code audioBase() + fileName}, records its wall-clock start and
     * duration, and stores a {@link PlayedResource} entry for the receipt.
     *
     * @param fileName the file name within {@link #audioBase()}, e.g. {@code "signal.wav"}
     * @throws IOException          on any I/O or streaming error
     * @throws InterruptedException if the calling thread is interrupted; stops immediately
     */
    protected void play(String fileName) throws IOException, InterruptedException {
        String path = audioBase() + fileName;
        Instant start = Instant.now();
        this.audioPlayer.playBlocking(path);
        Duration duration = Duration.between(start, Instant.now());
        this.played.add(new PlayedResource(start, duration, path));
    }

    /**
     * Builds and returns the playback receipt for this announcement.
     *
     * <p>Call once at the end of {@code announce()}, after all {@link #play(String)} calls.
     *
     * @return immutable receipt of every resource played so far; never {@code null}
     */
    protected PlaybackReceipt buildReceipt() {
        return new PlaybackReceipt(this.played);
    }

    /**
     * Sends silence to the caller until {@code target}, keeping the RTP stream alive.
     *
     * <p>Delegates to {@link AudioPlayer#playSilence(Duration)}.
     * If {@code target} is already in the past, this is a no-op.
     *
     * @param target the instant at which silence should end
     * @throws InterruptedException if the calling thread is interrupted
     */
    protected void playSilenceUntil(Instant target) throws InterruptedException {
        long millis = target.toEpochMilli() - Instant.now().toEpochMilli();

        if (millis > 0) {
            this.audioPlayer.playSilence(Duration.ofMillis(millis));
        }
    }
}
