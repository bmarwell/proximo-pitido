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
 * Codec metadata for G.711 A-law (PCMA), RFC 3551 §4.5.14.
 *
 * <p>Encodes 13-bit linear PCM to 8-bit A-law logarithmic PCM at 8 kHz, producing 64 kbps audio
 * in 20 ms packets of 160 encoded bytes each.
 */
public final class PcmaMetadata implements RtpCodecMetadata {

    @Override
    public int payloadType() {
        return 8;
    }

    @Override
    public int rtpClockRate() {
        return 8000;
    }

    @Override
    public int inputSampleRate() {
        return 8000;
    }

    @Override
    public int samplesPerFrame() {
        return 160;
    }

    @Override
    public int rtpTimestampIncrement() {
        return 160;
    }

    @Override
    public String sdpName() {
        return "PCMA";
    }

    @Override
    public int sdpChannelCount() {
        return 1;
    }
}
