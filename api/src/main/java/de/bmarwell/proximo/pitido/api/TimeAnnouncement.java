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

/**
 * Plays the current time announcement for one language.
 *
 * <p>Implementations determine the current time themselves (e.g. via {@code ZonedDateTime.now()})
 * and play the appropriate audio resources via the {@link AudioPlayer} they were constructed with.
 */
public interface TimeAnnouncement {

    /**
     * Plays the time announcement to completion, or until the calling thread is interrupted.
     *
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted; stop playback and propagate
     */
    void announce() throws IOException, InterruptedException;
}
