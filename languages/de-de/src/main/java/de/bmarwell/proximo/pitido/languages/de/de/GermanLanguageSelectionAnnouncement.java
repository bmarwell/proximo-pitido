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
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import java.io.IOException;

/**
 * Plays the German language-selection phrase for the assigned menu slot.
 *
 * <p>The phrase file is looked up at {@code audio/de/menu_<number>.opus} on the classpath.
 * If the file for a given slot number is absent, {@link #playSelectionPhrase} returns silently.
 * This allows partial rollout: add {@code menu_1.opus}, {@code menu_2.opus}, … one at a time
 * without requiring all slots to be populated simultaneously.
 */
public class GermanLanguageSelectionAnnouncement implements LanguageSelectionAnnouncement {

    private final AudioPlayer audioPlayer;

    public GermanLanguageSelectionAnnouncement(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Plays "Für Deutsch drücken Sie die [N]." if the corresponding audio file is available.
     * Does nothing if {@code menu_<number>.opus} is not present on the classpath.
     *
     * @param number the 1-based menu slot digit (1–9)
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Override
    public void playSelectionPhrase(int number) throws IOException, InterruptedException {
        String resourcePath = GermanTimeAnnouncement.AUDIO_BASE + "menu_" + number + ".opus";

        if (getClass().getClassLoader().getResource(resourcePath) == null) {
            return;
        }

        this.audioPlayer.playBlocking(resourcePath);
    }
}
