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

import org.junit.jupiter.api.Test;

class CallSessionManagerTest {

    @Test
    void tryClaimSipCallId_firstForkSucceeds() {
        // given
        var manager = new CallSessionManager();

        // when
        boolean claimed = manager.tryClaimSipCallId("call-id-1", "wlp_2_2");

        // then
        assertTrue(claimed);
    }

    @Test
    void tryClaimSipCallId_duplicateForkIsRejected() {
        // given
        var manager = new CallSessionManager();
        manager.tryClaimSipCallId("call-id-1", "wlp_2_2");

        // when — second fork with the same SIP Call-ID
        boolean claimed = manager.tryClaimSipCallId("call-id-1", "wlp_3_3");

        // then
        assertFalse(claimed);
    }

    @Test
    void tryClaimSipCallId_differentCallIdsAreIndependent() {
        // given
        var manager = new CallSessionManager();
        manager.tryClaimSipCallId("call-id-1", "wlp_2_2");

        // when — a different call (different SIP Call-ID)
        boolean claimed = manager.tryClaimSipCallId("call-id-2", "wlp_4_4");

        // then
        assertTrue(claimed);
    }

    @Test
    void releaseSipCallId_allowsReclaimAfterRelease() {
        // given
        var manager = new CallSessionManager();
        manager.tryClaimSipCallId("call-id-1", "wlp_2_2");
        manager.releaseSipCallId("call-id-1");

        // when — next call with the same SIP Call-ID (e.g. caller rings again)
        boolean claimed = manager.tryClaimSipCallId("call-id-1", "wlp_6_6");

        // then
        assertTrue(claimed);
    }
}
