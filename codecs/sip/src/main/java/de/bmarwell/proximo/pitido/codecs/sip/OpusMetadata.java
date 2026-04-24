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
 * Codec metadata for Opus, RFC 7587 §5.
 *
 * <p>Wideband codec encoding 16-bit linear PCM at 48 kHz to variable-bitrate Opus format,
 * in 20 ms packets of variable size.
 * Declares 2 channels in SDP even when encoding mono for interoperability.
 */
public final class OpusMetadata implements RtpCodecMetadata {

    @Override
    public int payloadType() {
        return 120;
    }

    @Override
    public int rtpClockRate() {
        return 48_000;
    }

    @Override
    public int inputSampleRate() {
        return 48_000;
    }

    @Override
    public int samplesPerFrame() {
        return 960;
    }

    @Override
    public int rtpTimestampIncrement() {
        return 960;
    }

    @Override
    public String sdpName() {
        return "opus";
    }

    @Override
    public int sdpChannelCount() {
        return 2;
    }
}
