/**
 * Test-support utilities for Próximo Pitido.
 *
 * <p>This module provides test doubles (e.g. {@code RecordingAudioPlayer})
 * used across unit and integration tests.
 * It is not deployed to production.
 */
module de.bmarwell.proximo.pitido.testsupport {
    requires de.bmarwell.proximo.pitido.api;

    exports de.bmarwell.proximo.pitido.testsupport;
}
