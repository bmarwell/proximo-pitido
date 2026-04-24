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

public class PcmuRtpCodec implements RtpCodec {

    @Override
    public int payloadType() {
        return 0;
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
    public String fmtpParams() {
        return "";
    }

    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        byte[] pcmu = new byte[pcmFrame.length];

        for (int index = 0; index < pcmFrame.length; index++) {
            pcmu[index] = linearToUlaw(pcmFrame[index]);
        }

        return pcmu;
    }

    /**
     * Encodes a 16-bit linear PCM sample to G.711 µ-law.
     *
     * <p>The algorithm follows ITU-T G.711 and the traditional Sun/Oracle Java Sound reference
     * implementation (CCITT G.711 Annex A).
     * A bias of 0x84 is added before taking the logarithm to linearise the small-signal region.
     *
     * @param pcm the signed 16-bit linear PCM input sample
     * @return the µ-law encoded byte
     */
    static byte linearToUlaw(short pcm) {
        final int bias = 0x84;
        final int clip = 32_635;

        int sample = pcm;
        int sign;

        if (sample < 0) {
            sample = -sample;
            sign = 0x80;
        } else {
            sign = 0;
        }

        if (sample > clip) {
            sample = clip;
        }

        sample += bias;

        int exponent = 7;

        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
            // advance until the most significant set bit is found
        }

        int mantissa = (sample >> (exponent + 3)) & 0x0F;

        return (byte) (~(sign | (exponent << 4) | mantissa) & 0xFF);
    }
}
