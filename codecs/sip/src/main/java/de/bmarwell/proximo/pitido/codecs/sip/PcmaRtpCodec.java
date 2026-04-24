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

public class PcmaRtpCodec implements RtpCodec {

    private static final RtpCodecMetadata METADATA = new PcmaMetadata();

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
    }

    @Override
    public String fmtpParams() {
        return "";
    }

    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        byte[] pcma = new byte[pcmFrame.length];

        for (int index = 0; index < pcmFrame.length; index++) {
            pcma[index] = linearToAlaw(pcmFrame[index]);
        }

        return pcma;
    }

    /**
     * Encodes a 16-bit linear PCM sample to G.711 A-law.
     * Based on the ITU-T G.711 specification.
     *
     * @param pcm the signed 16-bit linear PCM input sample
     * @return the A-law encoded byte
     */
    static byte linearToAlaw(short pcm) {
        final int[] segEnd = {0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF};

        int aval = pcm;
        int mask;

        if (aval >= 0) {
            mask = 0xD5;
        } else {
            mask = 0x55;
            aval = -aval - 1;
        }

        if (aval > Short.MAX_VALUE) {
            aval = Short.MAX_VALUE;
        }

        int seg = 0;

        while (seg < 8 && aval > segEnd[seg]) {
            seg++;
        }

        if (seg >= 8) {
            return (byte) (0x7F ^ mask);
        }

        int alaw = seg << 4;

        if (seg < 2) {
            alaw |= (aval >> 1) & 0x0F;
        } else {
            alaw |= (aval >> seg) & 0x0F;
        }

        return (byte) (alaw ^ mask);
    }
}
