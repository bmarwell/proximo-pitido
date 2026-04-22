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
package de.bmarwell.proximo.pitido.codecs.sip;

import java.lang.foreign.Arena;

/**
 * Abstract base class for RTP codecs backed by a native shared library loaded via the
 * Foreign Function and Memory (FFM) API.
 *
 * <p>Provides the common scaffolding shared by all native codecs:
 * <ul>
 *   <li>{@link #available} flag and {@link #isAvailable()} — set to {@code true} by
 *       {@code @PostConstruct} probe methods when the native library loads successfully.</li>
 *   <li>{@link #callArena} — a {@link Arena#ofConfined() confined arena} that owns the
 *       per-call native encoder state; {@code null} in CDI factory beans.</li>
 *   <li>{@link #close()} — closes the confined arena, releasing native state immediately
 *       when the call ends.</li>
 * </ul>
 *
 * <h2>CDI factory / per-call pattern</h2>
 *
 * <p>Stateful native codecs follow a two-instance pattern:
 * <ol>
 *   <li>A CDI {@code @ApplicationScoped} factory bean is created via the no-args constructor
 *       {@link #NativeRtpCodec()}.
 *       Its {@link #callArena} is {@code null} and {@link #available} starts {@code false}.</li>
 *   <li>After the CDI {@code @PostConstruct} probe method successfully loads the native library,
 *       {@link #available} is set to {@code true}.</li>
 *   <li>{@link RtpCodec#forCall()} allocates a {@link Arena#ofConfined() confined arena}, creates
 *       and initialises native encoder state inside it, then constructs a per-call instance via
 *       {@link #NativeRtpCodec(Arena)}.
 *       Per-call instances have {@link #available} {@code true} from construction.</li>
 *   <li>When the call ends, {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager}
 *       calls {@link #close()}, which closes the arena and releases the native state.</li>
 * </ol>
 *
 * <p>Stateless pure-Java codecs ({@link PcmaRtpCodec}, {@link PcmuRtpCodec}) do not extend this
 * class; they implement {@link RtpCodec} directly and inherit the default no-op {@code close()}.
 */
public abstract class NativeRtpCodec implements RtpCodec {

    /**
     * Whether the native library was successfully loaded.
     *
     * <p>{@code false} in freshly constructed CDI factory beans;
     * set to {@code true} by the subclass {@code @PostConstruct} probe method.
     * Always {@code true} in per-call instances created by {@link RtpCodec#forCall()}.
     */
    protected boolean available = false;

    /**
     * Confined arena owning the per-call native encoder state.
     * {@code null} in the CDI factory bean; non-null in per-call instances.
     *
     * <p>Closed by {@link #close()} when the call ends.
     */
    protected final Arena callArena;

    /**
     * CDI factory bean constructor.
     *
     * <p>Sets {@link #callArena} to {@code null}.
     * {@link #available} starts {@code false}; the subclass {@code @PostConstruct} probe method
     * sets it to {@code true} after a successful library load.
     */
    protected NativeRtpCodec() {
        this.callArena = null;
    }

    /**
     * Per-call instance constructor.
     *
     * <p>Sets {@link #callArena} to the given arena and marks the instance as
     * {@link #available} (since the native library was confirmed at probe time).
     *
     * @param callArena a confined arena owning the native encoder state for this call leg;
     *                  must not be {@code null}
     */
    protected NativeRtpCodec(Arena callArena) {
        this.callArena = callArena;
        this.available = true;
    }

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Closes the confined arena, releasing the native encoder state immediately.
     *
     * <p>A no-op when called on the CDI factory bean (whose {@link #callArena} is {@code null}).
     */
    @Override
    public void close() {
        if (this.callArena != null) {
            this.callArena.close();
        }
    }
}
