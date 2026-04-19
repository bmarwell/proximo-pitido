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

import de.bmarwell.proximo.pitido.core.sip.SipDigestChallenge;
import de.bmarwell.proximo.pitido.war.listener.SipCallHandler;
import de.bmarwell.proximo.pitido.war.listener.SipRegistrationListener;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.System.Logger.Level;
import javax.inject.Inject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;

/**
 * Main SIP entry point for the Próximo Pitido application.
 *
 * <p>This servlet is intentionally thin: it dispatches each SIP method to the appropriate
 * CDI handler bean and contains no business logic itself.
 *
 * <ul>
 *   <li>Registration lifecycle (REGISTER / 401 / 200) → {@link SipRegistrationListener}</li>
 *   <li>Incoming calls (INVITE / INFO / BYE) → {@link SipCallHandler}</li>
 * </ul>
 *
 * <p>{@code loadOnStartup = 1} causes Liberty to initialise this servlet at deployment time,
 * which is required to kick off the deferred initial REGISTER.
 */
@javax.servlet.sip.annotation.SipServlet(name = "SipTimeServlet", loadOnStartup = 1, applicationName = "Proximo Pitido")
public class SipTimeServlet extends SipServlet implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final System.Logger LOGGER = System.getLogger(SipTimeServlet.class.getName());

    @Inject
    SipRegistrationListener sipRegistrationService;

    @Inject
    SipCallHandler sipCallHandler;

    @Override
    protected void doInvite(SipServletRequest req) throws ServletException, IOException {
        sipCallHandler.handleInvite(req);
    }

    @Override
    protected void doInfo(SipServletRequest req) throws ServletException, IOException {
        sipCallHandler.handleDtmf(req);
    }

    @Override
    protected void doBye(SipServletRequest req) throws ServletException, IOException {
        sipCallHandler.handleBye(req);
    }

    /**
     * Handles SIP responses. A {@code 401 Unauthorized} or {@code 407 Proxy Authentication Required}
     * from the registrar triggers a re-REGISTER with Digest credentials.
     * A {@code 200 OK} for a REGISTER confirms successful registration.
     */
    @Override
    protected void doResponse(SipServletResponse response) throws ServletException, IOException {
        int status = response.getStatus();
        String method = response.getMethod();
        LOGGER.log(System.Logger.Level.INFO, "SIP response [{0}] for method [{1}]", status, method);

        if (!"REGISTER".equals(method)) {
            return;
        }
        if (status == SipServletResponse.SC_UNAUTHORIZED
                || status == SipServletResponse.SC_PROXY_AUTHENTICATION_REQUIRED) {
            handleAuthChallenge(response);
            return;
        }
        if (status == SipServletResponse.SC_OK) {
            int grantedExpires = this.sipRegistrationService.resolveGrantedExpires(response);
            this.sipRegistrationService.markRegistered(grantedExpires);
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "SIP registration completed successfully (status [{0}], granted-expires=[{1}s])",
                    status,
                    grantedExpires);
            return;
        }

        LOGGER.log(System.Logger.Level.INFO, "SIP registration completed unsuccessfully (status [{0}])", status);

        LOGGER.log(System.Logger.Level.WARNING, "Unexpected REGISTER response [{0}]: {1}", status, response);
    }

    private void handleAuthChallenge(SipServletResponse response) {
        SipFactory sipFactory = (SipFactory) getServletContext().getAttribute(SipFactory.class.getName());
        if (isAlreadyAuthenticated(response)) {
            handleAlreadyAuthedChallenge(response, sipFactory);
            return;
        }
        this.sipRegistrationService.registerWithAuth(response, sipFactory);
    }

    private boolean isAlreadyAuthenticated(SipServletResponse response) {
        var req = response.getRequest();
        return req.getHeader("Authorization") != null || req.getHeader("Proxy-Authorization") != null;
    }

    private void handleAlreadyAuthedChallenge(SipServletResponse response, SipFactory sipFactory) {
        SipDigestChallenge challenge = parseChallengeHeader(response);
        if (challenge == null) {
            LOGGER.log(System.Logger.Level.ERROR, "Auth rejected with no challenge header — check credentials");
            return;
        }
        if (challenge.stale() && this.sipRegistrationService.canRetryAfterStale()) {
            LOGGER.log(System.Logger.Level.WARNING, "Auth nonce was stale (consumed by prior session); retrying");
            this.sipRegistrationService.resetAuthState();
            this.sipRegistrationService.registerWithAuth(response, sipFactory);
            return;
        }
        LOGGER.log(
                System.Logger.Level.ERROR,
                "REGISTER auth rejected (stale={0}) — check SIP_USER_ID / SIP_USER_PASSWORD",
                challenge.stale());
    }

    private SipDigestChallenge parseChallengeHeader(SipServletResponse response) {
        String wwwAuth = response.getHeader("WWW-Authenticate");
        if (wwwAuth == null) {
            wwwAuth = response.getHeader("Proxy-Authenticate");
        }
        if (wwwAuth == null) {
            return null;
        }
        return SipDigestChallenge.parse(wwwAuth);
    }

    @Override
    public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        LOGGER.log(Level.INFO, "SipTimeServlet initialised — scheduling SIP registration");
        sipRegistrationService.scheduleRegistration(getServletContext());
    }
}
