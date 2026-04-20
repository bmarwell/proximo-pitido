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

import java.time.Instant;
import java.util.List;

/**
 * A receipt of every audio resource submitted for playback during a single time announcement.
 *
 * <p>Returned by {@link TimeAnnouncement#announce()} so that tests can verify which files
 * would be played without needing real audio hardware, a sound card, or an RTP stream.
 * Servers are headless — and as a wise Python script once discovered, invoking {@code vlc}
 * there results in nothing but silence (and a crash). 🐍🔇
 *
 * <p>Each {@link PlayedResource} entry carries the wall-clock start instant, the actual
 * playback duration, and the resource name.
 * In unit tests, duration is effectively zero because
 * {@link de.bmarwell.proximo.pitido.testsupport.RecordingAudioPlayer} returns instantly.
 * In production, duration reflects real RTP streaming time and can be used to diagnose
 * timing problems such as speech phrases that overrun the stroke-timing window.
 *
 * <p>Use {@link #fileNames()} when only the ordered list of resource paths is needed —
 * for example in assertions that check which files were selected without caring about timing.
 *
 * @param resources ordered list of played resources; never {@code null}
 */
public record PlaybackReceipt(List<PlayedResource> resources) {

    /** Canonical constructor; defends against mutable list references. */
    public PlaybackReceipt {
        resources = List.copyOf(resources);
    }

    /**
     * Returns a human-readable summary of every played resource, showing per-entry start offset,
     * duration, and the gap since the previous entry ended.
     *
     * <p>Offsets are relative to the start of the first resource.
     * Example output (one line per resource):
     * <pre>
     * PlaybackReceipt[
     *   +0ms     announcement.opus  dur=1240ms
     *   +1240ms  014.opus           dur=156ms   gap=+0ms
     *   +8500ms  stroke1.opus       dur=82ms    gap=+7104ms
     *   +9330ms  stroke2.opus       dur=81ms    gap=+748ms
     *   +10160ms stroke3.opus       dur=80ms    gap=+749ms
     * ]
     * </pre>
     *
     * @return formatted multi-line string; never {@code null}
     */
    @Override
    public String toString() {
        if (this.resources.isEmpty()) {
            return "PlaybackReceipt[]";
        }

        Instant callStart = this.resources.get(0).start();
        StringBuilder sb = new StringBuilder("PlaybackReceipt[\n");

        Instant previousEnd = callStart;

        for (PlayedResource entry : this.resources) {
            long offsetMs = entry.start().toEpochMilli() - callStart.toEpochMilli();
            long durationMs = entry.duration().toMillis();
            long gapMs = entry.start().toEpochMilli() - previousEnd.toEpochMilli();
            String shortName = shortName(entry.resourceName());

            sb.append(String.format("  +%-8d %-30s dur=%dms", offsetMs, shortName, durationMs));

            if (entry.start() != callStart) {
                sb.append(String.format("  gap=%+dms", gapMs));
            }

            sb.append('\n');
            previousEnd = entry.end();
        }

        sb.append(']');

        return sb.toString();
    }

    private static String shortName(String resourceName) {
        int slash = resourceName.lastIndexOf('/');

        if (slash >= 0) {
            return resourceName.substring(slash + 1);
        } else {
            return resourceName;
        }
    }

    /**
     * Returns only the resource names from each {@link PlayedResource}, in playback order.
     *
     * <p>Convenience view for tests and logging.
     * Equivalent to {@code resources().stream().map(PlayedResource::resourceName).toList()}.
     *
     * @return ordered list of classpath-relative resource paths; never {@code null}
     */
    public List<String> fileNames() {
        return this.resources.stream().map(PlayedResource::resourceName).toList();
    }
}
