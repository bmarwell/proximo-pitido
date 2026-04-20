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
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import java.io.IOException;

/**
 * Plays the British-English language-selection phrase: <em>"Press two for English."</em>
 */
public class BritishEnglishLanguageSelectionAnnouncement implements LanguageSelectionAnnouncement {

    private final AudioPlayer audioPlayer;

    public BritishEnglishLanguageSelectionAnnouncement(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Plays "Press [N] for English." as a single self-contained audio file.
     *
     * @param number the 1-based menu slot digit (1–9)
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Override
    public void playSelectionPhrase(int number) throws IOException, InterruptedException {
        this.audioPlayer.playBlocking(BritishEnglishTimeAnnouncement.AUDIO_BASE + "menu_" + number + ".opus");
    }
}
