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

/**
 * SPI for adding a language to Próximo Pitido.
 *
 * <p>Implement this interface and register it as a CDI bean (e.g. {@code @ApplicationScoped}).
 * The application discovers all visible {@code LanguageFactory} implementations at runtime.
 * No changes to the core or war modules are required.
 *
 * <p>When multiple languages are present, the language-selection menu is built from all
 * discovered factories, sorted by {@link #getDefaultOrder()} unless overridden by configuration.
 */
public interface LanguageFactory {

    /**
     * Returns the ISO 639-1 two-letter language code (e.g. {@code "de"}, {@code "en"}, {@code "es"}).
     */
    String getLanguageCode();

    /**
     * Returns a human-readable display name, used for logging and diagnostics.
     * Example: {@code "Deutsch"}, {@code "English"}.
     */
    String getDisplayName();

    /**
     * Returns the default position of this language in the selection menu.
     *
     * <p>Lower values appear earlier. Implementations should use stable, unique values
     * (e.g. {@code 10} for German, {@code 20} for English, {@code 30} for Spanish) so that
     * the menu order is deterministic across restarts even without explicit configuration.
     *
     * <p>The configured order (if any) takes precedence over this default.
     */
    int getDefaultOrder();

    /**
     * Creates a {@link TimeAnnouncement} for the active call, backed by the given player.
     * Called once per incoming call after the language has been selected.
     */
    TimeAnnouncement createTimeAnnouncement(AudioPlayer audioPlayer);

    /**
     * Creates a {@link LanguageSelectionAnnouncement} for use in the language-selection menu.
     * Called once per incoming call when multiple languages are available.
     */
    LanguageSelectionAnnouncement createLanguageSelectionAnnouncement(AudioPlayer audioPlayer);
}
