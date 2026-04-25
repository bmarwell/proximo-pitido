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
 * G.711 µ-law (PCMU) RTP codec, payload type 0.
 *
 * <p>PCMU is the North American and Japanese variant of G.711 (RFC 3551 §4.5.14).
 * It encodes 14-bit linear PCM to 8-bit µ-law logarithmic PCM at 8 kHz, producing 64 kbps audio
 * in 20 ms packets of 160 encoded bytes each.
 * RFC 3551 mandates that any SIP UA MUST be able to receive both PT=0 (PCMU) and PT=8 (PCMA).
 *
 * <p>G.711 µ-law is memoryless: each sample encodes independently with no encoder state.
 * This factory is {@code @ApplicationScoped} and stateless.
 *
 * <p>Preference is set to 110 — slightly lower priority than {@link PcmaRtpCodecFactory} (100) because
 * A-law is the European standard and most SIP providers in this region prefer PCMA.
 *
 * @see PcmaRtpCodecFactory
 */
@ApplicationScoped
public final class PcmuRtpCodecFactory implements RtpCodecFactory {

    private static final RtpCodecMetadata METADATA = new PcmuMetadata();

    PcmuRtpCodecFactory() {}

    @Override
    public RtpCodec forCall(String offeredFmtp) {
        return new PcmuRtpCodec();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int preference() {
        // Lower priority than PCMA (100); both are always available but PCMA is preferred at
        // European SIP providers.
        return 110;
    }

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
    }
}
