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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class BritishEnglishTimeAnnouncementTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * At 14:30:03 London time, now+13=14:30:16, 10s boundary=14:30:20 (gap=17≤18) → announced time = 14:30:20.
     * Expected: announcement, 014.opus, 130_minutes.opus, 220_seconds.opus, stroke1, stroke2, stroke3.
     */
    @Test
    void announcePlaysCorrectFilesAt14h30m03s() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 14, 30, 3, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(7, receipt.fileNames().size(), "Expected 7 audio files");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement.opus"));
        assertTrue(receipt.fileNames().get(1).endsWith("014.opus"), "hour 14");
        assertTrue(receipt.fileNames().get(2).endsWith("130_minutes.opus"), "30 minutes");
        assertTrue(receipt.fileNames().get(3).endsWith("220_seconds.opus"), "20 seconds (tens=20)");
        assertTrue(receipt.fileNames().get(4).endsWith("stroke1.opus"));
        assertTrue(receipt.fileNames().get(5).endsWith("stroke2.opus"));
        assertTrue(receipt.fileNames().get(6).endsWith("stroke3.opus"));
    }

    /**
     * At 03:59:44, now+13=03:59:57 → 10s boundary=04:00:00 (gap=16≤18).
     * M=0, S=0 (exact hour) → plays "exactly" (100_minutes.opus), no seconds file.
     * Expected: announcement, 004.opus, 100_minutes.opus, stroke1, stroke2, stroke3 (6 files).
     */
    @Test
    void announceExactHourPlaysExactly() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 3, 59, 44, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(6, receipt.fileNames().size(), "Expected 6 audio files for exact hour");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement.opus"));
        assertTrue(receipt.fileNames().get(1).endsWith("004.opus"), "hour 4");
        assertTrue(receipt.fileNames().get(2).endsWith("100_minutes.opus"), "exactly");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke1.opus"));
        assertTrue(receipt.fileNames().get(4).endsWith("stroke2.opus"));
        assertTrue(receipt.fileNames().get(5).endsWith("stroke3.opus"));
    }

    /**
     * At 12:15:03, now+13=12:15:16, 10s boundary=12:15:20 (gap=17≤18) → announced time = 12:15:20.
     * Seconds are non-zero — minute and second files both present.
     */
    @Test
    void announceIncludesSecondsWhenNonZero() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 12, 15, 3, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertTrue(receipt.fileNames().get(2).endsWith("115_minutes.opus"), "15 minutes");
        assertTrue(receipt.fileNames().get(3).endsWith("220_seconds.opus"), "20 seconds (tens=20)");
    }

    /**
     * At 09:30:44, now+13=09:30:57 → 10s boundary=09:31:00 (gap=16≤18).
     * M=31, S=0 (exact minute) → plays minute file only; seconds file omitted.
     * Expected: announcement, 009.opus, 131_minutes.opus, stroke1, stroke2, stroke3 (6 files).
     */
    @Test
    void announceExactMinuteOmitsSeconds() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 9, 30, 44, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(6, receipt.fileNames().size(), "Expected 6 files — seconds omitted when S=0");
        assertTrue(receipt.fileNames().get(2).endsWith("131_minutes.opus"), "31 minutes");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke1.opus"));
    }

    /**
     * At 12:00:08, now+13=12:00:21, 10s boundary=12:00:30 (gap=22>18) → 5s fallback = 12:00:25.
     * M=0, S=25 (hour+seconds) → skips minutes file.
     * Expected: announcement, 012.opus, 225_seconds.opus, stroke1, stroke2, stroke3 (6 files).
     */
    @Test
    void announcePicksFiveSecondBoundaryAt12h00m08s() throws Exception {
        // given
        Instant fixedInstant = ZonedDateTime.of(2024, 6, 1, 12, 0, 8, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(6, receipt.fileNames().size(), "Expected 6 files — minutes skipped when M=0");
        assertTrue(receipt.fileNames().get(1).endsWith("012.opus"), "hour 12");
        assertTrue(receipt.fileNames().get(2).endsWith("225_seconds.opus"), "25 seconds");
    }

    /** Announced time is always on a 5-second boundary (audio files exist for every 5s value). */
    @Test
    void announcementTimeIsAlwaysOnFiveSecondBoundary() {
        // given / when / then
        for (int sec = 0; sec < 60; sec++) {
            ZonedDateTime now = ZonedDateTime.of(2024, 6, 1, 12, 0, sec, 0, LONDON);
            ZonedDateTime at = BritishEnglishTimeAnnouncement.announcementTime(now);

            assertEquals(0, at.getSecond() % 5, "second must be multiple of 5 for input=" + sec);
        }
    }

    /** Gap between now and announced time is always within [MIN_LEAD_SECONDS, MAX_GAP_SECONDS] seconds. */
    @Test
    void announcementTimeGapIsWithinAcceptableBounds() {
        // given / when / then
        for (int sec = 0; sec < 60; sec++) {
            ZonedDateTime now = ZonedDateTime.of(2024, 6, 1, 12, 0, sec, 0, LONDON);
            ZonedDateTime at = BritishEnglishTimeAnnouncement.announcementTime(now);
            long gap = Duration.between(now, at).getSeconds();

            assertTrue(
                    gap >= BritishEnglishTimeAnnouncement.MIN_LEAD_SECONDS,
                    "gap " + gap + "s too short for input sec=" + sec);
            assertTrue(
                    gap <= BritishEnglishTimeAnnouncement.MAX_GAP_SECONDS,
                    "gap " + gap + "s too long for input sec=" + sec);
        }
    }

    /**
     * At 12:00:02, now+13=12:00:15, 10s boundary=12:00:20 (gap=18≤18) → prefers :20 over :15.
     * M=0, S=20 (hour+seconds) → skips minutes file.
     * Expected: announcement, 012.opus, 220_seconds.opus, stroke1, stroke2, stroke3 (6 files).
     */
    @Test
    void announcePrefersTenSecondBoundaryOverFiveSecond() throws Exception {
        // given
        Instant fixedInstant = ZonedDateTime.of(2024, 6, 1, 12, 0, 2, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(6, receipt.fileNames().size(), "Expected 6 files — minutes skipped when M=0");
        assertTrue(receipt.fileNames().get(1).endsWith("012.opus"), "hour 12");
        assertTrue(
                receipt.fileNames().get(2).endsWith("220_seconds.opus"),
                "20 seconds (10s boundary preferred over :15)");
    }

    /**
     * At 23:59:47, now+13=00:00:00 → announced time is midnight.
     * Midnight uses a single dedicated phrase; hours, minutes, and seconds are omitted.
     * Expected: announcement, midnight.opus, stroke1, stroke2, stroke3 (5 files).
     */
    @Test
    void announceMidnightPlaysMidnightFile() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 23, 59, 47, 0, LONDON).toInstant();
        Clock clock = Clock.fixed(fixedInstant, LONDON);
        var player = new RecordingAudioPlayer();
        var announcement = new BritishEnglishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(5, receipt.fileNames().size(), "Expected 5 files for midnight");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement.opus"));
        assertTrue(receipt.fileNames().get(1).endsWith("midnight.opus"), "midnight phrase");
        assertTrue(receipt.fileNames().get(2).endsWith("stroke1.opus"));
        assertTrue(receipt.fileNames().get(3).endsWith("stroke2.opus"));
        assertTrue(receipt.fileNames().get(4).endsWith("stroke3.opus"));
    }
}
