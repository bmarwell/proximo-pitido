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

class SipRegistrationListenerTest {

    @Test
    void markRegistered_setsRegisteredState() {
        // given
        var listener = new SipRegistrationListener();

        // when
        listener.markRegistered();

        // then
        assertTrue(listener.isRegistered());
    }

    @Test
    void markRegistered_doesNotScheduleReRegistration_whenServletContextIsNull() {
        // given: no servletContext set — simulates a plain unit test without a SIP container
        var listener = new SipRegistrationListener();

        // when
        listener.markRegistered();

        // then: state is REGISTERED, no background thread was launched (would loop forever)
        assertTrue(listener.isRegistered());
        assertFalse(listener.isIdle());
    }

    @Test
    void resetForReRegistration_fromRegistered_resetsToIdle() {
        // given
        var listener = new SipRegistrationListener();
        listener.markRegistered();

        // when
        listener.resetForReRegistration();

        // then
        assertTrue(listener.isIdle());
        assertFalse(listener.isRegistered());
    }

    @Test
    void resetForReRegistration_allowsStaleRetryAgain() {
        // given: a previous registration cycle consumed the stale-retry allowance
        var listener = new SipRegistrationListener();
        listener.markRegistered();
        listener.canRetryAfterStale(); // consumes the allowance

        // when
        listener.resetForReRegistration();

        // then: stale-retry allowance is restored for the new cycle
        assertTrue(listener.canRetryAfterStale());
    }
}
