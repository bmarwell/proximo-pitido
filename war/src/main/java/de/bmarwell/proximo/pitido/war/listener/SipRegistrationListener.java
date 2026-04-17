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

import java.io.IOException;
import java.util.Objects;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SipRegistrationListener {

    private static final System.Logger LOGGER = System.getLogger(SipRegistrationListener.class.getName());

    @Inject
    @ConfigProperty(name = "sip.provider.host")
    String host;

    @Inject
    @ConfigProperty(name = "sip.user.id")
    String userId;

    @Inject
    @ConfigProperty(name = "sip.user.domain")
    String domain;

    @Inject
    @ConfigProperty(name = "sip.registration.expires", defaultValue = "3600")
    int expires;

    public void register(ServletContext servletContext) {
        // Here you would use the SipFactory to create a REGISTER request
        // and send it to your provider (e.g., tel.t-online.de)
        // using your username and password from mpConfig.
        SipFactory sipFactory = (SipFactory) servletContext.getAttribute(SipFactory.class.getName());
        LOGGER.log(
                System.Logger.Level.INFO,
                "Registering SIP user ID: [{0}@{1}] via [{2}]",
                this.userId,
                this.domain,
                sipFactory);

        var applicationSession = sipFactory.createApplicationSession();
        Objects.requireNonNull(applicationSession.getApplicationName());
        var fromUri = sipFactory.createSipURI(this.userId, this.domain);

        try {
            var requestURI = sipFactory.createURI("sip:" + this.domain);
            var registerRequest = sipFactory.createRequest(applicationSession, "REGISTER", fromUri, fromUri);
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Session: {0}, FromToUri: {1}, RequestUri: {2}, request: {3}, applicationName: {4}",
                    applicationSession,
                    fromUri,
                    requestURI,
                    registerRequest,
                    applicationSession.getApplicationName());
            registerRequest.setExpires(this.expires);

            // Critical for some providers:
            // The 'Contact' header tells them where the server is physically located
            Address contact = sipFactory.createAddress(fromUri);
            registerRequest.setAddressHeader("Contact", contact);

            registerRequest.setRequestURI(requestURI);

            registerRequest.send();
        } catch (ServletParseException | IOException sipEx) {
            LOGGER.log(System.Logger.Level.ERROR, "Registration failed", sipEx);
        } catch (NullPointerException npe) {
            LOGGER.log(System.Logger.Level.ERROR, "Registration failed due to NPE", npe);
        }
    }
}
