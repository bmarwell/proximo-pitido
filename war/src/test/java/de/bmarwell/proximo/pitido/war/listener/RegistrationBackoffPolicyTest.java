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

import org.junit.jupiter.api.Test;

class RegistrationBackoffPolicyTest {

    @Test
    void firstFailure_returns30Seconds() {
        // given
        var policy = new RegistrationBackoffPolicy();

        // when
        int delay = policy.nextDelaySeconds();

        // then
        assertEquals(30, delay);
    }

    @Test
    void secondFailure_returns60Seconds() {
        // given
        var policy = new RegistrationBackoffPolicy();
        policy.nextDelaySeconds(); // first failure

        // when
        int delay = policy.nextDelaySeconds();

        // then
        assertEquals(60, delay);
    }

    @Test
    void thirdFailure_returns120Seconds() {
        // given
        var policy = new RegistrationBackoffPolicy();
        policy.nextDelaySeconds(); // first
        policy.nextDelaySeconds(); // second

        // when
        int delay = policy.nextDelaySeconds();

        // then
        assertEquals(120, delay);
    }

    @Test
    void furtherFailures_capAt120Seconds() {
        // given
        var policy = new RegistrationBackoffPolicy();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();

        // when: sixth failure — well past the end of the table
        int delay = policy.nextDelaySeconds();

        // then
        assertEquals(120, delay);
    }

    @Test
    void reset_restoresFirstDelay() {
        // given: two failures already recorded
        var policy = new RegistrationBackoffPolicy();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();

        // when
        policy.reset();
        int delay = policy.nextDelaySeconds();

        // then: back to the first step (30 s)
        assertEquals(30, delay);
    }

    @Test
    void failureCount_reflectsIncrements() {
        // given
        var policy = new RegistrationBackoffPolicy();

        // when
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();

        // then
        assertEquals(2, policy.failureCount());
    }

    @Test
    void failureCount_resetsToZero_afterReset() {
        // given
        var policy = new RegistrationBackoffPolicy();
        policy.nextDelaySeconds();
        policy.nextDelaySeconds();

        // when
        policy.reset();

        // then
        assertEquals(0, policy.failureCount());
    }
}
