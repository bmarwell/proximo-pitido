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
package de.bmarwell.proximo.pitido.languages.en.gb;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.spi.AbstractTimeAnnouncement;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * British-English time announcement in the traditional BT speaking-clock style.
 *
 * <p>Example phrases:
 * <ul>
 *   <li><em>"At the third stroke, the time from Próximo Pitido will be fourteen hours
 *       and thirty minutes and twenty seconds."</em></li>
 *   <li><em>"At the third stroke, the time from Próximo Pitido will be four hours
 *       exactly."</em> (full hours only)</li>
 *   <li><em>"At the third stroke, the time from Próximo Pitido will be midnight
 *       precisely."</em> (00:00:00 only)</li>
 * </ul>
 *
 * <p>The announcement time prefers the next 10-second boundary at least
 * {@value #MIN_LEAD_SECONDS} seconds away, falling back to the 5-second boundary when the
 * 10-second boundary would add more than {@value #MAX_GAP_SECONDS} seconds of total gap.
 * Ten-second announcements sound cleaner ("twenty seconds" vs "twenty-five seconds") and are
 * preferred wherever they fit.
 * The maximum total gap (speech + silence + two seconds to first stroke) is
 * {@value #MAX_GAP_SECONDS} seconds.
 *
 * <p>Stroke timing: the three strokes are anchored to the announced time T.
 * Stroke 1 fires at T − 2 s, stroke 2 at T − 1 s, and stroke 3 exactly at T.
 * Silence RTP packets are sent between each stroke via {@link #playSilenceUntil} so the
 * receiver's jitter buffer stays alive and renders the gaps as genuine 1-second silences.
 *
 * <p>Playback sequence (stitching rules):
 * <ol>
 *   <li>{@code announcement.opus} — "At the third stroke, the time from Próximo Pitido"</li>
 *   <li>Midnight (00:00:00): {@code midnight.opus} — "will be midnight precisely" — then stop.</li>
 *   <li>Otherwise: {@code NNN.opus} — "will be N hours" (000–023)</li>
 *   <li>
 *     <ul>
 *       <li>Exact hour (M=0, S=0): {@code 100_minutes.opus} — "exactly" — then stop.</li>
 *       <li>Hour+seconds (M=0, S≠0): {@code 2SS_seconds.opus} — "and N seconds" — skip minutes.</li>
 *       <li>Exact minute (M≠0, S=0): {@code 1MM_minutes.opus} — "and N minutes" — then stop.</li>
 *       <li>Normal (M≠0, S≠0): {@code 1MM_minutes.opus} then {@code 2SS_seconds.opus}.</li>
 *     </ul>
 *   </li>
 *   <li>Silence until T − 2 s, then play {@code stroke1.opus}</li>
 *   <li>Silence until T − 1 s, then play {@code stroke2.opus}</li>
 *   <li>Silence until T, then play {@code stroke3.opus}</li>
 * </ol>
 *
 * <p>{@code 200_seconds.opus} ("precisely") is never played; the seconds file is always omitted
 * when {@code second == 0}.
 *
 * <p>All resource paths are relative to the classpath root, inside this module's jar.
 */
public class BritishEnglishTimeAnnouncement extends AbstractTimeAnnouncement {

    private static final System.Logger LOG = System.getLogger(BritishEnglishTimeAnnouncement.class.getName());

    static final String AUDIO_BASE = "de/bmarwell/proximo/pitido/languages/en/gb/audio/en/";

    /**
     * Minimum seconds between "now" and the third stroke (T).
     * Stroke 1 fires at T − 2 s, so the effective minimum lead to stroke 1 is
     * {@code MIN_LEAD_SECONDS − 2 = 11} seconds.
     * The longest possible speech (announcement + hour + minute + second files) is
     * approximately 9.1 s of audio, plus per-file codec-initialisation overhead.
     * Setting this to 13 keeps at least 1.9 s of silence between speech end and stroke 1.
     */
    static final int MIN_LEAD_SECONDS = 13;

    /**
     * Maximum acceptable gap in seconds between "now" and the announced time (T).
     * Ten-second boundaries are preferred when they fit within this limit;
     * the 5-second fallback is used when they do not.
     * Must be at least {@code MIN_LEAD_SECONDS + 4} to accommodate the maximum 4-second
     * overshoot of a 5-second alignment step.
     */
    static final int MAX_GAP_SECONDS = 18;

    private final Clock clock;

    public BritishEnglishTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
        super(audioPlayer);
        this.clock = clock;
    }

    @Override
    protected String audioBase() {
        return AUDIO_BASE;
    }

    /**
     * Plays the British-English time announcement and returns a receipt of every file submitted for playback.
     *
     * @return receipt listing files in playback order; never {@code null}
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stops playback immediately and propagates
     */
    @Override
    public PlaybackReceipt announce() throws IOException, InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(this.clock);
        ZonedDateTime announcedTime = announcementTime(now);
        long gapSeconds = Duration.between(now, announcedTime).getSeconds();

        LOG.log(Level.TRACE, "Announced time: {0}, gap: {1}s", announcedTime, gapSeconds);

        play("announcement.opus");

        if (isMidnight(announcedTime)) {
            play("midnight.opus");
        } else {
            play(hourFile(announcedTime.getHour()));
            playTimeQualifier(announcedTime.getMinute(), announcedTime.getSecond());
        }

        long millisUntilFirstStroke =
                announcedTime.minus(Duration.ofSeconds(2)).toInstant().toEpochMilli()
                        - Instant.now().toEpochMilli();
        LOG.log(Level.TRACE, "Speech complete; {0}ms remaining before stroke 1 (T-2s)", millisUntilFirstStroke);

        playSilenceUntil(announcedTime.minus(Duration.ofSeconds(2)).toInstant());
        LOG.log(Level.TRACE, "Playing stroke 1 at {0}", Instant.now());
        play("stroke1.opus");

        playSilenceUntil(announcedTime.minus(Duration.ofSeconds(1)).toInstant());
        LOG.log(Level.TRACE, "Playing stroke 2 at {0}", Instant.now());
        play("stroke2.opus");

        playSilenceUntil(announcedTime.toInstant());
        LOG.log(Level.TRACE, "Playing stroke 3 at {0}", Instant.now());
        play("stroke3.opus");

        PlaybackReceipt receipt = buildReceipt();
        LOG.log(Level.DEBUG, "Announcement complete:\n{0}", receipt);

        return receipt;
    }

    /**
     * Returns {@code true} when the announced time is exactly midnight (00:00:00).
     * Midnight has its own dedicated phrase and skips the normal hour/qualifier logic.
     */
    private static boolean isMidnight(ZonedDateTime time) {
        return time.getHour() == 0 && time.getMinute() == 0 && time.getSecond() == 0;
    }

    /**
     * Plays minute and/or second qualifier files according to the new stitching rules.
     *
     * <ul>
     *   <li>M=0, S=0 (exact hour): {@code 100_minutes.opus} ("exactly") — stop.</li>
     *   <li>M=0, S≠0 (hour + seconds only): {@code 2SS_seconds.opus} — skip minutes.</li>
     *   <li>M≠0, S=0 (exact minute): {@code 1MM_minutes.opus} — stop.</li>
     *   <li>M≠0, S≠0 (normal): {@code 1MM_minutes.opus} then {@code 2SS_seconds.opus}.</li>
     * </ul>
     *
     * <p>{@code 200_seconds.opus} ("precisely") is never played; seconds are always omitted when
     * {@code second == 0}.
     */
    private void playTimeQualifier(int minute, int second) throws IOException, InterruptedException {
        if (minute == 0 && second == 0) {
            play(minuteFile(0));
            return;
        }

        if (minute == 0) {
            play(secondFile(second));
            return;
        }

        play(minuteFile(minute));

        if (second != 0) {
            play(secondFile(second));
        }
    }

    /**
     * Calculates the announcement time: the next 10-second boundary (preferred) or 5-second
     * boundary (fallback) at least {@value #MIN_LEAD_SECONDS} seconds in the future.
     *
     * <p>A 10-second boundary is chosen when the resulting gap is at most
     * {@value #MAX_GAP_SECONDS} seconds.
     * This yields clean second values (:00, :10, :20, :30, :40, :50) wherever possible.
     * When the 10-second boundary would exceed {@value #MAX_GAP_SECONDS} seconds, the
     * 5-second fallback is used, producing at most a {@value #MAX_GAP_SECONDS}-second gap.
     *
     * @param now the current time
     * @return the time at which the third stroke will sound; second is always a multiple of 5
     */
    static ZonedDateTime announcementTime(ZonedDateTime now) {
        ZonedDateTime earliest = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(MIN_LEAD_SECONDS);
        ZonedDateTime aligned10 = alignUp(earliest, 10);
        long gap10Ms = Duration.between(now, aligned10).toMillis();

        if (gap10Ms <= MAX_GAP_SECONDS * 1000L) {
            return aligned10;
        }

        return alignUp(earliest, 5);
    }

    /** Rounds {@code dt} up to the next multiple of {@code stepSeconds} within the minute. */
    private static ZonedDateTime alignUp(ZonedDateTime dt, int stepSeconds) {
        int remainder = dt.getSecond() % stepSeconds;

        if (remainder == 0) {
            return dt;
        }

        return dt.plusSeconds(stepSeconds - remainder);
    }

    static String hourFile(int hour) {
        return String.format(Locale.ROOT, "%03d.opus", hour);
    }

    static String minuteFile(int minute) {
        return (100 + minute) + "_minutes.opus";
    }

    static String secondFile(int second) {
        return (200 + second) + "_seconds.opus";
    }
}
