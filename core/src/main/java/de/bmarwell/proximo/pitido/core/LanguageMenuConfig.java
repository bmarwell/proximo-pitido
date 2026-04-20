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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * Parses the {@code sip.languages.enabled} MicroProfile Config property into an ordered
 * digit-to-locale-tag mapping.
 *
 * <p>Two formats are supported:
 *
 * <ul>
 *   <li><b>Simple</b>: a comma-separated list of BCP 47 locale tags — digits are assigned
 *       automatically in list order starting at {@code 1}.
 *       Example: {@code de-DE,en-GB}
 *   <li><b>Explicit</b>: entries of the form {@code <digit>=<locale>}, comma-separated.
 *       Digits are used as given; entries are ordered by digit value.
 *       Example: {@code 1=de-DE,2=en-GB}
 * </ul>
 *
 * <p>Locale tags are normalised with {@link Locale#forLanguageTag} so that {@code de_DE},
 * {@code de-de}, and {@code de-DE} all resolve to {@code de-DE}.
 * Variant subtags (e.g. {@code de-DE-myvoice}) are preserved with their original casing.
 *
 * <p>An empty or blank config string yields an empty map; the caller is responsible for
 * treating an empty map as "all discovered languages are active".
 */
public final class LanguageMenuConfig {

    private LanguageMenuConfig() {}

    /**
     * Parses the config string and returns an ordered map of dial digit to normalised locale tag.
     *
     * @param config the raw value of {@code sip.languages.enabled}, may be blank
     * @return a {@link SequencedMap} ordered by digit; never {@code null}
     */
    public static SequencedMap<Integer, String> parse(String config) {
        if (config == null || config.isBlank()) {
            return new LinkedHashMap<>();
        }

        String[] entries = Arrays.stream(config.split(","))
                .map(String::strip)
                .filter(entry -> !entry.isEmpty())
                .toArray(String[]::new);

        boolean isExplicit = isExplicitFormat(entries);

        if (isExplicit) {
            return parseExplicit(entries);
        }

        return parseSimple(entries);
    }

    private static boolean isExplicitFormat(String[] entries) {
        if (entries.length == 0) {
            return false;
        }

        return entries[0].contains("=");
    }

    private static SequencedMap<Integer, String> parseSimple(String[] entries) {
        LinkedHashMap<Integer, String> result = new LinkedHashMap<>();

        for (int index = 0; index < entries.length; index++) {
            result.put(index + 1, normalise(entries[index]));
        }

        return result;
    }

    private static SequencedMap<Integer, String> parseExplicit(String[] entries) {
        TreeMap<Integer, String> result = new TreeMap<>();

        for (String entry : entries) {
            int separator = entry.indexOf('=');

            if (separator < 0) {
                continue;
            }

            String digitPart = entry.substring(0, separator).strip();
            String localePart = entry.substring(separator + 1).strip();

            try {
                int digit = Integer.parseInt(digitPart);
                result.put(digit, normalise(localePart));
            } catch (NumberFormatException numberFormatException) {
                throw new IllegalArgumentException(
                        "Invalid digit in sip.languages.enabled entry: '" + entry + "'", numberFormatException);
            }
        }

        return result;
    }

    /** Normalises a locale tag string to canonical BCP 47 form. */
    static String normalise(String tag) {
        return Locale.forLanguageTag(tag.replace('_', '-')).toLanguageTag();
    }
}
