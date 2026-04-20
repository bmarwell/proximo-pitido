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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SequencedMap;
import org.junit.jupiter.api.Test;

class LanguageMenuConfigTest {

    @Test
    void blankConfigReturnsEmptyMap() {
        // given
        String config = "";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    void singleLocaleTagAutoAssignsDigitOne() {
        // given
        String config = "en-GB";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertEquals(1, result.size());
        assertEquals("en-GB", result.get(1));
    }

    @Test
    void twoLocaleTagsAutoAssignDigitsOneAndTwo() {
        // given
        String config = "de-DE,en-GB";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertEquals(2, result.size());
        assertEquals("de-DE", result.get(1));
        assertEquals("en-GB", result.get(2));
    }

    @Test
    void explicitDigitFormatIsRespected() {
        // given
        String config = "1=de-DE,2=en-GB";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertEquals(2, result.size());
        assertEquals("de-DE", result.get(1));
        assertEquals("en-GB", result.get(2));
    }

    @Test
    void underscoreLocaleTagIsNormalised() {
        // given
        String config = "1=de_DE,2=en_GB";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertEquals("de-DE", result.get(1));
        assertEquals("en-GB", result.get(2));
    }

    @Test
    void lowercaseLocaleTagIsNormalised() {
        // given
        String config = "1=de-de,2=en-gb";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then
        assertEquals("de-DE", result.get(1));
        assertEquals("en-GB", result.get(2));
    }

    @Test
    void explicitDigitsAreOrderedByDigitValue() {
        // given — entries supplied in reverse order
        String config = "2=en-GB,1=de-DE";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then — map is ordered by digit, not by declaration order
        assertEquals(1, result.sequencedKeySet().getFirst());
        assertEquals("de-DE", result.get(1));
        assertEquals("en-GB", result.get(2));
    }

    @Test
    void variantSubtagIsPreserved() {
        // given — hypothetical dialect/voice variant
        String config = "1=de-DE-myvoice";

        // when
        SequencedMap<Integer, String> result = LanguageMenuConfig.parse(config);

        // then — variant casing is preserved by Java's BCP 47 implementation
        assertEquals("de-DE-myvoice", result.get(1));
    }

    @Test
    void invalidDigitThrowsIllegalArgumentException() {
        // given
        String config = "one=de-DE";

        // when / then
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> LanguageMenuConfig.parse(config));
        assertTrue(exception.getMessage().contains("one=de-DE"));
    }
}
