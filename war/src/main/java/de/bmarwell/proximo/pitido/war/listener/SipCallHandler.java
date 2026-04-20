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
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;

/**
 * Routes SIP requests to the focused CDI handler beans.
 *
 * <p>This class contains no business logic.
 * Each SIP method is delegated immediately to the responsible bean:
 *
 * <ul>
 *   <li>INVITE → {@link CallAcceptor}</li>
 *   <li>INFO (DTMF) → {@link DtmfDispatcher}</li>
 *   <li>BYE → {@link CallSessionManager}</li>
 * </ul>
 */
@ApplicationScoped
public class SipCallHandler {

    @Inject
    CallAcceptor callAcceptor;

    @Inject
    DtmfDispatcher dtmfDispatcher;

    @Inject
    CallSessionManager callSessionManager;

    public void handleInvite(SipServletRequest req) throws IOException {
        this.callAcceptor.accept(req);
    }

    public void handleDtmf(SipServletRequest req) throws IOException {
        this.dtmfDispatcher.dispatch(req);
    }

    public void handleBye(SipServletRequest req) throws IOException {
        this.callSessionManager.handleBye(req);
    }
}
