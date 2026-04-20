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

import de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class GermanLanguageSelectionAnnouncementTest {

    @Test
    void playSelectionPhrase_slot1_playsMenu1Opus() throws IOException, InterruptedException {
        // given
        var player = new RecordingAudioPlayer();
        var announcement = new GermanLanguageSelectionAnnouncement(player);

        // when
        announcement.playSelectionPhrase(1);

        // then
        assertEquals(1, player.playedFiles().size(), "expected exactly one playBlocking call");
        assertEquals(
                GermanTimeAnnouncement.AUDIO_BASE + "menu_1.opus",
                player.playedFiles().get(0));
    }
}
