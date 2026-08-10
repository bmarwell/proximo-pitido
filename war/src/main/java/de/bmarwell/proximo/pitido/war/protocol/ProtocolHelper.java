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
package de.bmarwell.proximo.pitido.war.protocol;

import de.bmarwell.proximo.pitido.core.sip.LocalSipHostProvider;
import de.bmarwell.proximo.pitido.core.sip.SrvDnsResolver;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.inject.Inject;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.URI;

@Default
@ApplicationScoped
public class ProtocolHelper {

    @Inject
    SrvDnsResolver srvDnsResolver;

    @Inject
    LocalSipHostProvider localSipHostProvider;

    public ProtocolHelper() {
        // cdi
    }

    public URI buildRequestUri(SipFactory sipFactory, String registrar) throws ServletParseException {
        String sipServer = srvDnsResolver.resolve(registrar);
        return sipFactory.createURI("sip:" + sipServer + ":5060;transport=tcp");
    }

    public Address buildContactAddress(SipFactory sipFactory, String sipId) {
        String host = localSipHostProvider.get();
        String sipHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;

        return sipFactory.createAddress(sipFactory.createSipURI(sipId, sipHost));
    }
}
