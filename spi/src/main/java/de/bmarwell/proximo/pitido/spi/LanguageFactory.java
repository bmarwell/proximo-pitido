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
import java.time.Clock;
import java.util.Locale;

/**
 * SPI for adding a language to Próximo Pitido.
 *
 * <p>Implement this interface and register it as a CDI bean (e.g. {@code @ApplicationScoped}).
 * The application discovers all visible {@code LanguageFactory} implementations at runtime.
 * No changes to the core or war modules are required.
 *
 * <p>When multiple languages are present, the language-selection menu is built from all
 * discovered factories, sorted by {@link #getDefaultOrder()} unless overridden by configuration.
 *
 * <p>Use a fully-qualified {@link Locale} (language <em>and</em> region) wherever a language
 * variant is specific to a region.
 * For example, European Spanish and Río Platense Spanish share the ISO 639-1 code {@code "es"}
 * but are represented as {@code Locale.forLanguageTag("es-ES")} and
 * {@code Locale.forLanguageTag("es-AR")} respectively, which keeps them unambiguous and
 * allows the runtime to sort and label them correctly.
 */
public interface LanguageFactory {

    /**
     * Returns the {@link Locale} that identifies this language variant.
     *
     * <p>Use a locale that includes both language and region when the variant is region-specific.
     * Examples:
     * <ul>
     *   <li>{@code Locale.GERMANY} ({@code de-DE}) for Standard German</li>
     *   <li>{@code Locale.forLanguageTag("es-ES")} for European Spanish</li>
     *   <li>{@code Locale.forLanguageTag("es-AR")} for Río Platense / Argentine Spanish</li>
     *   <li>{@code Locale.UK} ({@code en-GB}) for British English</li>
     * </ul>
     *
     * <p>The locale is used for identification, sorting fallback, and display — not for
     * number or date formatting (each implementation controls its own audio logic).
     */
    Locale getLocale();

    /**
     * Returns a human-readable display name in the language itself, used in the selection menu,
     * logging, and diagnostics.
     *
     * <p>For region-specific variants, include the region in parentheses so callers can
     * distinguish them.
     * Examples: {@code "Deutsch"}, {@code "English"}, {@code "Español (España)"},
     * {@code "Español (Río Platense)"}.
     */
    String getDisplayName();

    /**
     * Returns the default position of this language in the selection menu.
     *
     * <p>Lower values appear earlier.
     * Use stable, unique values so that the menu order is deterministic across restarts
     * even without explicit configuration.
     * The configured order (if any) takes precedence over this default.
     */
    int getDefaultOrder();

    /**
     * Creates a {@link TimeAnnouncement} for the active call, backed by the given player
     * and reading the current time from the given clock.
     *
     * <p>Pass {@link java.time.Clock#systemDefaultZone()} (or a zone-specific clock) in
     * production.
     * In tests, pass {@link java.time.Clock#fixed(java.time.Instant, java.time.ZoneId)} to
     * pin the announced time to a known value, making assertions deterministic.
     *
     * <p>Called once per incoming call after the language has been selected.
     */
    TimeAnnouncement createTimeAnnouncement(AudioPlayer audioPlayer, Clock clock);

    /**
     * Creates a {@link LanguageSelectionAnnouncement} for use in the language-selection menu.
     * Called once per incoming call when multiple languages are available.
     */
    LanguageSelectionAnnouncement createLanguageSelectionAnnouncement(AudioPlayer audioPlayer);
}
