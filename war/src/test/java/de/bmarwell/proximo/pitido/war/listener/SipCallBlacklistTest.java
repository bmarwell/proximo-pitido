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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import org.junit.jupiter.api.Test;

class SipCallBlacklistTest {

    private static SipCallBlacklist blocklist(String fromUsers, String userAgents) {
        var bl = new SipCallBlacklist();
        bl.fromUsersConfig = Optional.ofNullable(fromUsers);
        bl.userAgentsConfig = Optional.ofNullable(userAgents);
        bl.init();
        return bl;
    }

    @Test
    void empty_string_or_null_is_empty_list() {
        // given
        final SipCallBlacklist blocklist = blocklist(null, null);

        // when
        final boolean blocklisted = blocklist.isBlocklisted(mockRequest("any", "any", null, null));

        // then
        assertTrue(blocklist.getFromUsers().isEmpty());
        assertTrue(blocklist.getUserAgentPrefixes().isEmpty());
        assertFalse(blocklisted);

        // given
        final var blocklist2 = blocklist("", "");

        // when
        final var blocklisted2 = blocklist2.isBlocklisted(mockRequest("any", "any", null, null));

        // then
        assertTrue(blocklist.getFromUsers().isEmpty());
        assertTrue(blocklist.getUserAgentPrefixes().isEmpty());
        assertFalse(blocklisted2);
    }

    @Test
    void blocksCallByFromUser() {
        // given
        var bl = blocklist("test,spam", "");
        var req = mockRequest("test", null, null, null);

        // when
        boolean result = bl.isBlocklisted(req);

        // then
        assertTrue(result);
    }

    @Test
    void blocksCallByUserAgentPrefix() {
        // given
        var bl = blocklist("", "Z ");
        var req = mockRequest("alice", "Z 5.6.4 v2.10.20.4", null, null);

        // when
        boolean result = bl.isBlocklisted(req);

        // then
        assertTrue(result);
    }

    @Test
    void allowsCallWithAssertedPhoneIdentityWhenBlocklistIsEmpty() {
        // given
        var bl = blocklist("", "");
        var req = mockRequest(
                "+491707139317", null, "phone", "<sip:+491707139317@1und1-mobilfunk.de;user=phone;transport=udp>");

        // when
        boolean result = bl.isBlocklisted(req);

        // then
        assertFalse(result);
    }

    @Test
    void allowsCallWithFromUserPhoneParamWhenNeitherUserNorAgentMatch() {
        // given
        var bl = blocklist("test", "Z ");
        var req = mockRequest("+491707139317", null, "phone", null);

        // when
        boolean result = bl.isBlocklisted(req);

        // then
        assertFalse(result);
    }

    @Test
    void blocksKnownPolycomBotByFromUser() {
        // given
        var blocklist = blocklist("13216220427", "");
        var request = mockRequest("13216220427", "PolycomSoundPointIP-SPIP_335-UA/3.3.1.0907", null, null);

        // when
        boolean result = blocklist.isBlocklisted(request);

        // then
        assertTrue(result);
    }

    @Test
    void blocksSipBotWithoutAssertedOrPhoneIdentity() {
        // given
        var blocklist = blocklist("13216220427", "Z");
        var request = mockRequest("1001", "Cisco-SIPGateway/IOS-12.x", null, null);

        // when
        boolean result = blocklist.isBlocklisted(request);

        // then
        assertTrue(result);
    }

    private static SipServletRequest mockRequest(
            String fromUser, String userAgent, String userParam, String pAssertedIdentity) {
        var req = mock(SipServletRequest.class);
        var sipUri = mock(SipURI.class);
        var from = mock(Address.class);
        when(sipUri.getUser()).thenReturn(fromUser);
        when(sipUri.getParameter("user")).thenReturn(userParam);
        when(from.getURI()).thenReturn(sipUri);
        when(req.getFrom()).thenReturn(from);
        when(req.getHeader("User-Agent")).thenReturn(userAgent);
        when(req.getHeader("P-Asserted-Identity")).thenReturn(pAssertedIdentity);
        return req;
    }
}
