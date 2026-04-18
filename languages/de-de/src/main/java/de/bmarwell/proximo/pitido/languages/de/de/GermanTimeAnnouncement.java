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
package de.bmarwell.proximo.pitido.languages.de.de;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * German time announcement: <em>"Beim nächsten Ton ist es … Uhr … Minuten und … Sekunden."</em>
 *
 * <p>The announcement time is calculated as the current time plus seven seconds,
 * rounded up to the next ten-second boundary.
 * This mirrors the logic of the original Python {@code zeitansage} script — which used
 * {@code vlc} for audio playback and therefore worked exclusively on workstations with a
 * display server, a sound card, and a live user sitting in front of them.
 * Servers are headless. 🐍🔇
 *
 * <p>Playback sequence:
 * <ol>
 *   <li>{@code announcement.wav} — "Beim nächsten Ton ist es …"</li>
 *   <li>{@code NNN.wav} — the hour (zero-padded to three digits, 000–023)</li>
 *   <li>{@code 1MM_Minuten.wav} — the minute (100–159 where MM is 00–59)</li>
 *   <li>{@code 2S0_Sekunden.wav} — the tens of seconds (200–250 where S is 0–5)</li>
 *   <li>Wait until the announcement time has been reached</li>
 *   <li>{@code signal.wav} — the beep</li>
 * </ol>
 *
 * <p>All resource paths are relative to the classpath root, inside this module's jar.
 */
public class GermanTimeAnnouncement implements TimeAnnouncement {

    static final String AUDIO_BASE = "de/bmarwell/proximo/pitido/languages/de/de/audio/de/";

    private final AudioPlayer audioPlayer;
    private final Clock clock;

    public GermanTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
        this.audioPlayer = audioPlayer;
        this.clock = clock;
    }

    /**
     * Plays the German time announcement and returns a receipt of every file submitted for playback.
     *
     * @return receipt listing files in playback order; never {@code null}
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stops playback immediately and propagates
     */
    @Override
    public PlaybackReceipt announce() throws IOException, InterruptedException {
        ZonedDateTime now = ZonedDateTime.now(this.clock);
        ZonedDateTime announcementTime = announcementTime(now);

        List<String> played = new ArrayList<>();

        play(played, "announcement.wav");
        play(played, hourFile(announcementTime.getHour()));
        play(played, minuteFile(announcementTime.getMinute()));
        play(played, secondFile(announcementTime.getSecond()));

        waitUntil(announcementTime);

        play(played, "signal.wav");

        return new PlaybackReceipt(played);
    }

    /**
     * Calculates the announcement time: the current time plus seven seconds, rounded up to
     * the next ten-second boundary.
     *
     * <p>Sub-second precision is discarded before the seven-second delta is applied,
     * matching the Python reference implementation.
     *
     * @param now the current time
     * @return the time at which the signal tone will sound; always a multiple of ten seconds
     */
    static ZonedDateTime announcementTime(ZonedDateTime now) {
        ZonedDateTime withDelta = now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(7);
        int remainder = withDelta.getSecond() % 10;
        int toAdd;
        if (remainder == 0) {
            toAdd = 0;
        } else {
            toAdd = (10 - remainder);
        }

        return withDelta.plusSeconds(toAdd);
    }

    private void play(List<String> played, String fileName) throws IOException, InterruptedException {
        String path = AUDIO_BASE + fileName;
        this.audioPlayer.playBlocking(path);
        played.add(path);
    }

    private void waitUntil(ZonedDateTime target) throws InterruptedException {
        long millis = target.toInstant().toEpochMilli() - this.clock.instant().toEpochMilli();

        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    static String hourFile(int hour) {
        return String.format("%03d.wav", hour);
    }

    static String minuteFile(int minute) {
        return (100 + minute) + "_Minuten.wav";
    }

    static String secondFile(int second) {
        int tens = (second / 10) * 10;

        return (200 + tens) + "_Sekunden.wav";
    }
}
