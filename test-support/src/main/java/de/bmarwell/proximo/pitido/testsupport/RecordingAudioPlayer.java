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
package de.bmarwell.proximo.pitido.testsupport;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import java.util.ArrayList;
import java.util.List;

/**
 * A test-double {@link AudioPlayer} that records every call to
 * {@link #playBlocking(String)} without producing any audio output.
 *
 * <p>Intended for use in unit tests running on headless servers where no sound card,
 * display server, or RTP receiver is available.
 * Pass an instance to a {@link de.bmarwell.proximo.pitido.api.TimeAnnouncement}
 * constructor, call {@link de.bmarwell.proximo.pitido.api.TimeAnnouncement#announce()},
 * and inspect the returned {@link de.bmarwell.proximo.pitido.api.PlaybackReceipt} or
 * call {@link #playedFiles()} directly.
 *
 * <p>This class is <em>not</em> thread-safe.
 * Each test should use a fresh instance.
 */
public final class RecordingAudioPlayer implements AudioPlayer {

    private final List<String> playedFiles = new ArrayList<>();

    /**
     * Records the resource path and returns immediately without playing anything.
     *
     * @param resourcePath classpath-relative path to the audio resource
     */
    @Override
    public void playBlocking(String resourcePath) {
        this.playedFiles.add(resourcePath);
    }

    /**
     * Returns an unmodifiable snapshot of all resource paths submitted so far, in call order.
     *
     * @return ordered list of resource paths; never {@code null}
     */
    public List<String> playedFiles() {
        return List.copyOf(this.playedFiles);
    }
}
