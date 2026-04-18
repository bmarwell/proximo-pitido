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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.enterprise.context.ApplicationScoped;

/**
 * Computes RFC 2617 Digest MD5 {@code Authorization} header values for SIP REGISTER requests.
 *
 * <p>The Digest URI passed to {@link #buildAuthorizationHeader} should be {@code sip:<registrar>}
 * (the SIP domain, e.g. {@code sip:sip.example.com}), not the SRV-resolved hostname. RFC 2617
 * requires the URI in the hash to match the URI in the request line; for REGISTER the request
 * target is the registrar domain.
 */
@ApplicationScoped
public class DigestMd5Computer {

    private final SecureRandom secureRandom;

    /** CDI no-arg constructor. */
    public DigestMd5Computer() {
        this(new SecureRandom());
    }

    /** Package-private constructor for testing with a deterministic {@link SecureRandom}. */
    DigestMd5Computer(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * Builds a complete {@code Authorization} header value for a REGISTER challenge.
     *
     * @param username   the auth username (e.g. user e-mail or phone number)
     * @param password   the auth password
     * @param challenge  the parsed Digest challenge from the {@code 401} response
     * @param digestUri  the Digest URI, typically {@code sip:<registrar>}
     * @return a value suitable for {@code request.setHeader("Authorization", ...)}
     */
    public String buildAuthorizationHeader(
            String username, String password, SipDigestChallenge challenge, String digestUri) {
        String ha1 = hexMd5(username + ":" + challenge.realm() + ":" + password);
        String ha2 = hexMd5("REGISTER:" + digestUri);

        if (challenge.hasQopAuth()) {
            return buildWithQop(username, challenge, digestUri, ha1, ha2);
        }
        return buildWithoutQop(username, challenge, digestUri, ha1, ha2);
    }

    private String buildWithQop(
            String username, SipDigestChallenge challenge, String digestUri, String ha1, String ha2) {
        String nc = "00000001";
        String cnonce = generateCnonce();
        String response = hexMd5(ha1 + ":" + challenge.nonce() + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        return "Digest username=\"" + username + "\","
                + "realm=\"" + challenge.realm() + "\","
                + "nonce=\"" + challenge.nonce() + "\","
                + "uri=\"" + digestUri + "\","
                + "qop=auth,"
                + "nc=" + nc + ","
                + "cnonce=\"" + cnonce + "\","
                + "response=\"" + response + "\","
                + "algorithm=MD5";
    }

    private String buildWithoutQop(
            String username, SipDigestChallenge challenge, String digestUri, String ha1, String ha2) {
        String response = hexMd5(ha1 + ":" + challenge.nonce() + ":" + ha2);
        return "Digest username=\"" + username + "\","
                + "realm=\"" + challenge.realm() + "\","
                + "nonce=\"" + challenge.nonce() + "\","
                + "uri=\"" + digestUri + "\","
                + "response=\"" + response + "\","
                + "algorithm=MD5";
    }

    private String generateCnonce() {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Computes the lowercase hex-encoded MD5 hash of {@code input} (UTF-8). */
    static String hexMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("MD5 not available", noSuchAlgorithmException);
        }
    }
}
