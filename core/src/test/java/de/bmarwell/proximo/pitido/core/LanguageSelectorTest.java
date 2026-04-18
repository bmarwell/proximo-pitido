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

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LanguageSelectorTest {

    /**
     * Minimal stub that supplies only the {@code defaultOrder} used by {@link LanguageSelector}.
     * The audio methods throw {@link AssertionError} to make accidental calls visible.
     */
    private record StubFactory(int defaultOrder) implements LanguageFactory {

        @Override
        public Locale locale() {
            return Locale.ROOT;
        }

        @Override
        public String displayName() {
            return "stub-" + defaultOrder;
        }

        @Override
        public TimeAnnouncement createTimeAnnouncement(AudioPlayer audioPlayer, Clock clock) {
            throw new AssertionError("not expected in selector tests");
        }

        @Override
        public LanguageSelectionAnnouncement createLanguageSelectionAnnouncement(AudioPlayer audioPlayer) {
            throw new AssertionError("not expected in selector tests");
        }
    }

    private final LanguageFactory first = new StubFactory(10);
    private final LanguageFactory second = new StubFactory(20);
    private final LanguageFactory third = new StubFactory(30);

    @Test
    void emptyCollection_returnsEmptyList() {
        assertEquals(List.of(), LanguageSelector.sorted(List.of()));
    }

    @Test
    void sortedAscendingByDefaultOrder() {
        var result = LanguageSelector.sorted(List.of(third, first, second));
        assertEquals(List.of(first, second, third), result);
    }

    @Test
    void fromDigit_mapsOneBasedIndex() {
        var sorted = List.of(first, second, third);
        assertEquals(Optional.of(first), LanguageSelector.fromDigit(sorted, 1));
        assertEquals(Optional.of(second), LanguageSelector.fromDigit(sorted, 2));
        assertEquals(Optional.of(third), LanguageSelector.fromDigit(sorted, 3));
    }

    @Test
    void fromDigit_digitZero_returnsEmpty() {
        assertEquals(Optional.empty(), LanguageSelector.fromDigit(List.of(first), 0));
    }

    @Test
    void fromDigit_digitBeyondSize_returnsEmpty() {
        assertEquals(Optional.empty(), LanguageSelector.fromDigit(List.of(first), 2));
    }
}
