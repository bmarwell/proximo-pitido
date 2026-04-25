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

/**
 * Factory for creating per-call RTP codec instances with awareness of SDP offer parameters.
 *
 * <p><strong>Architecture:</strong> This factory pattern separates codec descriptor (sample rates,
 * payload type, SDP name) from per-call codec state (encoder predictor state, memory arenas).
 * The factory is {@code @ApplicationScoped} and stateless; each call leg obtains its own
 * per-call {@link RtpCodec} instance by calling {@link #forCall(String)}.
 *
 * <p><strong>Lifecycle:</strong>
 * <ol>
 *   <li>{@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator} discovers all {@code RtpCodecFactory}
 *       beans via CDI {@code Instance<RtpCodecFactory>} during SDP offer/answer exchange.</li>
 *   <li>Negotiator filters by {@link #isAvailable()} and {@link #preference()}, then calls
 *       {@link #forCall(String)} to create a per-call {@link RtpCodec} instance.</li>
 *   <li>The {@link RtpCodec} instance encodes audio frames via {@link RtpCodec#encode(short[])}
 *       and must be released via {@link RtpCodec#close()} when the call ends.</li>
 * </ol>
 *
 * <p><strong>Codec state:</strong> Most codecs are stateful (ADPCM predictor state, encoder memory).
 * Each call leg must use its own {@link RtpCodec} instance; sharing encoder state across calls corrupts audio.
 * {@link PcmaRtpCodecFactory} is stateless (G.711 A-law is memoryless) but follows the factory pattern
 * for consistency and extensibility.
 *
 * <p>Each implementation provides metadata (payload type, sample rates, SDP parameters) via
 * {@link #metadata()} for use during SDP negotiation and fmtp compatibility checking.
 */
public interface RtpCodecFactory {

    /**
     * Returns a codec instance suitable for exactly one call leg, with awareness of the
     * offered fmtp parameters.
     *
     * Codecs that adapt their encoding behaviour based on offered parameters (e.g. AMR-WB
     * selecting the best allowed mode from {@code mode-set}) must override this method.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty if absent
     */
    RtpCodec forCall(String offeredFmtp);

    /**
     * Returns {@code true} if this codec can be used on the current host.
     *
     * <p>Pure-Java codecs (e.g. PCMA) always return {@code true}.
     * Native-library codecs (e.g. {@link G722RtpCodecFactory} via libspandsp) return {@code false} when the
     * required library is not installed.
     */
    boolean isAvailable();

    /**
     * Codec preference for SDP negotiation — lower value = offered first (higher quality preferred).
     *
     * <p>Example assignments:
     * <ul>
     *   <li>G.722 — 50 (preferred when available; wideband 50 Hz–7 kHz)</li>
     *   <li>PCMA — 100 (always-available narrowband fallback)</li>
     * </ul>
     */
    int preference();

    /**
     * Returns static metadata for this codec: payload type, sample rates, SDP parameters, etc.
     *
     * <p>Metadata is codec-wide and independent of per-call state.
     * Both the factory and per-call codec instances expose the same metadata.
     *
     * @return immutable metadata object
     */
    RtpCodecMetadata metadata();

    /**
     * Returns {@code true} if the given {@code a=fmtp} parameter string from the SDP offer is
     * compatible with this codec's packetisation requirements.
     *
     * <p>The default returns {@code true}, meaning the codec accepts any (or no) fmtp parameters.
     * Codecs that have a mandatory packetisation mode — such as AMR-WB in octet-aligned mode —
     * must override this method and reject offers that do not declare the required parameter.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer
     *                    (the part after {@code "a=fmtp:<pt> "}), or an empty string if the
     *                    caller did not include an {@code a=fmtp} line for this payload type
     * @return {@code true} if the offer is compatible; {@code false} if the packetisation mode
     *         is incompatible and this payload type must be skipped during negotiation
     */
    default boolean matchesFmtp(String offeredFmtp) {
        return true;
    }

    /**
     * Generates the fmtp parameters for the SDP answer.
     *
     * <p>Most codecs do not require fmtp parameters in the SDP answer and return an empty string.
     * Codecs with mandatory packetisation modes (e.g. AMR-WB in octet-aligned mode) override this
     * method to return the appropriate answer parameters.
     *
     * <p>The default returns an empty string; codecs with specific fmtp requirements override this method.
     * This method does not require per-call state and may be called during SDP negotiation before
     * {@link #forCall(String)} is invoked.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or an empty string if absent
     * @return the fmtp parameters for the SDP answer, or an empty string if none are required
     */
    default String fmtpAnswer(String offeredFmtp) {
        return "";
    }
}
