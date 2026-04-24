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
 * {@link #forCall(String)} returns a new {@link PcmaRtpCodec} instance (the codec is stateless
 * so sharing would be fine, but factory pattern is consistent with stateful codecs).
 *
 * @see G722RtpCodecFactory
 */
@ApplicationScoped
public final class PcmaRtpCodecFactory implements RtpCodecFactory {

    private static final RtpCodecMetadata METADATA = new PcmaMetadata();

    public PcmaRtpCodecFactory() {}

    @Override
    public RtpCodec forCall(String offeredFmtp) {
        return new PcmaRtpCodec();
    }

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
    public RtpCodecMetadata metadata() {
        return METADATA;
    }
}
