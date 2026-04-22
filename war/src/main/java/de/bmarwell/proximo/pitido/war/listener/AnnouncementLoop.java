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
import de.bmarwell.proximo.pitido.api.TimeAnnouncement;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipSession;

/**
 * Plays the time announcement for a single language in a loop until interrupted,
 * the call is hung up, or the maximum call duration is reached.
 *
 * <p>On exit (for any reason), removes the session from {@link CallSessionManager} and closes
 * the media socket so no resources leak.
 *
 * <h2>Timezone</h2>
 *
 * <p>{@link Clock#systemDefaultZone()} is used to obtain the current local time.
 * OpenJDK on Linux resolves the JVM default timezone in the following order:
 *
 * <ol>
 *   <li>{@code TZ} environment variable — set this in {@code docker-compose.yml} or via
 *       {@code docker run -e TZ=Europe/Berlin} to configure the announced timezone.</li>
 *   <li>{@code /etc/localtime} — the container or OS default.
 *       Stock Docker images default to UTC, which is why announcements are in UTC when
 *       {@code TZ} is not set.</li>
 * </ol>
 *
 * <p>Note: {@code user.country} and {@code user.region} are locale settings that control
 * language formatting (decimal separators, date order, etc.) but have <em>no effect</em>
 * on timezone resolution.
 */
@ApplicationScoped
public class AnnouncementLoop {

    private static final System.Logger LOGGER = System.getLogger(AnnouncementLoop.class.getName());

    /** Maximum duration of a single call before the server hangs up. */
    static final Duration CALL_MAX_DURATION = Duration.ofMinutes(2);

    @Inject
    CallSessionManager callSessionManager;

    /**
     * Plays the time announcement repeatedly until interrupted, the socket closes, or
     * {@link #CALL_MAX_DURATION} elapses.
     * On exit, removes the call from session state and closes the media socket.
     */
    void play(SipSession session, AudioPlayer player, LanguageFactory factory, String sessionId, CallMedia media) {
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Announcement loop starting — language [{1}]",
                SipCallHeaders.callPrefix(sessionId),
                factory.displayName());

        Instant deadline = Instant.now().plus(CALL_MAX_DURATION);

        try {
            while (Instant.now().isBefore(deadline)) {
                boolean socketClosed = playOneAnnouncement(player, factory, sessionId, media);

                if (socketClosed) {
                    return;
                }
            }

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "{0}Maximum call duration reached — hanging up",
                    SipCallHeaders.callPrefix(sessionId));
            CallSessionManager.sendBye(session);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "{0}Announcement loop interrupted",
                    SipCallHeaders.callPrefix(sessionId));
        } finally {
            this.callSessionManager.remove(sessionId);
            CallSessionManager.closeMedia(media);
        }
    }

    /**
     * Plays one full announcement cycle followed by one second of silence.
     *
     * @return {@code true} if the socket was closed mid-play (caller hung up); {@code false}
     *     otherwise
     */
    private static boolean playOneAnnouncement(
            AudioPlayer player, LanguageFactory factory, String sessionId, CallMedia media)
            throws InterruptedException {
        TimeAnnouncement announcement = factory.createTimeAnnouncement(player, Clock.systemDefaultZone());

        try {
            var receipt = announcement.announce();
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "{0}Announcement complete; played {1} file(s)",
                    SipCallHeaders.callPrefix(sessionId),
                    receipt.fileNames().size());
            LOGGER.log(
                    Level.TRACE,
                    "{0}Announcement complete; played: {1}.",
                    SipCallHeaders.callPrefix(sessionId),
                    receipt);
            player.playSilence(Duration.ofSeconds(1));

            return false;
        } catch (IOException ioException) {
            if (media.localSocket().isClosed()) {
                LOGGER.log(
                        System.Logger.Level.DEBUG,
                        "{0}Announcement loop: socket closed — exiting",
                        SipCallHeaders.callPrefix(sessionId));

                return true;
            }

            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0}Time announcement failed for language [{1}]: {2}",
                    SipCallHeaders.callPrefix(sessionId),
                    factory.displayName(),
                    ioException.getMessage(),
                    ioException);

            return false;
        }
    }
}
