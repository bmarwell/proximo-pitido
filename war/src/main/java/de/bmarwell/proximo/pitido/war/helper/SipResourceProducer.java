/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * [PROJECT_HOME]/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */
package de.bmarwell.proximo.pitido.war.helper;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.sip.SipFactory;

@ApplicationScoped
public class SipResourceProducer {

    @Inject
    ServletContext servletContext;

    @Produces
    @ApplicationScoped
    public SipFactory produceSipFactory() {
        // In JSR 289, the container places the factory in this specific attribute
        SipFactory factory = (SipFactory) servletContext.getAttribute(SipFactory.class.getName());

        if (factory == null) {
            // Fallback for some Liberty versions: check the short name
            factory = (SipFactory) servletContext.getAttribute("javax.servlet.sip.SipFactory");
        }

        System.out.println("Produced SipFactory: " + factory);

        return factory;
    }
}
