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

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Rejects incoming SIP calls whose caller identity or User-Agent is on the configured blacklist.
 *
 * <p>Two lists are supported, both configured as comma-separated strings via MicroProfile Config:
 * <ul>
 *   <li>{@code sip.blacklist.from.users} — exact SIP user-part matches from the {@code From} header
 *       (e.g. {@code test,spam}).</li>
 *   <li>{@code sip.blacklist.user.agents} — User-Agent prefix matches
 *       (e.g. {@code Z } to block all Z-softphone versions).</li>
 * </ul>
 *
 * <p>Both lists default to empty (no calls blocked).
 * Override them in {@code bootstrap.properties}, environment variables
 * ({@code SIP_BLACKLIST_FROM_USERS}, {@code SIP_BLACKLIST_USER_AGENTS}), or any other
 * MicroProfile Config source.
 */
@ApplicationScoped
public class SipCallBlacklist {

    private static final System.Logger LOGGER = System.getLogger(SipCallBlacklist.class.getName());
    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^\\+?[0-9]{3,20}$");
    private static final Pattern SIP_USER_PATTERN = Pattern.compile("sip:([^@>;]+)");

    @Inject
    @ConfigProperty(name = "sip.blacklist.from.users", defaultValue = "")
    String fromUsersConfig;

    @Inject
    @ConfigProperty(name = "sip.blacklist.user.agents", defaultValue = "")
    String userAgentsConfig;

    private List<String> fromUsers;
    private List<String> userAgentPrefixes;

    @PostConstruct
    void init() {
        this.fromUsers = parseCommaSeparated(this.fromUsersConfig);
        this.userAgentPrefixes = parseCommaSeparated(this.userAgentsConfig);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Call blacklist initialised — blocked from-users: {0}, blocked user-agent prefixes: {1}",
                this.fromUsers,
                this.userAgentPrefixes);
    }

    /**
     * Returns {@code true} if the request should be rejected.
     * Checks the {@code User-Agent} header and the user part of the {@code From} URI.
     */
    public boolean isBlacklisted(SipServletRequest req) {
        String userAgent = req.getHeader("User-Agent");

        if (userAgent != null && isBlockedUserAgent(userAgent)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Blacklisted call from [{0}] — User-Agent [{1}] is blocked",
                    req.getFrom(),
                    userAgent);
            return true;
        }

        Address from = req.getFrom();

        if (from != null && from.getURI() instanceof SipURI sipUri) {
            String user = sipUri.getUser();

            if (user != null && isBlockedFromUser(user)) {
                LOGGER.log(
                        System.Logger.Level.DEBUG,
                        "Blacklisted call from [{0}] — From user [{1}] is blocked",
                        req.getFrom(),
                        user);
                return true;
            }
        }

        if (isTrustedPhoneCaller(req)) {
            return false;
        }

        LOGGER.log(
                System.Logger.Level.DEBUG,
                "Blacklisted call from [{0}] — caller is not a trusted phone identity",
                req.getFrom());
        return true;
    }

    private boolean isBlockedUserAgent(String userAgent) {
        return this.userAgentPrefixes.stream().anyMatch(userAgent::startsWith);
    }

    private boolean isBlockedFromUser(String user) {
        return this.fromUsers.contains(user);
    }

    private static boolean isTrustedPhoneCaller(SipServletRequest req) {
        if (hasAssertedPhoneIdentity(req)) {
            return true;
        }

        Address from = req.getFrom();

        if (!(from != null && from.getURI() instanceof SipURI sipUri)) {
            return false;
        }

        String user = sipUri.getUser();

        if (user == null || !isPhoneNumber(user)) {
            return false;
        }

        String userParam = sipUri.getParameter("user");

        if (userParam == null) {
            return false;
        }

        return "phone".equalsIgnoreCase(userParam);
    }

    private static boolean hasAssertedPhoneIdentity(SipServletRequest req) {
        String pAssertedIdentity = req.getHeader("P-Asserted-Identity");

        if (pAssertedIdentity == null || pAssertedIdentity.isBlank()) {
            return false;
        }

        Matcher matcher = SIP_USER_PATTERN.matcher(pAssertedIdentity);

        if (!matcher.find()) {
            return false;
        }

        return isPhoneNumber(matcher.group(1));
    }

    private static boolean isPhoneNumber(String candidate) {
        return PHONE_NUMBER_PATTERN.matcher(candidate).matches();
    }

    private static List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
