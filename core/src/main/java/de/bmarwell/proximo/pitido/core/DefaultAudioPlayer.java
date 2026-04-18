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
package de.bmarwell.proximo.pitido.core;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import java.io.IOException;
import javax.enterprise.context.Dependent;

@Dependent
public class DefaultAudioPlayer implements AudioPlayer {

    private static final System.Logger LOGGER = System.getLogger(DefaultAudioPlayer.class.getName());

    /**
     * Stub implementation: logs the resource path and sleeps for an estimated playback duration.
     * A real implementation will negotiate SDP from the active SIP call and stream audio via RTP.
     *
     * @param resourcePath classpath-relative path to the audio resource
     * @throws InterruptedException if the calling thread is interrupted during the sleep
     */
    @Override
    public void playBlocking(String resourcePath) throws IOException, InterruptedException {
        LOGGER.log(System.Logger.Level.INFO, "Playing audio resource: {0}", resourcePath);
        Thread.sleep(1_000);
    }
}
