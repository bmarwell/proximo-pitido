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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class GermanTimeAnnouncementTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /**
     * At 14:30:03 Berlin time, the announcement time is:
     * 14:30:03 + 7 s = 14:30:10, which is already a 10-second boundary → 14:30:10.
     * Expected files: announcement, 014.wav (14 h), 130_Minuten (30 min), 210_Sekunden (10 s), signal.
     */
    @Test
    void announcePlaysCorrectFilesAt14h30m03s() throws Exception {
        Instant fixedInstant =
                ZonedDateTime.of(2024, 1, 15, 14, 30, 3, 0, BERLIN).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BERLIN);
        var player = new RecordingAudioPlayer();
        var announcement = new GermanTimeAnnouncement(player, clock);

        PlaybackReceipt receipt = announcement.announce();

        assertEquals(5, receipt.fileNames().size(), "Expected 5 audio files");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement.wav"));
        assertTrue(receipt.fileNames().get(1).endsWith("014.wav"), "hour 14");
        assertTrue(receipt.fileNames().get(2).endsWith("130_Minuten.wav"), "30 minutes");
        assertTrue(receipt.fileNames().get(3).endsWith("210_Sekunden.wav"), "10 seconds");
        assertTrue(receipt.fileNames().get(4).endsWith("signal.wav"));
    }

    /**
     * At 23:59:55 Berlin time, the announcement time is:
     * 23:59:55 + 7 s = 00:00:02 (next day), rounded up → 00:00:10.
     * Expected files: announcement, 000.wav (0 h), 100_Minuten (0 min), 210_Sekunden (10 s), signal.
     */
    @Test
    void announcementTimeWrapsAtMidnight() {
        ZonedDateTime at235955 = ZonedDateTime.of(2024, 1, 15, 23, 59, 55, 0, BERLIN);
        ZonedDateTime expected = ZonedDateTime.of(2024, 1, 16, 0, 0, 10, 0, BERLIN);

        assertEquals(expected, GermanTimeAnnouncement.announcementTime(at235955));
    }

    /** Verifies that announcementTime always lands on a 10-second boundary. */
    @Test
    void announcementTimeIsAlwaysOnTenSecondBoundary() {
        for (int sec = 0; sec < 60; sec++) {
            ZonedDateTime now = ZonedDateTime.of(2024, 6, 1, 12, 0, sec, 0, BERLIN);
            ZonedDateTime at = GermanTimeAnnouncement.announcementTime(now);

            assertEquals(0, at.getSecond() % 10, "second must be a multiple of 10 for input second=" + sec);
        }
    }

    /** Announcement must stop immediately when the thread is interrupted. */
    @Test
    void announceStopsOnInterrupt() throws Exception {
        // Use a far-future clock so waitUntil() would block indefinitely without interrupt.
        Instant fixedInstant =
                ZonedDateTime.of(2024, 1, 15, 14, 30, 3, 0, BERLIN).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BERLIN);
        var player = new RecordingAudioPlayer();
        var announcement = new GermanTimeAnnouncement(player, clock);

        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                announcement.announce();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // ignored
            }
        });

        // Allow the announcement to start, then interrupt it during waitUntil().
        Thread.sleep(50);
        thread.interrupt();
        thread.join(500);

        assertFalse(thread.isAlive(), "Announcement thread must stop after interrupt");
    }
}
