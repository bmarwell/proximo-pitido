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
 * Codec metadata for AMR-WB (adaptive multi-rate wideband), RFC 4867 §4.4 (octet-aligned).
 *
 * <p>Wideband codec encoding 16-bit linear PCM at 16 kHz to adaptive-bitrate AMR-WB format,
 * in 20 ms packets.
 */
public final class AmrWbMetadata implements RtpCodecMetadata {

    @Override
    public int payloadType() {
        return 98;
    }

    @Override
    public int rtpClockRate() {
        return 16_000;
    }

    @Override
    public int inputSampleRate() {
        return 16_000;
    }

    @Override
    public int samplesPerFrame() {
        return 320;
    }

    @Override
    public int rtpTimestampIncrement() {
        return 320;
    }

    @Override
    public String sdpName() {
        return "AMR-WB";
    }

    @Override
    public int sdpChannelCount() {
        return 1;
    }
}
