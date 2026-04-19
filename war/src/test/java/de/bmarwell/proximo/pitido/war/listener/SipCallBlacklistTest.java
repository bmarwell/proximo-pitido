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

import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipURI;
import org.junit.jupiter.api.Test;

class SipCallBlacklistTest {

    private static SipCallBlacklist blacklist(String fromUsers, String userAgents) {
        var bl = new SipCallBlacklist();
        bl.fromUsersConfig = fromUsers;
        bl.userAgentsConfig = userAgents;
        bl.init();
        return bl;
    }

    @Test
    void blocksCallByFromUser() {
        // given
        var bl = blacklist("test,spam", "");
        var req = mockRequest("test", null);

        // when
        boolean result = bl.isBlacklisted(req);

        // then
        assertTrue(result);
    }

    @Test
    void blocksCallByUserAgentPrefix() {
        // given
        var bl = blacklist("", "Z ");
        var req = mockRequest("alice", "Z 5.6.4 v2.10.20.4");

        // when
        boolean result = bl.isBlacklisted(req);

        // then
        assertTrue(result);
    }

    @Test
    void allowsCallWhenBlacklistIsEmpty() {
        // given
        var bl = blacklist("", "");
        var req = mockRequest("alice", "Linphone/5.0");

        // when
        boolean result = bl.isBlacklisted(req);

        // then
        assertFalse(result);
    }

    @Test
    void allowsCallWhenNeitherUserNorAgentMatch() {
        // given
        var bl = blacklist("test", "Z ");
        var req = mockRequest("alice", "Linphone/5.0");

        // when
        boolean result = bl.isBlacklisted(req);

        // then
        assertFalse(result);
    }

    @Test
    void blocksKnownPolycomBotByFromUser() {
        // given
        var blacklist = blacklist("13216220427", "");
        var request = mockRequest("13216220427", "PolycomSoundPointIP-SPIP_335-UA/3.3.1.0907");

        // when
        boolean result = blacklist.isBlacklisted(request);

        // then
        assertTrue(result);
    }

    private static SipServletRequest mockRequest(String fromUser, String userAgent) {
        var req = mock(SipServletRequest.class);
        var sipUri = mock(SipURI.class);
        var from = mock(Address.class);
        when(sipUri.getUser()).thenReturn(fromUser);
        when(from.getURI()).thenReturn(sipUri);
        when(req.getFrom()).thenReturn(from);
        when(req.getHeader("User-Agent")).thenReturn(userAgent);
        return req;
    }
}
