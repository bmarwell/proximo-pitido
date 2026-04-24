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
 * Abstracts a single RTP audio codec: sample-rate requirements, payload type, SDP description,
 * and the PCM → wire-format encoding step.
 *
 * <p>Each call leg negotiates exactly one codec during SDP offer/answer exchange.
 * The negotiated instance is stored in {@link CallMedia#codec()} and used by
 * {@link RtpAudioPlayer} for every packet in that call.
 *
 * <p>Most codecs are stateful (the encoder carries ADPCM predictor state across packets) and must
 * <em>not</em> be shared across call legs.
 * {@link PcmaRtpCodecFactory} is the exception: G.711 A-law is memoryless, so its CDI singleton instance
 * is safe to share — {@link #forCall()} returns {@code this}.
 *
 * <p>Each implementation is an {@code @ApplicationScoped} CDI bean.
 * {@link #isAvailable()} reports whether the codec can actually be used on the current host
 * (e.g. the required native library is installed).
 * {@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator} discovers all beans via CDI
 * {@code Instance<RtpCodec>} and filters by availability and preference.
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
    <T extends RtpCodec> T forCall(String offeredFmtp);

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
     * RTP payload type (0–127).
     *
     * <p>Static assignments (0–95) are defined in RFC 3551.
     * Dynamic assignments (96–127) are for codecs requiring a negotiated {@code a=rtpmap} line.
     */
    int payloadType();

    /**
     * RTP clock rate in Hz, as declared in the SDP {@code a=rtpmap} attribute.
     *
     * <p>This governs RTP timestamp arithmetic and may differ from {@link #inputSampleRate()}.
     * G.722 declares an RTP clock of 8 000 Hz per RFC 3551 §4.5.2 despite processing 16 kHz
     * input — a historical anomaly preserved for interoperability.
     */
    int rtpClockRate();

    /**
     * PCM sample rate in Hz expected by {@link #encode(short[])}.
     *
     * <p>Differs from {@link #rtpClockRate()} for G.722 (16 000 vs 8 000).
     * The PCM decode pipeline should target this rate when multi-rate support is added.
     */
    int inputSampleRate();

    /**
     * Number of PCM samples (at {@link #inputSampleRate()}) consumed per 20 ms RTP packet.
     * Equals {@code inputSampleRate() / 50}.
     */
    int samplesPerFrame();

    /**
     * RTP timestamp increment per 20 ms packet.
     * Equals {@code rtpClockRate() / 50}.
     *
     * <p>Note: for G.722 this is 160 (8 000 × 0.02) despite 16 kHz processing, due to the
     * RFC 3551 clock rate quirk.
     */
    int rtpTimestampIncrement();

    /**
     * Number of channels declared in the SDP {@code a=rtpmap} encoding-parameters field.
     *
     * <p>Most voice codecs are mono and omit this field (the default is 1).
     * Opus declares 2 channels in the SDP per RFC 7587 §5, even when encoding mono audio,
     * for historical interoperability reasons.
     *
     * <p>The default returns {@code 1}; codecs that require a different value (e.g.
     * {@link OpusRtpCodecFactory}) override this method.
     *
     * @return the SDP channel count, usually {@code 1}
     */
    default int sdpChannelCount() {
        return 1;
    }

    /**
     * Codec name used in the SDP {@code a=rtpmap} attribute, e.g. {@code "PCMA"} or
     * {@code "G722"}.
     */
    String sdpName();

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
}
