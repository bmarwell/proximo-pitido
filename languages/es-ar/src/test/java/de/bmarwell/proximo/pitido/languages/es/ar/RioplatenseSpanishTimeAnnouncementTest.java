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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.bmarwell.proximo.pitido.api.PlaybackReceipt;
import de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class RioplatenseSpanishTimeAnnouncementTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");

    /**
     * At 14:30:08 Buenos Aires time, now+12=14:30:20, 10s boundary=14:30:20 (gap=12≤16).
     * M=30, S=20, hour=14 (plural) → announcement_next, 014, 130_minutos, 220_segundos, stroke3.
     */
    @Test
    void announcePlaysCorrectFilesAt14h30m08s() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 14, 30, 8, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(5, receipt.fileNames().size(), "Expected 5 audio files");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement_next.opus"), "plural announcement");
        assertTrue(receipt.fileNames().get(1).endsWith("014.opus"), "hour 14");
        assertTrue(receipt.fileNames().get(2).endsWith("130_minutos.opus"), "30 minutos");
        assertTrue(receipt.fileNames().get(3).endsWith("220_segundos.opus"), "20 segundos");
        assertTrue(receipt.fileNames().get(4).endsWith("stroke3.opus"));
    }

    /**
     * At 03:59:48, now+12=04:00:00, 10s boundary=04:00:00 (gap=12≤16).
     * M=0, S=0 (exact hour, plural) → announcement_next, 004, 100_minutos (en punto), stroke3.
     */
    @Test
    void announceExactHourPluralPlaysEnPunto() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 3, 59, 48, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(4, receipt.fileNames().size(), "Expected 4 files for exact hour");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement_next.opus"), "plural announcement");
        assertTrue(receipt.fileNames().get(1).endsWith("004.opus"), "hour 4");
        assertTrue(receipt.fileNames().get(2).endsWith("100_minutos.opus"), "en punto");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke3.opus"));
    }

    /**
     * At 00:59:48, now+12=01:00:00, 10s boundary=01:00:00 (gap=12≤16).
     * Hour=1, M=0, S=0 (exact hour, singular) → announcement_next_singular, 001, 100_minutos, stroke3.
     */
    @Test
    void announceExactHourSingularUsesCorrectAnnouncement() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 0, 59, 48, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(4, receipt.fileNames().size(), "Expected 4 files for singular exact hour");
        assertTrue(receipt.fileNames().get(0).endsWith("announcement_next_singular.opus"), "singular announcement");
        assertTrue(receipt.fileNames().get(1).endsWith("001.opus"), "hour 1");
        assertTrue(receipt.fileNames().get(2).endsWith("100_minutos.opus"), "en punto");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke3.opus"));
    }

    /**
     * At 12:00:08, now+12=12:00:20, 10s boundary=12:00:20 (gap=12≤16).
     * M=0, S=20 (hour + seconds only) → announcement_next, 012, 220_segundos, stroke3.
     */
    @Test
    void announceHourPlusSecondsSkipsMinutes() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 12, 0, 8, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(4, receipt.fileNames().size(), "Expected 4 files — minutes skipped when M=0, S!=0");
        assertTrue(receipt.fileNames().get(1).endsWith("012.opus"), "hour 12");
        assertTrue(receipt.fileNames().get(2).endsWith("220_segundos.opus"), "20 segundos (10s boundary fits)");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke3.opus"));
    }

    /**
     * At 09:30:48, now+12=09:31:00, 10s boundary=09:31:00 (gap=12≤16).
     * M=31, S=0 (exact minute) → announcement_next, 009, 131_minutos, stroke3.
     */
    @Test
    void announceExactMinuteOmitsSeconds() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 9, 30, 48, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(4, receipt.fileNames().size(), "Expected 4 files — seconds omitted when S=0");
        assertTrue(receipt.fileNames().get(2).endsWith("131_minutos.opus"), "31 minutos");
        assertTrue(receipt.fileNames().get(3).endsWith("stroke3.opus"));
    }

    /**
     * At 23:59:47, now+12=23:59:59, 10s boundary=00:00:00 (gap=13≤16) → midnight.
     * Midnight is standalone: midnight_next.opus only (no announcement header or hour), then beep.
     * Expected: midnight_next, stroke3 (2 files).
     */
    @Test
    void announceMidnightPlaysMidnightFileStandalone() throws Exception {
        // given
        Instant fixedInstant =
                ZonedDateTime.of(2024, 6, 1, 23, 59, 47, 0, BUENOS_AIRES).toInstant();
        Clock clock = Clock.fixed(fixedInstant, BUENOS_AIRES);
        var player = new RecordingAudioPlayer();
        var announcement = new RioplatenseSpanishTimeAnnouncement(player, clock);

        // when
        PlaybackReceipt receipt = announcement.announce();

        // then
        assertEquals(
                2, receipt.fileNames().size(), "Expected 2 files for midnight (standalone, no announcement header)");
        assertTrue(receipt.fileNames().get(0).endsWith("midnight_next.opus"), "midnight phrase standalone");
        assertTrue(receipt.fileNames().get(1).endsWith("stroke3.opus"));
    }
}
