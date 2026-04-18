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
package de.bmarwell.proximo.pitido.api;

import java.util.List;

/**
 * A minimal receipt of what audio was played during a single time announcement.
 *
 * <p>Returned by {@link TimeAnnouncement#announce()} so that tests can verify which files
 * would be played without needing real audio hardware, a sound card, or an RTP stream.
 * Servers are headless — and as a wise Python script once discovered, invoking {@code vlc}
 * there results in nothing but silence (and a crash). 🐍🔇
 *
 * <p>{@link #fileNames()} contains the classpath-relative resource paths in the order they
 * were submitted to {@link AudioPlayer#playBlocking}, with one entry per call.
 *
 * @param fileNames ordered list of resource paths submitted to the audio player; never {@code null}
 */
public record PlaybackReceipt(List<String> fileNames) {

    /** Canonical constructor; defends against mutable list references. */
    public PlaybackReceipt {
        fileNames = List.copyOf(fileNames);
    }
}
