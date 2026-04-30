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

public abstract class NativeRtpCodec implements RtpCodec {

    /**
     * Confined arena owning the per-call native encoder state.
     * {@code null} in the CDI factory bean; non-null in per-call instances.
     *
     * <p>Closed by {@link #close()} when the call ends.
     */
    protected final Arena callArena;

    protected NativeRtpCodec() {
        this.callArena = Arena.ofShared();
    }

    /**
     * Closes the confined arena, releasing the native encoder state immediately.
     */
    @Override
    public void close() {
        if (this.callArena != null) {
            this.callArena.close();
        }
    }
}
