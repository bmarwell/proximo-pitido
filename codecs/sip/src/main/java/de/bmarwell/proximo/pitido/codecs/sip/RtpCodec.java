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

import java.io.IOException;

public interface RtpCodec extends AutoCloseable {

    /**
     * Encodes one frame of {@link #samplesPerFrame()} mono PCM samples to the codec's wire format.
     *
     * @param pcmFrame mono PCM samples at {@link #inputSampleRate()}; length must equal
     *                 {@link #samplesPerFrame()}
     * @return encoded payload bytes for one RTP packet
     * @throws IOException if encoding fails
     */
    byte[] encode(short[] pcmFrame) throws IOException;

    /**
     * Returns the {@code a=fmtp} parameter string to place in the SDP answer for this codec,
     * given the parameter string from the caller's SDP offer.
     *
     * <p>The default implementation ignores the offered fmtp and returns {@link #fmtpParams()},
     * which is correct for most codecs.
     * Codecs that must echo specific offered parameters in the answer (e.g. AMR-WB mode-set
     * per RFC 4867 §8.3.2) must override this method.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty if absent
     * @return the fmtp parameter string for the SDP answer; empty string if no {@code a=fmtp}
     *         line should be emitted
     */
    default String fmtpAnswer(String offeredFmtp) {
        return fmtpParams();
    }

    /**
     * SDP {@code a=fmtp} parameters for this codec, or an empty string if none are needed.
     * Does not include the leading {@code "a=fmtp:<pt> "} prefix.
     */
    String fmtpParams();

    /// Releases any native resources held by this per-call codec instance.
    ///
    /// Stateless codecs (e.g. PCMA) do not hold any native resources and inherit the default no-op implementation.
    /// Stateful per-call codecs (e.g. G.722) override this to release their native [Arena].
    @Override
    default void close() {
        // no-op for stateless codecs (e.g. PCMA) whose forCall() returns the shared CDI singleton
    }
}
