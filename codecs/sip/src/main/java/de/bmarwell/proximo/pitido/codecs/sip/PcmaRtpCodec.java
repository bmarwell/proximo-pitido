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
import javax.enterprise.context.ApplicationScoped;

/**
 * G.711 A-law (PCMA) RTP codec, payload type 8.
 *
 * <p>PCMA is the mandatory baseline codec for SIP telephony (RFC 3551 §4.5.14).
 * It encodes 13-bit linear PCM to 8-bit A-law logarithmic PCM at 8 kHz, producing 64 kbps audio
 * in 20 ms packets of 160 encoded bytes each.
 *
 * <p>G.711 A-law is memoryless: each sample encodes independently, so the encoder carries no state
 * across packets.
 * This bean is {@code @ApplicationScoped} (a CDI singleton) and safe to share across call legs;
 * {@link #forCall()} returns {@code this}.
 *
 * <p>{@link #INSTANCE} is kept as a static fallback for code paths that cannot use CDI injection
 * (e.g. default return values in {@link SdpNegotiator}).
 * Prefer CDI injection wherever possible.
 *
 * @see G722RtpCodec
 */
@ApplicationScoped
public final class PcmaRtpCodec implements RtpCodecFactory {

    /**
     * Static fallback instance for non-CDI contexts.
     * Prefer injecting via CDI; use this only where injection is unavailable.
     */
    public static final PcmaRtpCodec INSTANCE = new PcmaRtpCodec();

    PcmaRtpCodec() {}

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int preference() {
        // PCMA is the narrowband baseline; prefer higher-quality codecs when available.
        return 100;
    }

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
    public byte[] encode(short[] pcmFrame) throws IOException {
        byte[] pcma = new byte[pcmFrame.length];

        for (int index = 0; index < pcmFrame.length; index++) {
            pcma[index] = linearToAlaw(pcmFrame[index]);
        }

        return pcma;
    }

    @Override
    public String sdpName() {
        return "PCMA";
    }

    @Override
    public String fmtpParams() {
        return "";
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
