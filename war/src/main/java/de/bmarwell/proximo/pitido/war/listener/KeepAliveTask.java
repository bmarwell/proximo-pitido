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

import de.bmarwell.proximo.pitido.war.protocol.ProtocolHelper;
import java.util.function.Consumer;
import javax.servlet.sip.SipFactory;

/// Sends a single OPTIONS request to the registrar to probe connectivity.
public class KeepAliveTask implements Runnable {

    private static final System.Logger LOGGER = System.getLogger(KeepAliveTask.class.getName());

    private final SipFactory sipFactory;
    /// SIP subscriber ID / phone number used to build the SIP URI. Maps to `SIP_SIPID`.
    private final String sipId;
    /// SIP registrar domain used to build the SIP URI. Maps to `SIP_REGISTRAR`.
    private final String registrar;
    private final ProtocolHelper protocolHelper;
    private final Consumer<String> errorCallback;

    public KeepAliveTask(
            SipFactory sipFactory,
            String sipId,
            String registrar,
            ProtocolHelper protocolHelper,
            Consumer<String> errorCallback) {
        this.sipFactory = sipFactory;
        this.sipId = sipId;
        this.registrar = registrar;
        this.protocolHelper = protocolHelper;
        this.errorCallback = errorCallback;
    }

    @Override
    public void run() {
        try {
            SipFactory sipFactory = this.sipFactory;

            if (sipFactory == null) {
                return;
            }

            var fromUri = sipFactory.createSipURI(this.sipId, this.registrar);
            var toUri = sipFactory.createSipURI("", this.registrar);
            var appSession = sipFactory.createApplicationSession();
            var request = sipFactory.createRequest(appSession, "OPTIONS", fromUri, toUri);
            request.setRequestURI(protocolHelper.buildRequestUri(sipFactory, this.registrar));
            request.setAddressHeader("Contact", protocolHelper.buildContactAddress(sipFactory, this.sipId));
            request.send();
            LOGGER.log(System.Logger.Level.DEBUG, "OPTIONS keep-alive sent to registrar [{0}]", this.registrar);
        } catch (Exception ex) {
            LOGGER.log(System.Logger.Level.WARNING, "OPTIONS keep-alive failed to send", ex);
            this.errorCallback.accept("OPTIONS keep-alive failed to send: " + ex.getMessage());
        }
    }
}
