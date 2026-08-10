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

import java.util.concurrent.atomic.AtomicInteger;

/// Tracks consecutive REGISTER failures and maps them to a retry delay.
///
/// This class contains no SIP container dependencies and is purely concerned with the
/// backoff policy, making it straightforward to unit-test in isolation.
///
/// ## Policy
///
/// Delays are taken from a fixed table (seconds): `[30, 60, 120]`.
/// The index into the table is the failure count, clamped to the last entry once all steps are
/// exhausted.
/// The counter is reset to zero by [#reset()] when registration succeeds.
///
/// ## Thread safety
///
/// [#nextDelaySeconds()] is atomic: it increments the counter and returns the
/// corresponding delay in a single operation, so concurrent callers each get a distinct delay
/// and counter-value without races.
/// [#reset()] is also atomic.
class RegistrationBackoffPolicy {

    /// Retry delays in seconds for failed REGISTER attempts (exponential backoff, capped at 120 s).
    /// The index is clamped to the last element once all delays are exhausted.
    /// Index 0 = first failure (30 s), index 1 = second failure (60 s), index 2+ = cap (120 s).
    /// Delay values are defined in [RegistrationBackoffPolicy#RETRY_DELAYS_SECONDS].
    static final int[] RETRY_DELAYS_SECONDS = {30, 60, 120};

    private final AtomicInteger failureCount = new AtomicInteger(0);

    /// Increments the failure counter and returns the delay in seconds to wait before the next
    /// REGISTER attempt.
    ///
    /// The delay is taken from [#RETRY_DELAYS_SECONDS], clamped to the last entry once
    /// all steps are exhausted.
    ///
    /// @return delay in seconds; always positive
    int nextDelaySeconds() {
        int count = this.failureCount.getAndIncrement();
        int index = Math.min(count, RETRY_DELAYS_SECONDS.length - 1);

        return RETRY_DELAYS_SECONDS[index];
    }

    /// Returns the current failure count without advancing it.
    /// Intended for log messages and tests.
    ///
    /// @return number of consecutive failures since the last [#reset()]
    int failureCount() {
        return this.failureCount.get();
    }

    /// Resets the failure counter to zero.
    /// Must be called after every successful REGISTER so the next failure restarts from the
    /// shortest delay.
    void reset() {
        this.failureCount.set(0);
    }
}
