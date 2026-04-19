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
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

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
 * {@link #isAvailable()} returns {@code false} until an encoder is implemented.
 *
 * <h2>Future implementation path — libg72x via FFM</h2>
 *
 * <p>The recommended native backend is {@code libg72x} (ITU-T reference implementation).
 * Install it on the host:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libg72x-dev} (if packaged) or build from source</li>
 *   <li>Arch Linux: AUR package {@code libg72x}</li>
 * </ul>
 *
 * <p>Bind using the Foreign Function and Memory (FFM) API:
 * <ol>
 *   <li>Detect the library: {@code SymbolLookup.libraryLookup("libg72x.so", arena)}</li>
 *   <li>Bind {@code g722_encode_init(g722_encode_state_t *)} and
 *       {@code g722_encode(g722_encode_state_t *, const short *, int, unsigned char *)}</li>
 *   <li>Allocate the opaque state struct as a {@code MemorySegment} and hold it per call leg.</li>
 * </ol>
 *
 * <p>Because the ADPCM predictor state is per-call, {@link #forCall()} must return a new
 * {@code G722RtpCodec} instance with a fresh encoder state rather than returning {@code this}.
 * The CDI {@code @ApplicationScoped} bean acts as a factory/descriptor; actual encoding uses
 * per-call instances.
 *
 * <h2>G.729 — will not be implemented</h2>
 *
 * <p>G.729 (CS-ACELP, payload type 18) will <em>not</em> be implemented in this project.
 * The algorithm complexity makes a pure-Java port impractical, and the codec is dying in practice —
 * Deutsche Telekom and most modern SIP providers do not offer it.
 * {@code bcg729} exists as a C library but adds significant porting effort for negligible gain.
 *
 * @see PcmaRtpCodec
 */
@ApplicationScoped
public final class G722RtpCodec implements RtpCodec {

    private static final System.Logger LOGGER = System.getLogger(G722RtpCodec.class.getName());

    @PostConstruct
    void logStatus() {
        LOGGER.log(System.Logger.Level.DEBUG, "G722RtpCodec loaded — encoding not yet implemented, isAvailable=false");
    }

    @Override
    public boolean isAvailable() {
        // Set to true and implement encode() once libg72x FFM binding is complete.
        return false;
    }

    @Override
    public int preference() {
        // Preferred over PCMA when available: wideband audio sounds significantly better.
        return 50;
    }

    /**
     * Returns a new per-call encoder instance.
     *
     * <p>G.722 is stateful (ADPCM predictor); sharing this bean across calls would corrupt audio.
     * Each call leg must receive its own instance with a freshly initialised encoder state.
     * Currently returns {@code this} because no encoder state exists yet.
     * Override once the libg72x FFM binding carries per-call state.
     */
    @Override
    public RtpCodec forCall() {
        return this;
    }

    @Override
    public int payloadType() {
        return 9;
    }

    @Override
    public int rtpClockRate() {
        // RFC 3551 §4.5.2: G.722 uses an 8 000 Hz RTP clock despite 16 kHz processing.
        return 8000;
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
