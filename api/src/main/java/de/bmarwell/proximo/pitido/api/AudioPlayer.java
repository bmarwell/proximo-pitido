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
package de.bmarwell.proximo.pitido.api;

import java.io.IOException;
import java.time.Duration;

/**
 * Plays an audio resource to the active SIP call leg (blocking).
 *
 * <p>Implementations must block until playback completes or the calling thread is interrupted.
 * Interruption is the signal to stop playback early — this is how DTMF-triggered language
 * selection cancels a running menu announcement mid-phrase.
 *
 * <p>Implementations should be provided as {@code @Dependent} or per-call-scoped CDI beans
 * and are created via {@link de.bmarwell.proximo.pitido.spi.LanguageFactory}, which receives
 * the {@link AudioPlayer} instance as a constructor argument.
 */
public interface AudioPlayer {

    /**
     * Plays the given resource to completion.
     *
     * @param resourcePath classpath-relative path to the audio resource
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if the calling thread is interrupted; implementations must
     *                              stop playback promptly and propagate this exception
     */
    void playBlocking(String resourcePath) throws IOException, InterruptedException;

    /**
     * Holds the stream open with silence for the given duration.
     *
     * <p>RTP implementations must send actual silence packets so the receiver's jitter buffer
     * stays active and renders the gap as audible silence.
     * Without continuous packet flow, most jitter buffers flush after ~200 ms and play the next
     * audio immediately, collapsing any intended gap.
     *
     * <p>The default implementation simply sleeps; it is suitable for test doubles that do not
     * produce real RTP output.
     * Non-positive durations are ignored.
     *
     * @param duration how long to be silent
     * @throws InterruptedException if the calling thread is interrupted
     */
    default void playSilence(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();

        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
