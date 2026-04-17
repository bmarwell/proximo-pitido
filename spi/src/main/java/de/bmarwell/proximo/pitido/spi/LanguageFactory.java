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
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;

public interface LanguageFactory {

    /// metadata: Get the ISO language code
    String getLanguageCode();

    /// get the Display Name, mostly for debugging.
    String getDisplayName();

    /// Creates a {@link TimeAnnouncement}-Instance with the given {@link AudioPlayer}
    TimeAnnouncement createTimeAnnouncement(AudioPlayer audioPlayer);

    /// Creates a {@link LanguageSelectionAnnouncement}-Instance with the given {@link AudioPlayer}.
    LanguageSelectionAnnouncement createLanguageSelectionAnnouncement(AudioPlayer audioPlayer);
}
