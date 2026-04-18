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
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import java.io.IOException;
import java.time.Clock;

public class GermanTimeAnnouncement implements TimeAnnouncement {

    /**
     * Classpath resource path for the beep audio file.
     * Used as a stand-in until full time-announcement audio is implemented.
     */
    static final String BEEP_RESOURCE = "de/bmarwell/proximo/pitido/languages/de/de/beep.mp3";

    private static final long PAUSE_BETWEEN_BEEPS_MS = 2_000L;

    private final AudioPlayer audioPlayer;

    public GermanTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Plays the beep tone in a loop until the calling thread is interrupted.
     * A 2-second pause is inserted between each play.
     * This is a temporary stub; real time-announcement audio will replace it.
     *
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stops the loop and propagates
     */
    @Override
    public void announce() throws IOException, InterruptedException {
        while (true) {
            audioPlayer.playBlocking(BEEP_RESOURCE);
            Thread.sleep(PAUSE_BETWEEN_BEEPS_MS);
        }
    }
}
