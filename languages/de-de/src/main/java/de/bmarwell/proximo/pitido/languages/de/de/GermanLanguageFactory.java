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
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.time.Clock;
import java.util.Locale;
import java.util.StringJoiner;
import javax.enterprise.context.Dependent;
import javax.inject.Named;

@Dependent
@Named("language-factory-de-de")
public class GermanLanguageFactory implements LanguageFactory {

    private static final Locale LOCALE = Locale.GERMAN;
    private static final String DISPLAY_NAME = "Deutsch";
    private static final int DEFAULT_ORDER = 10;

    @Override
    public Locale locale() {
        return LOCALE;
    }

    @Override
    public String displayName() {
        return DISPLAY_NAME;
    }

    @Override
    public int defaultOrder() {
        return DEFAULT_ORDER;
    }

    @Override
    public TimeAnnouncement createTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
        return new GermanTimeAnnouncement(audioPlayer, clock);
    }

    @Override
    public LanguageSelectionAnnouncement createLanguageSelectionAnnouncement(AudioPlayer audioPlayer) {
        return new GermanLanguageSelectionAnnouncement(audioPlayer);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", GermanLanguageFactory.class.getSimpleName() + "[", "]")
            .add("locale=" + locale())
            .add("displayName='" + displayName() + "'")
            .add("defaultOrder=" + defaultOrder())
            .toString();
    }
}
