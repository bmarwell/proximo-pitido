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

import java.util.Locale;
import javax.servlet.sip.SipServletRequest;

/**
 * Static utility methods for formatting SIP header values and log-line prefixes.
 *
 * <p>These are pure functions with no state, shared by all call-handler beans.
 */
final class SipCallHeaders {

    private SipCallHeaders() {}

    static String callPrefix(String callId) {
        return "[callId=" + callId + "] ";
    }

    static String buildCallerIdentitySummary(SipServletRequest req) {
        return String.format(
                Locale.ROOT,
                "from=[%s], to=[%s], requestUri=[%s], callId=[%s], pAssertedIdentity=[%s], remotePartyId=[%s], "
                        + "pPreferredIdentity=[%s], privacy=[%s], diversion=[%s], historyInfo=[%s], contact=[%s], "
                        + "via=[%s], userAgent=[%s]",
                req.getFrom(),
                req.getTo(),
                req.getRequestURI(),
                normaliseHeader(req.getHeader("Call-ID")),
                normaliseHeader(req.getHeader("P-Asserted-Identity")),
                normaliseHeader(req.getHeader("Remote-Party-ID")),
                normaliseHeader(req.getHeader("P-Preferred-Identity")),
                normaliseHeader(req.getHeader("Privacy")),
                normaliseHeader(req.getHeader("Diversion")),
                normaliseHeader(req.getHeader("History-Info")),
                normaliseHeader(req.getHeader("Contact")),
                normaliseHeader(req.getHeader("Via")),
                normaliseHeader(req.getHeader("User-Agent")));
    }

    static String normaliseHeader(String value) {
        if (value == null) {
            return "<absent>";
        }

        if (value.isBlank()) {
            return "<blank>";
        }

        return value;
    }
}
