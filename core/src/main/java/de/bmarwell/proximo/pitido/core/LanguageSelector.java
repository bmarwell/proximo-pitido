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

import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Pure utility for selecting a {@link LanguageFactory} from an unordered collection.
 *
 * <p>This class contains no state and no SIP or CDI dependencies, making it straightforward to
 * test in isolation.
 */
public final class LanguageSelector {

    private LanguageSelector() {}

    /**
     * Returns all factories sorted ascending by {@link LanguageFactory#defaultOrder()}.
     *
     * <p>Accepts any {@link Iterable}, including a CDI {@code Instance<LanguageFactory>}, so
     * the result is deterministic regardless of CDI's internal iteration order.
     */
    public static List<LanguageFactory> sorted(Iterable<LanguageFactory> factories) {
        return StreamSupport.stream(factories.spliterator(), false)
                .sorted(Comparator.comparingInt(LanguageFactory::defaultOrder))
                .toList();
    }

    /**
     * Finds the factory for the given 1-based menu digit.
     *
     * <p>Digit {@code 1} maps to the first factory (lowest {@link LanguageFactory#defaultOrder()}),
     * digit {@code 2} to the second, and so on.
     * Returns {@link Optional#empty()} for digits outside the valid range.
     *
     * @param sorted a pre-sorted list as returned by {@link #sorted(Iterable)}
     * @param digit  the 1-based digit pressed by the caller
     */
    public static Optional<LanguageFactory> fromDigit(List<LanguageFactory> sorted, int digit) {
        int index = digit - 1;
        if (index < 0 || index >= sorted.size()) {
            return Optional.empty();
        }
        return Optional.of(sorted.get(index));
    }
}
