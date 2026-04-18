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
package de.bmarwell.proximo.pitido.war.media;

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
 * {@link PcmaRtpCodec} is the exception: G.711 A-law is memoryless and its singleton
 * {@link PcmaRtpCodec#INSTANCE} is safe to share.
 *
 * <p>The {@link #inputSampleRate()} and {@link #samplesPerFrame()} values document what the PCM
 * decode pipeline should deliver to this encoder.
 * The pipeline currently always outputs 8 kHz mono PCM; when 16 kHz language packs become
 * available this interface will allow codecs such as G.722 to request the correct rate.
 */
public interface RtpCodec {

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
     * Codec name used in the SDP {@code a=rtpmap} attribute, e.g. {@code "PCMA"} or
     * {@code "G722"}.
     */
    String sdpName();

    /**
     * SDP {@code a=fmtp} parameters for this codec, or an empty string if none are needed.
     * Does not include the leading {@code "a=fmtp:<pt> "} prefix.
     */
    String fmtpParams();
}
