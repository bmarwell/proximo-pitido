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
package de.bmarwell.proximo.pitido.api;

import java.io.IOException;

/**
 * Plays the language-selection prompt for a single menu slot.
 *
 * <p>Example (German, slot 1): <em>„Für Deutsch drücken Sie die 1."</em>
 * Example (English, slot 2): <em>„Press 2 for English."</em>
 *
 * <p>The phrase is spoken in the language it represents so that a caller unfamiliar with
 * the other languages can still recognise their own option.
 */
public interface LanguageSelectionAnnouncement {

    /**
     * Plays the selection phrase for this language at the given menu position.
     *
     * @param number the DTMF digit the caller must press to select this language (1–9)
     * @throws IOException          on any I/O or RTP streaming error
     * @throws InterruptedException if interrupted (caller pressed a digit); stop and propagate
     */
    void playSelectionPhrase(int number) throws IOException, InterruptedException;
}
