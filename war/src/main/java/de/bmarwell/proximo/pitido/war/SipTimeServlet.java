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
package de.bmarwell.proximo.pitido.war;

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.listener.SipRegistrationListener;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.annotation.SipServlet;

@SipServlet(name = "SipTimeServlet", loadOnStartup = 1, applicationName = "Proximo Pitido")
public class SipTimeServlet extends javax.servlet.sip.SipServlet implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final System.Logger LOGGER = System.getLogger(SipTimeServlet.class.getName());

    @Inject
    SipRegistrationListener sipRegistrationService;

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    AudioPlayer audioPlayer;

    @Override
    protected void doInvite(SipServletRequest req) throws ServletException, IOException {
        super.doInvite(req);
        // TODO: implement
        throw new UnsupportedOperationException(
                "not yet implemented: [de.bmarwell.proximo.pitido.war.SipTimeServlet::doInvite].");
    }

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        LOGGER.log(System.Logger.Level.INFO, "do init: {0}", cfg);
        this.sipRegistrationService.register(getServletContext());
    }

    public void setLanguageFactories(Instance<LanguageFactory> languageFactories) {
        this.languageFactories = languageFactories;
    }

    public void setAudioPlayer(AudioPlayer audioPlayer) {
        this.audioPlayer = audioPlayer;
    }
}
