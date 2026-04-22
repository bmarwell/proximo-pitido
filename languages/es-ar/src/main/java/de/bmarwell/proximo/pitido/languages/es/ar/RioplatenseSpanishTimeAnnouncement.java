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
package de.bmarwell.proximo.pitido.languages.es.ar;

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
 * Rioplatense Spanish (es-AR) time announcement in the Buenos Aires / River Plate colloquial style.
 *
 * <p>Example phrases:
 * <ul>
 *   <li><em>"Al próximo pitido van a ser las catorce horas y treinta minutos con veinte
 *       segundos."</em> (normal case)</li>
 *   <li><em>"Al próximo pitido va a ser la una hora en punto."</em> (singular, exact hour)</li>
 *   <li><em>"va a ser medianoche"</em> (midnight, standalone — no announcement before it)</li>
 * </ul>
 *
 * <p>Singular vs plural: hour 1 triggers the singular announcement variant
 * ({@code announcement_next_singular.opus}, "va a ser la"); all other hours use the plural
 * variant ({@code announcement_next.opus}, "van a ser las").
 *
 * <p>Stitching rules:
 * <ol>
 *   <li>Midnight (00:00:00): {@code midnight_next.opus} standalone — skip announcement and hour.</li>
 *   <li>Otherwise: {@code announcement_next[_singular].opus} — "Al próximo pitido van/va a ser
 *       las/la"</li>
 *   <li>{@code NNN.opus} — hour number + "horas" (000–023)</li>
 *   <li>
 *     <ul>
 *       <li>Exact hour (M=0, S=0): {@code 100_minutos.opus} ("en punto") — stop.</li>
 *       <li>Hour+seconds (M=0, S≠0): {@code 2SS_segundos.opus} — skip minutes.</li>
 *       <li>Exact minute (M≠0, S=0): {@code 1MM_minutos.opus} — stop.</li>
 *       <li>Normal (M≠0, S≠0): {@code 1MM_minutos.opus} then {@code 2SS_segundos.opus}.</li>
 *     </ul>
 *   </li>
 *   <li>Silence until T, then play {@code stroke3.opus} (the single beep)</li>
 * </ol>
 *
 * <p>{@code 200_segundos.opus} (S=0) is never played; seconds are always omitted when
 * {@code second == 0}.
 *
 * <p>The announcement time prefers the next 10-second boundary at least
 * {@value #MIN_LEAD_SECONDS} seconds away, falling back to the 5-second boundary when the
 * 10-second boundary would exceed {@value #MAX_GAP_SECONDS} seconds.
 *
 * <p>Speech starts at approximately {@code T − }{@value #AVERAGE_SPEECH_SECONDS}{@code  −
 * }{@value #TARGET_SILENCE_BEFORE_BEEP}{@code  seconds}, so the beep lands about
 * {@value #TARGET_SILENCE_BEFORE_BEEP} seconds after the last syllable.
 * Any remaining time between call start and speech start is silence placed
 * <em>before</em> the announcement (i.e., after the previous beep), which callers
 * perceive as natural post-beep pause rather than an awkward gap between speech and signal.
 *
 * <p>All resource paths are relative to the classpath root, inside this module's jar.
 */
public class RioplatenseSpanishTimeAnnouncement extends AbstractTimeAnnouncement {

    private static final System.Logger LOG = System.getLogger(RioplatenseSpanishTimeAnnouncement.class.getName());

    static final String AUDIO_BASE = "de/bmarwell/proximo/pitido/languages/es/ar/audio/es-ar/";

    /**
     * Minimum seconds between "now" and the beep (T).
     * Must exceed {@link #AVERAGE_SPEECH_SECONDS} + {@link #TARGET_SILENCE_BEFORE_BEEP}
     * to guarantee a positive pre-speech silence (post-beep pause from the caller's view).
     * Set to 12 to leave at least 2 s of post-beep silence in all cases.
     */
    static final int MIN_LEAD_SECONDS = 12;

    /**
     * Maximum acceptable gap in seconds between "now" and the announced time (T).
     * Ten-second boundaries are preferred when they fit within this limit;
     * the 5-second fallback is used when they do not.
     */
    static final int MAX_GAP_SECONDS = 16;

    /**
     * Average speech duration in seconds across all announcement types.
     * The modal case (4 audio files: announcement + hour + minute + seconds) averages ~8.2 s.
     * Used together with {@link #TARGET_SILENCE_BEFORE_BEEP} to calculate the speech start instant.
     */
    static final int AVERAGE_SPEECH_SECONDS = 8;

    /**
     * Target silence in seconds between the end of speech and the beep.
     * Combined with {@link #AVERAGE_SPEECH_SECONDS}, speech starts at
     * {@code T − AVERAGE_SPEECH_SECONDS − TARGET_SILENCE_BEFORE_BEEP}.
     * Outcomes are in the 1–3 s range for roughly 90 % of announcements.
     */
    static final int TARGET_SILENCE_BEFORE_BEEP = 2;

    private final Clock clock;

    public RioplatenseSpanishTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
        super(audioPlayer);
        this.clock = clock;
    }

    @Override
    protected String audioBase() {
        return AUDIO_BASE;
    }

    /**
     * Plays the Rioplatense Spanish time announcement and returns a receipt of every file
     * submitted for playback.
     *
     * @return receipt listing files in playback order; never {@code null}
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stops playback immediately and propagates
     */
    @Override
    public PlaybackReceipt announce() throws IOException, InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(this.clock);
        ZonedDateTime announcedTime = announcementTime(now);

        LOG.log(Level.TRACE, "Announced time: {0}", announcedTime);

        Instant speechStart =
                announcedTime.toInstant().minusSeconds((long) AVERAGE_SPEECH_SECONDS + TARGET_SILENCE_BEFORE_BEEP);
        playSilenceUntil(speechStart);

        if (isMidnight(announcedTime)) {
            play("midnight_next.opus");
        } else {
            int hour = announcedTime.getHour();
            play(hour == 1 ? "announcement_next_singular.opus" : "announcement_next.opus");
            play(hourFile(hour));
            playTimeQualifier(announcedTime.getMinute(), announcedTime.getSecond());
        }

        long millisUntilBeep =
                announcedTime.toInstant().toEpochMilli() - Instant.now().toEpochMilli();
        LOG.log(Level.TRACE, "Speech complete; {0}ms remaining before beep at T", millisUntilBeep);

        playSilenceUntil(announcedTime.toInstant());
        LOG.log(Level.TRACE, "Playing beep at {0}", Instant.now());
        play("stroke3.opus");

        PlaybackReceipt receipt = buildReceipt();
        LOG.log(Level.DEBUG, "Announcement complete:\n{0}", receipt);

        return receipt;
    }

    /** Returns {@code true} when the announced time is exactly midnight (00:00:00). */
    private static boolean isMidnight(ZonedDateTime time) {
        return time.getHour() == 0 && time.getMinute() == 0 && time.getSecond() == 0;
    }

    /**
     * Plays minute and/or second qualifier files.
     *
     * <ul>
     *   <li>M=0, S=0 (exact hour): {@code 100_minutos.opus} ("en punto") — stop.</li>
     *   <li>M=0, S≠0 (hour + seconds only): {@code 2SS_segundos.opus} — skip minutes.</li>
     *   <li>M≠0, S=0 (exact minute): {@code 1MM_minutos.opus} — stop.</li>
     *   <li>M≠0, S≠0 (normal): {@code 1MM_minutos.opus} then {@code 2SS_segundos.opus}.</li>
     * </ul>
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
     * <p>Speech begins at {@code T − }{@value #AVERAGE_SPEECH_SECONDS}{@code  −
     * }{@value #TARGET_SILENCE_BEFORE_BEEP}{@code  seconds}; any gap between "now" and
     * that instant becomes post-beep silence from the caller's perspective.
     *
     * @param now the current time
     * @return the announced time; second is always a multiple of 5
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
        return (100 + minute) + "_minutos.opus";
    }

    static String secondFile(int second) {
        return (200 + second) + "_segundos.opus";
    }
}
