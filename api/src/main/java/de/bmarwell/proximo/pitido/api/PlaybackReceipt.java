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
import java.util.StringJoiner;

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
     * Returns a compact, single-line summary of every played resource.
     *
     * <p>Each entry is formatted as {@code name(durationMs)}.
     * Gaps larger than 20 ms between consecutive entries are shown as {@code ~Nms~} immediately
     * before the entry that follows the gap.
     * Example:
     * <pre>
     * PlaybackReceipt[announcement.opus(1240ms), 014.opus(156ms), 130_minutes.opus(892ms),
     *   220_seconds.opus(712ms), ~7104ms~, stroke1.opus(82ms), ~748ms~, stroke2.opus(81ms),
     *   ~749ms~, stroke3.opus(80ms)]
     * </pre>
     * (Line breaks above are for readability; actual output is a single line.)
     *
     * @return single-line formatted string; never {@code null}
     */
    @Override
    public String toString() {
        if (this.resources.isEmpty()) {
            return "PlaybackReceipt[]";
        }

        StringJoiner sj = new StringJoiner(", ", "PlaybackReceipt[", "]");
        Instant previousEnd = this.resources.get(0).start();

        for (PlayedResource entry : this.resources) {
            long gapMs = entry.start().toEpochMilli() - previousEnd.toEpochMilli();

            if (gapMs > 20) {
                sj.add("~" + gapMs + "ms~");
            }

            sj.add(shortName(entry.resourceName()) + "(" + entry.duration().toMillis() + "ms)");
            previousEnd = entry.end();
        }

        return sj.toString();
    }

    private static String shortName(String resourceName) {
        int slash = resourceName.lastIndexOf('/');

        if (slash >= 0) {
            return resourceName.substring(slash + 1);
        }

        return resourceName;
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
