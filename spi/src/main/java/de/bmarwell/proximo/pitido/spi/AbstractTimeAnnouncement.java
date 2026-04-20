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
 *
 * <h2>Implementing a new language module</h2>
 *
 * <p>The key steps for a new language implementation are:
 * <ol>
 *   <li>Bundle all audio clips as classpath resources under a dedicated package path.</li>
 *   <li>Implement {@link #audioBase()} to return that path (ending with {@code "/"}).</li>
 *   <li>Call {@link #play(String)} for each speech segment in the announcement.</li>
 *   <li>Use {@link #playSilenceUntil(java.time.Instant)} to fill timed gaps between strokes.</li>
 *   <li>Return {@link #buildReceipt()} from {@code announce()}.</li>
 * </ol>
 *
 * <h2>Timed gaps and the RTP jitter buffer</h2>
 *
 * <p><strong>Never use {@code Thread.sleep()} to create gaps between audio segments.</strong>
 * On a real SIP call, audio is carried by an RTP stream.
 * RTP receivers keep a small jitter buffer (typically 100–300 ms) to absorb network jitter.
 * When no RTP packets arrive for more than ~200 ms, the jitter buffer is flushed and the
 * next packet is played back immediately upon arrival.
 * This means a {@code Thread.sleep(1000)} between two audio files does not produce an audible
 * 1-second gap — all three stroke sounds collapse into a single burst.
 *
 * <p>Instead, use {@link #playSilenceUntil(java.time.Instant)} for any gap that must be
 * perceptible to the caller.
 * It delegates to {@link AudioPlayer#playSilence(Duration)}, which in production sends
 * zero-valued PCMA/PCMU packets at the normal 20 ms cadence, keeping the jitter buffer
 * active and rendering the gap as true audible silence.
 *
 * <p>The typical pattern for a three-stroke announcement where the final stroke must land
 * at an exact wall-clock instant {@code T}:
 * <pre>
 * Instant strokeTime = ...; // exact wall-clock instant for stroke 3
 *
 * play("announcement.opus");  // "At the third stroke …"
 * play("time_phrase.opus");   // "… twelve hours, ten minutes, twenty seconds …"
 * playSilenceUntil(strokeTime.minusSeconds(2));  // wait until T−2 s
 * play("stroke1.opus");
 * playSilenceUntil(strokeTime.minusSeconds(1));  // wait until T−1 s
 * play("stroke2.opus");
 * playSilenceUntil(strokeTime);                  // wait until T
 * play("stroke3.opus");
 * </pre>
 *
 * <p>The silence packets also advance the RTP timestamp correctly so that the receiving
 * side perceives the gaps at the right proportion of real time, even after a clock drift
 * or a late-arriving packet.
 *
 * <h2>Announcement timing</h2>
 *
 * <p>Choose {@code T} to be at least as far in the future as the total expected speech
 * duration plus a safety margin.
 * Prefer 10-second boundaries (:00, :10, :20, …) — they sound cleaner to the caller and
 * require no fractional-second audio files.
 * Fall back to 5-second boundaries only when the 10-second boundary would exceed the
 * maximum acceptable gap.
 *
 * <h2>Highly recommended: late-start speech placement</h2>
 *
 * <p>Do <em>not</em> begin speech immediately when {@code announce()} is entered.
 * Instead, wait with silence until {@code T − avgSpeechDuration − targetPreBeepSilence},
 * then play the speech, then let the remaining gap close naturally before the beep.
 *
 * <p>This places any "spare" silence <em>before</em> the speech (i.e., after the previous
 * beep), which callers perceive as a natural post-beep pause.
 * The alternative — starting speech immediately and waiting after — produces an awkward
 * silence between the last spoken word and the beep, which callers notice and dislike.
 *
 * <p>Recommended values:
 * <ul>
 *   <li>{@code avgSpeechDuration}: average speech duration across all announcement types
 *       for this language (e.g. 8 s for a four-file opus announcement).</li>
 *   <li>{@code targetPreBeepSilence}: 2 s (acceptable range: 1–3 s).</li>
 *   <li>{@code minLeadSeconds}: must exceed {@code avgSpeechDuration + targetPreBeepSilence}
 *       to guarantee a positive pre-speech silence in every case.</li>
 * </ul>
 *
 * <p>The outcome lands in the 1–3 s pre-beep silence range for roughly 90 % of
 * announcements; the remaining edge cases (unusually long or short phrases) may be
 * slightly outside the range, which is acceptable.
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
