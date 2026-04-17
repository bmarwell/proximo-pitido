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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.sip.SipFactory;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@WebListener
public class SipRegistrationListener implements ServletContextListener {

    private static final System.Logger LOGGER = System.getLogger(SipRegistrationListener.class.getName());

    @Inject
    @ConfigProperty(name = "sip.phone.number", defaultValue = "+1000000000")
    private String phoneNumber;

    @Inject
    SipFactory sipFactory;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // Here you would use the SipFactory to create a REGISTER request
        // and send it to your provider (e.g., tel.t-online.de)
        // using your username and password from mpConfig.
        LOGGER.log(
                System.Logger.Level.INFO,
                "Registering SIP phone number: [{0}] via [{1}]",
                this.phoneNumber,
                this.sipFactory);
    }
}
