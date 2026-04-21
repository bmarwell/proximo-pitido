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

import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import java.time.Instant;
import java.util.SequencedMap;
import java.util.concurrent.Future;
import javax.servlet.sip.SipSession;

/**
 * Immutable snapshot of per-call state while a call is active.
 *
 * <p>Stored in {@link CallSessionManager} and accessed by all handler beans that need to
 * cancel futures, close media, or determine which language was selected.
 */
record CallState(
        SipSession session,
        Future<?> callFuture,
        Future<?> receiverFuture,
        SequencedMap<Integer, LanguageFactory> menu,
        CallMedia media,
        Instant startTime,
        String callerIdentitySummary) {}
