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
package de.bmarwell.proximo.pitido.spi;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import java.io.IOException;
import java.util.List;

/**
 * Base class for {@link TimeAnnouncement} implementations that play audio files from the classpath.
 *
 * <p>Provides a single {@link #play(List, String)} helper that:
 * <ol>
 *   <li>Resolves the full classpath resource path as {@code audioBase() + fileName}</li>
 *   <li>Delegates to the injected {@link AudioPlayer}</li>
 *   <li>Records the path in the running playback list</li>
 * </ol>
 *
 * <p>Subclasses must supply an {@link #audioBase()} that is the classpath prefix for their
 * audio resources, e.g. {@code "de/bmarwell/proximo/pitido/languages/de/de/audio/de/"}.
 */
public abstract class AbstractTimeAnnouncement implements TimeAnnouncement {

    /** The audio player to use for all playback operations. */
    protected final AudioPlayer audioPlayer;

    /**
     * Constructs an announcement backed by the given player.
     *
     * @param audioPlayer the player to use; must not be {@code null}
     */
    protected AbstractTimeAnnouncement(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }

    /**
     * Returns the classpath prefix for all audio resources used by this implementation.
     *
     * <p>The returned string must end with {@code "/"}.
     * For example: {@code "de/bmarwell/proximo/pitido/languages/de/de/audio/de/"}.
     */
    protected abstract String audioBase();

    /**
     * Plays the resource at {@code audioBase() + fileName}, waits for it to finish, and adds the
     * full resolved path to {@code played}.
     *
     * @param played    the accumulator for the playback receipt; entries are added in call order
     * @param fileName  the file name within {@link #audioBase()}, e.g. {@code "signal.wav"}
     * @throws IOException          on any I/O or streaming error
     * @throws InterruptedException if the calling thread is interrupted; stops immediately
     */
    protected void play(List<String> played, String fileName) throws IOException, InterruptedException {
        String path = audioBase() + fileName;
        this.audioPlayer.playBlocking(path);
        played.add(path);
    }
}
