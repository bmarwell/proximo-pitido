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
 * {@link PcmaRtpCodec} is the exception: G.711 A-law is memoryless, so its CDI singleton instance
 * is safe to share — {@link #forCall()} returns {@code this}.
 *
 * <p>Each implementation is an {@code @ApplicationScoped} CDI bean.
 * {@link #isAvailable()} reports whether the codec can actually be used on the current host
 * (e.g. the required native library is installed).
 * {@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator} discovers all beans via CDI
 * {@code Instance<RtpCodec>} and filters by availability and preference.
 */
public interface RtpCodec extends AutoCloseable {

    /**
     * Returns {@code true} if this codec can be used on the current host.
     *
     * <p>Pure-Java codecs (e.g. PCMA) always return {@code true}.
     * Native-library codecs (e.g. {@link de.bmarwell.proximo.pitido.codecs.sip.G722RtpCodec} via libspandsp) return {@code false} when the
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
     * Returns a codec instance suitable for exactly one call leg.
     *
     * <p>Stateless codecs (e.g. PCMA) return {@code this} — the CDI singleton is safe to share.
     * Stateful codecs (e.g. G.722 ADPCM) must override this to return a new instance with fresh
     * encoder state; sharing predictor state across calls would corrupt the audio stream.
     *
     * <p>Called by {@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator} once per negotiated
     * call, immediately before storing the instance in {@link CallMedia}.
     */
    default RtpCodec forCall() {
        return this;
    }

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
     * Encodes one frame of {@link #samplesPerFrame()} mono PCM samples to the codec's wire format.
     *
     * @param pcmFrame mono PCM samples at {@link #inputSampleRate()}; length must equal
     *                 {@link #samplesPerFrame()}
     * @return encoded payload bytes for one RTP packet
     * @throws IOException if encoding fails
     */
    byte[] encode(short[] pcmFrame) throws IOException;

    /**
     * Number of channels declared in the SDP {@code a=rtpmap} encoding-parameters field.
     *
     * <p>Most voice codecs are mono and omit this field (the default is 1).
     * Opus declares 2 channels in the SDP per RFC 7587 §5, even when encoding mono audio,
     * for historical interoperability reasons.
     *
     * <p>The default returns {@code 1}; codecs that require a different value (e.g.
     * {@link OpusRtpCodec}) override this method.
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
     * SDP {@code a=fmtp} parameters for this codec, or an empty string if none are needed.
     * Does not include the leading {@code "a=fmtp:<pt> "} prefix.
     */
    String fmtpParams();

    /**
     * Releases any native resources held by this per-call codec instance.
     *
     * <p>Stateless codecs (e.g. PCMA) return {@code this} from {@link #forCall()} and must not
     * be closed; the default implementation is a no-op.
     * Stateful per-call codecs (e.g. G.722) override this to release their native {@link Arena}.
     * {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager} calls this when the call ends.
     */
    @Override
    default void close() {
        // no-op for stateless codecs (e.g. PCMA) whose forCall() returns the shared CDI singleton
    }
}
