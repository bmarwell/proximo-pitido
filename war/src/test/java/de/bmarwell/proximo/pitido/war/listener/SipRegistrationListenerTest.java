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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.servlet.sip.Address;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import org.junit.jupiter.api.Test;

class SipRegistrationListenerTest {

    /**
     * Creates a {@link SipRegistrationListener} whose {@code managedScheduledExecutorService}
     * is replaced with a no-op stub so that tests that exercise retry-scheduling do not NPE.
     */
    @SuppressWarnings("unchecked")
    private static SipRegistrationListener listenerWithStubScheduler() {
        var listener = new SipRegistrationListener();
        var scheduler = mock(ManagedScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenReturn(mock(ScheduledFuture.class));
        listener.managedScheduledExecutorService = scheduler;
        return listener;
    }

    @Test
    void markRegistered_setsRegisteredState() {
        // given
        var listener = new SipRegistrationListener();

        // when
        listener.markRegistered(-1);

        // then
        assertTrue(listener.isRegistered());
    }

    @Test
    void markRegistered_withGrantedExpires_setsRegisteredState() {
        // given: registrar grants a shorter expiry (e.g. Deutsche Telekom grants 60 s)
        var listener = new SipRegistrationListener();

        // when
        listener.markRegistered(60);

        // then: still reaches REGISTERED state despite short granted expiry
        assertTrue(listener.isRegistered());
        assertFalse(listener.isIdle());
    }

    @Test
    void markRegistered_doesNotScheduleReRegistration_whenServletContextIsNull() {
        // given: no servletContext set — simulates a plain unit test without a SIP container
        var listener = new SipRegistrationListener();

        // when
        listener.markRegistered(-1);

        // then: state is REGISTERED, no background thread was launched (would loop forever)
        assertTrue(listener.isRegistered());
        assertFalse(listener.isIdle());
    }

    @Test
    void resetForReRegistration_fromRegistered_resetsToIdle() {
        // given
        var listener = new SipRegistrationListener();
        listener.markRegistered(-1);

        // when
        listener.resetForReRegistration();

        // then
        assertTrue(listener.isIdle());
        assertFalse(listener.isRegistered());
    }

    @Test
    void canRetryAfterStale_returnsTrueFirstTime_thenFalse() {
        // given
        var listener = new SipRegistrationListener();

        // when / then: exactly one stale retry is permitted
        assertTrue(listener.canRetryAfterStale());
        assertFalse(listener.canRetryAfterStale());
    }

    @Test
    void resetForReRegistration_allowsStaleRetryAgain() {
        // given: a previous registration cycle consumed the stale-retry allowance
        var listener = new SipRegistrationListener();
        listener.markRegistered(-1);
        listener.canRetryAfterStale(); // consumes the allowance

        // when
        listener.resetForReRegistration();

        // then: stale-retry allowance is restored for the new cycle
        assertTrue(listener.canRetryAfterStale());
    }

    @Test
    void resolveGrantedExpires_usesMatchingContactExpires_whenHeaderIsMissing() throws Exception {
        // given
        var listener = new SipRegistrationListener();
        listener.sipId = java.util.Optional.of("051143820934");
        SipServletResponse response = mock(SipServletResponse.class);
        Address contactAddress = mock(Address.class);
        SipURI sipUri = mock(SipURI.class);
        when(response.getExpires()).thenReturn(-1);
        when(response.getAddressHeaders("Contact"))
                .thenReturn(List.of(contactAddress).listIterator());
        when(contactAddress.getExpires()).thenReturn(60);
        when(contactAddress.getURI()).thenReturn(sipUri);
        when(sipUri.getUser()).thenReturn("051143820934");

        // when
        int grantedExpires = listener.resolveGrantedExpires(response);

        // then
        assertEquals(60, grantedExpires);
    }

    @Test
    void resolveGrantedExpires_fallsBackToHeaderExpires_whenContactsAreAbsent() throws Exception {
        // given
        var listener = new SipRegistrationListener();
        SipServletResponse response = mock(SipServletResponse.class);
        when(response.getExpires()).thenReturn(120);
        when(response.getAddressHeaders("Contact"))
                .thenReturn(List.<Address>of().listIterator());

        // when
        int grantedExpires = listener.resolveGrantedExpires(response);

        // then
        assertEquals(120, grantedExpires);
    }

    @Test
    void markRegistered_resetsBackoffPolicy() {
        // given: two prior failures have been recorded
        var listener = new SipRegistrationListener();
        listener.backoffPolicy.nextDelaySeconds();
        listener.backoffPolicy.nextDelaySeconds();

        // when
        listener.markRegistered(-1);

        // then: failure counter is reset; next delay is back to the shortest step
        assertEquals(0, listener.backoffPolicy.failureCount());
        assertEquals(30, listener.backoffPolicy.nextDelaySeconds());
    }

    @Test
    void handleOptionsResponse_2xx_leavesStateUnchanged() {
        // given
        var listener = new SipRegistrationListener();
        listener.markRegistered(-1);

        // when
        listener.handleOptionsResponse(200);

        // then: still registered; no retry triggered
        assertTrue(listener.isRegistered());
    }

    @Test
    void handleOptionsResponse_408_triggersRetryAndResetsToIdle() {
        // given
        var listener = listenerWithStubScheduler();
        listener.markRegistered(-1);

        // when
        listener.handleOptionsResponse(408);

        // then: state reset to idle so a fresh registration cycle can begin
        assertTrue(listener.isIdle());
        assertFalse(listener.isRegistered());
    }

    @Test
    void scheduleRetryAfterFailure_idempotent_whenAlreadyIdle() {
        // given: state is already IDLE — simulates a second concurrent failure arriving after
        // the first caller already reset state to IDLE
        var listener = listenerWithStubScheduler();
        listener.markRegistered(-1); // state = REGISTERED

        // when: first caller transitions REGISTERED → IDLE and schedules retry
        listener.scheduleRetryAfterFailure("first");
        // second caller arrives after state is already IDLE — CAS fails, it is a no-op
        listener.scheduleRetryAfterFailure("second");

        // then: failure count was incremented only once (second call was dropped by CAS)
        assertEquals(1, listener.backoffPolicy.failureCount());
    }
}
