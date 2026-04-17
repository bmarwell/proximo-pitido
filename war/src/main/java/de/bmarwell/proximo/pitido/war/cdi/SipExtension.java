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
package de.bmarwell.proximo.pitido.war.cdi;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.sip.SipFactory;

/// By default, SipFactory is not injectable.
/// Let's make it injectable using the InitialContext reference defined in `web.xml`.
public class SipExtension implements Extension {

    private static final System.Logger LOGGER = System.getLogger(SipExtension.class.getName());

    private static final String IC_SIP_FACTORY = "java:comp/env/sip/SipFactory";

    void afterBeanDiscovery(@Observes AfterBeanDiscovery event, BeanManager beanManager) {
        event.addBean()
                .types(SipFactory.class)
                .scope(ApplicationScoped.class)
                .beanClass(SipExtension.class)
                .<SipFactory>createWith(_ -> getSipFactoryFromInitialContext());
    }

    private static SipFactory getSipFactoryFromInitialContext() {
        try {
            if (LOGGER.isLoggable(System.Logger.Level.DEBUG)) {
                LOGGER.log(System.Logger.Level.DEBUG, "Looking up SipFactory in {0}", IC_SIP_FACTORY);
            }

            InitialContext initialContext = new InitialContext();

            return (SipFactory) initialContext.lookup(IC_SIP_FACTORY);
        } catch (NamingException e) {
            LOGGER.log(System.Logger.Level.ERROR, "Failed to look up SipFactory in {0}", IC_SIP_FACTORY, e);

            throw new IllegalStateException(
                    "SipFactory not available in InitialContext, did you enable the Liberty Feature?", e);
        }
    }
}
