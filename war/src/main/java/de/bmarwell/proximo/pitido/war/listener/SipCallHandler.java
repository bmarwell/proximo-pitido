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
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import java.io.IOException;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;

/**
 * Handles incoming SIP call sessions: INVITE, DTMF input (INFO), and call teardown (BYE).
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Answering incoming calls (INVITE)</li>
 *   <li>Playing the language-selection menu when multiple languages are available</li>
 *   <li>Processing DTMF digits (INFO) to let the caller select a language mid-playback</li>
 *   <li>Playing the time announcement in the selected language</li>
 *   <li>Tearing down the call session (BYE)</li>
 * </ul>
 *
 * <p>Language selection: if exactly one {@link LanguageFactory} is present on the classpath,
 * the announcement plays immediately. With two or more, the selection menu cycles through all
 * available languages in order of {@link LanguageFactory#getDefaultOrder()}, or as configured.
 * The caller may interrupt at any point by pressing a digit.
 *
 * <p>Audio interruption: when a DTMF digit arrives, the active {@link AudioPlayer} is interrupted
 * via {@link Thread#interrupt()} so that blocking playback stops promptly.
 */
@ApplicationScoped
public class SipCallHandler {

    private static final System.Logger LOGGER = System.getLogger(SipCallHandler.class.getName());

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    AudioPlayer audioPlayer;

    /**
     * Handles an incoming INVITE. Sends {@code 100 Trying}, then either plays the
     * time announcement directly (single language) or the language-selection menu first
     * (multiple languages).
     *
     * <p>Not yet implemented.
     */
    public void handleInvite(SipServletRequest req) throws IOException {
        LOGGER.log(
                System.Logger.Level.INFO,
                "Incoming INVITE from [{0}] — call handling not yet implemented",
                req.getFrom());
        req.createResponse(503).send();
    }

    /**
     * Handles a SIP INFO message carrying a DTMF digit. Interrupts the currently playing
     * language-selection menu and locks in the caller's language choice.
     *
     * <p>Not yet implemented.
     */
    public void handleDtmf(SipServletRequest req) throws IOException {
        LOGGER.log(System.Logger.Level.INFO, "Received INFO (DTMF) from [{0}]", req.getFrom());
        req.createResponse(200).send();
    }

    /**
     * Handles BYE — cleans up the call session and stops any ongoing audio playback.
     *
     * <p>Not yet implemented.
     */
    public void handleBye(SipServletRequest req) throws IOException {
        LOGGER.log(System.Logger.Level.INFO, "Call ended (BYE) from [{0}]", req.getFrom());
        req.createResponse(200).send();
    }
}
