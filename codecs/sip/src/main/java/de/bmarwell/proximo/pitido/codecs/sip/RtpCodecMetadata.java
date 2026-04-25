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
 * Codec metadata: static properties independent of codec instantiation.
 *
 * <p>Metadata represents codec characteristics that do not depend on per-call state:
 * sample rates, clock rates, payload types, SDP parameters, etc.
 * Implementations are immutable and thread-safe.
 * Both {@link RtpCodecFactory} and {@link RtpCodec} instances expose metadata via {@link RtpCodec#metadata()}.
 */
public interface RtpCodecMetadata {

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
     * PCM sample rate in Hz expected by {@link RtpCodec#encode(short[])}.
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
     * Codec name used in the SDP {@code a=rtpmap} attribute, e.g. {@code "PCMA"} or
     * {@code "G722"}.
     */
    String sdpName();

    /**
     * Number of channels declared in the SDP {@code a=rtpmap} encoding-parameters field.
     *
     * <p>Most voice codecs are mono and omit this field (the default is 1).
     * Opus declares 2 channels in the SDP per RFC 7587 §5, even when encoding mono audio,
     * for historical interoperability reasons.
     *
     * @return the SDP channel count, usually {@code 1}
     */
    int sdpChannelCount();
}
