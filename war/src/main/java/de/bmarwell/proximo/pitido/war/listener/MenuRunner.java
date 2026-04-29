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
package de.bmarwell.proximo.pitido.war.listener;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.api.LanguageSelectionAnnouncement;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipSession;

/**
 * Plays the language-selection menu in a loop and transitions into the time announcement once
 * the caller presses a digit, or hangs up when the menu expires without input.
 *
 * <p>The menu loops at most {@link #MAX_MENU_LOOPS} times.
 * A one-second silence separates successive language phrases; a five-second silence separates
 * successive loop iterations.
 */
@ApplicationScoped
public class MenuRunner {

    private static final System.Logger LOGGER = System.getLogger(MenuRunner.class.getName());

    /** Number of full language-list cycles before the menu expires and the call is hung up. */
    static final int MAX_MENU_LOOPS = 5;

    /** Silence between successive language-selection phrases within one loop iteration. */
    static final Duration MENU_SILENCE_BETWEEN_LANGUAGES = Duration.ofSeconds(1);

    /** Silence between successive loop iterations of the selection menu. */
    static final Duration MENU_SILENCE_BETWEEN_LOOPS = Duration.ofSeconds(5);

    @Inject
    CallSessionManager callSessionManager;

    @Inject
    AnnouncementLoop announcementLoop;

    /**
     * Plays the selection menu, then transitions to the announcement loop for the chosen language,
     * or hangs up if no digit was pressed before the menu expired.
     */
    void run(
            SipSession session,
            AudioPlayer player,
            SequencedMap<Integer, LanguageFactory> menu,
            String sessionId,
            CallMedia media) {
        try {
            Thread.sleep(500);
            runMenuLoop(player, menu, sessionId);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } finally {
            LanguageFactory chosen = this.callSessionManager.takePendingSelection(sessionId);

            if (chosen != null) {
                // Clear the interrupt flag before transitioning to announcement playback.
                // The menu was cancelled via Future.cancel(true) when the DTMF digit arrived,
                // which set the thread interrupt flag. We must clear it to allow the announcement
                // to play on the same executor thread without being immediately interrupted.
                Thread.interrupted();

                this.announcementLoop.play(session, player, chosen, sessionId, media);
            } else {
                LOGGER.log(
                        System.Logger.Level.INFO,
                        "{0}Language-selection menu expired without digit — hanging up",
                        SipCallHeaders.callPrefix(sessionId));
                CallSessionManager.sendBye(session);
                this.callSessionManager.remove(sessionId);
                CallSessionManager.closeMedia(media);
            }
        }
    }

    private static void runMenuLoop(AudioPlayer player, SequencedMap<Integer, LanguageFactory> menu, String sessionId)
            throws InterruptedException {
        List<Map.Entry<Integer, LanguageFactory>> entries =
                menu.entrySet().stream().toList();

        for (int loop = 0; loop < MAX_MENU_LOOPS; loop++) {
            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<Integer, LanguageFactory> entry = entries.get(index);
                playSelectionPhrase(player, entry.getValue(), entry.getKey(), sessionId);

                boolean isLastLanguage = (index == entries.size() - 1);
                boolean isLastLoop = (loop == MAX_MENU_LOOPS - 1);

                if (!isLastLanguage) {
                    player.playSilence(MENU_SILENCE_BETWEEN_LANGUAGES);
                } else if (!isLastLoop) {
                    player.playSilence(MENU_SILENCE_BETWEEN_LOOPS);
                }
            }
        }
    }

    private static void playSelectionPhrase(AudioPlayer player, LanguageFactory factory, int slot, String sessionId)
            throws InterruptedException {
        try {
            LanguageSelectionAnnouncement announcement = factory.createLanguageSelectionAnnouncement(player);
            announcement.playSelectionPhrase(slot);
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Could not play selection phrase for [{1}] at slot {2} — skipping",
                    SipCallHeaders.callPrefix(sessionId),
                    factory.displayName(),
                    slot,
                    ioException);
        }
    }
}
