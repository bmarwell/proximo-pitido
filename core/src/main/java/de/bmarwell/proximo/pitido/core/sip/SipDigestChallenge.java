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
package de.bmarwell.proximo.pitido.core.sip;

import java.util.Locale;

/**
 * Parsed representation of a SIP {@code WWW-Authenticate} or {@code Proxy-Authenticate}
 * Digest challenge header.
 *
 * <p>Example input:
 * {@code Digest algorithm=MD5,realm="tel.t-online.de",nonce="abc123",qop="auth"}
 *
 * @param realm  The authentication realm (used in HA1 computation).
 * @param nonce  The server-issued nonce value.
 * @param qop    The quality-of-protection value (e.g. {@code "auth"}), or {@code null} if absent.
 * @param stale  {@code true} if the server indicated the nonce was stale (consumed by a prior session).
 */
public record SipDigestChallenge(String realm, String nonce, String qop, boolean stale) {

    /**
     * Parses a {@code WWW-Authenticate} or {@code Proxy-Authenticate} header value into a challenge.
     *
     * @param header the raw header value; must not be {@code null}
     * @throws IllegalArgumentException if realm or nonce are missing
     */
    public static SipDigestChallenge parse(String header) {
        String realm = requireParam(header, "realm");
        String nonce = requireParam(header, "nonce");
        String qop = extractParam(header, "qop");
        boolean stale = header.toLowerCase(Locale.ROOT).contains("stale=true");
        return new SipDigestChallenge(realm, nonce, qop, stale);
    }

    /** Returns {@code true} if {@code qop} is present and contains {@code "auth"}. */
    public boolean hasQopAuth() {
        return qop != null && qop.contains("auth");
    }

    private static String requireParam(String header, String param) {
        String value = extractParam(header, param);
        if (value == null) {
            throw new IllegalArgumentException("Missing '" + param + "' in Digest challenge: " + header);
        }
        return value;
    }

    /**
     * Extracts a quoted or unquoted parameter value from a Digest header.
     * Returns {@code null} if the parameter is absent.
     */
    static String extractParam(String header, String param) {
        int idx = header.indexOf(param + "=");
        if (idx < 0) {
            return null;
        }
        int start = idx + param.length() + 1;
        if (start >= header.length()) {
            return null;
        }
        if (header.charAt(start) == '"') {
            int end = header.indexOf('"', start + 1);
            return end > start ? header.substring(start + 1, end) : null;
        }
        int end = header.indexOf(',', start);
        String val = end > start ? header.substring(start, end) : header.substring(start);
        return val.trim();
    }
}
