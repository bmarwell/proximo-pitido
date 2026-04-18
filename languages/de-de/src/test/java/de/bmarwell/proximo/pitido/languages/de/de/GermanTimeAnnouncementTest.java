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

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class GermanTimeAnnouncementTest {

    @Test
    void announcePlaysBeepResource() throws Exception {
        var player = mock(AudioPlayer.class);
        var announcement = new GermanTimeAnnouncement(player, Clock.systemDefaultZone());

        var thread = Thread.ofVirtual().start(() -> {
            try {
                announcement.announce();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(50);
        thread.interrupt();
        thread.join(500);

        verify(player, atLeastOnce()).playBlocking(GermanTimeAnnouncement.BEEP_RESOURCE);
    }

    @Test
    void announceStopsOnInterrupt() throws Exception {
        var player = mock(AudioPlayer.class);
        var announcement = new GermanTimeAnnouncement(player, Clock.systemDefaultZone());

        var thread = Thread.ofVirtual().start(() -> {
            try {
                announcement.announce();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(50);
        thread.interrupt();
        thread.join(500);

        // Thread must have terminated (not hung).
        assert !thread.isAlive() : "Announcement thread did not stop after interrupt";
    }
}
