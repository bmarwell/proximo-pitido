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
 * G.722 wideband audio codec scaffold for RTP transmission (payload type 9).
 *
 * <p>G.722 delivers wideband audio (50 Hz – 7 kHz) at 64 kbps using sub-band ADPCM
 * (ITU-T G.722, 1988).
 * A QMF analysis filter splits the 16 kHz input signal into two 8 kHz sub-bands.
 * The lower sub-band (0–4 kHz) is encoded with a 6-bit ADPCM coder; the upper sub-band
 * (4–8 kHz) uses a 2-bit ADPCM coder.
 *
 * <p>Per RFC 3551 §4.5.2, the RTP clock rate for G.722 is declared as 8 000 Hz — a historical
 * anomaly preserved for interoperability; actual processing is at 16 kHz.
 * The RTP timestamp therefore increments by 160 per 20 ms packet, identical to PCMA.
 *
 * <p><strong>Encoding is not yet implemented.</strong>
 * {@link #encode(short[])} throws {@link UnsupportedOperationException}.
 * {@link SdpNegotiator} does not include this codec in its preference list until encoding is
 * implemented and a suitable pure-Java library is available on Maven Central.
 *
 * <p>Audio quality note: G.722's wideband advantage requires 16 kHz source audio.
 * The current decode pipeline outputs 8 kHz; language packs would need to supply 16 kHz
 * recordings and the pipeline would need to target {@link #inputSampleRate()} before G.722
 * sounds better than {@link PcmaRtpCodec PCMA}.
 *
 * @see PcmaRtpCodec
 */
public final class G722RtpCodec implements RtpCodec {

    @Override
    public int payloadType() {
        return 9;
    }

    @Override
    public int rtpClockRate() {
        // RFC 3551 §4.5.2: G.722 uses an 8 000 Hz RTP clock despite 16 kHz processing.
        return 8_000;
    }

    @Override
    public int inputSampleRate() {
        return 16_000;
    }

    @Override
    public int samplesPerFrame() {
        // 20 ms × 16 000 Hz
        return 320;
    }

    @Override
    public int rtpTimestampIncrement() {
        // rtpClockRate() / 50 packets per second
        return 160;
    }

    /**
     * Not yet implemented.
     *
     * <p>G.722 requires a QMF analysis filter (12-tap), a 6-bit lower-band ADPCM encoder with a
     * Griffiths LMS adaptive predictor, and a 2-bit upper-band ADPCM encoder.
     * No verified pure-Java implementation is available on Maven Central.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public byte[] encode(short[] pcmFrame) throws IOException {
        throw new UnsupportedOperationException("G.722 encoding is not yet implemented. "
                + "Add this codec to SdpNegotiator.PREFERRED_CODECS once an encoder is available.");
    }

    @Override
    public String sdpName() {
        return "G722";
    }

    @Override
    public String fmtpParams() {
        return "";
    }
}
