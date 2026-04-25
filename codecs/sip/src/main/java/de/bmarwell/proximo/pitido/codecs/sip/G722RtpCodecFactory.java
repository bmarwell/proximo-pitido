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

import java.lang.foreign.Arena;
import javax.enterprise.context.ApplicationScoped;

/**
 * G.722 wideband audio codec for RTP transmission (payload type 9).
 *
 * <p>G.722 delivers wideband audio (50 Hz–7 kHz) at 64 kbps using sub-band ADPCM
 * (ITU-T G.722, 1988).
 * A QMF analysis filter splits the 16 kHz input signal into two 8 kHz sub-bands.
 * The lower sub-band (0–4 kHz) is encoded with a 6-bit ADPCM coder; the upper sub-band
 * (4–8 kHz) uses a 2-bit ADPCM coder, yielding 4 bits per input sample and 160 bytes per
 * 20 ms packet.
 *
 * <p>Per RFC 3551 §4.5.2, the RTP clock rate for G.722 is declared as 8 000 Hz — a historical
 * anomaly preserved for interoperability; actual processing is at 16 kHz.
 * The RTP timestamp increments by 160 per 20 ms packet, identical to PCMA.
 *
 * <h2>Native backend — libspandsp via FFM</h2>
 *
 * <p>Encoding is performed by {@code libspandsp} via the Foreign Function and Memory (FFM) API.
 * Install it on the host system before starting the server:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libspandsp2}</li>
 *   <li>Arch Linux: {@code pacman -S spandsp}</li>
 *   <li>RHEL / UBI 9: {@code rpm -i https://dl.fedoraproject.org/pub/epel/epel-release-latest-9.noarch.rpm && microdnf install spandsp}</li>
 * </ul>
 *
 * <p>The FFM binding calls two functions:
 * <ul>
 *   <li>{@code g722_encode_init(state*, rate, options)} — initialises the ADPCM encoder state
 *       in a pre-allocated segment; called once per call leg in {@link #forCall(String)}.</li>
 *   <li>{@code g722_encode(state*, out_bytes*, in_pcm*, len)} — encodes one frame; called per
 *       packet in {@link RtpCodec#encode(short[])}.</li>
 * </ul>
 *
 * <h2>Factory / per-call separation</h2>
 *
 * <p>G.722 ADPCM carries predictor state across packets; sharing encoder state between calls
 * would corrupt audio.
 * This {@code @ApplicationScoped} factory is stateless; {@link #forCall(String)} allocates a
 * fresh {@link Arena} and {@code g722_encode_state_t} segment for each call leg and returns a
 * plain (non-CDI) {@code G722RtpCodec} instance that implements {@link AutoCloseable}.
 * Callers must invoke {@link RtpCodec#close()} when the call ends to release the native encoder state
 * promptly; {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager} handles this.
 *
 * <h2>G.729 — will not be implemented</h2>
 *
 * <p>G.729 (CS-ACELP, payload type 18) will <em>not</em> be implemented in this project.
 * The algorithm complexity makes a pure-Java port impractical, and the codec is dying in practice —
 * Deutsche Telekom and most modern SIP providers do not offer it.
 *
 * @see PcmaRtpCodecFactory
 */
@ApplicationScoped
public class G722RtpCodecFactory extends NativeRtpCodecFactory {

    private static final RtpCodecMetadata METADATA = new G722Metadata();

    /**
     * Probes for {@code libspandsp.so.2} on construction.
     *
     * <p>Sets {@link #available} to {@code true} if the library is found and all symbols resolve.
     * Library remains loaded for the lifetime of the JVM via {@link Arena#global()}.
     */
    public G722RtpCodecFactory() {
        probeLibrary("libspandsp.so.2", "G.722 wideband codec");
    }

    @Override
    public int preference() {
        // Preferred over PCMA when available: wideband audio sounds significantly better.
        return 50;
    }

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
    }

    /**
     * Returns a new per-call encoder instance with freshly initialised G.722 ADPCM state.
     *
     * <p>Each call leg must use its own encoder state.
     * Sharing ADPCM predictor state across calls corrupts the audio stream.
     *
     * <p>The returned instance is not a CDI bean.
     * It holds a confined {@link Arena} and an initialised {@code g722_encode_state_t};
     * call {@link RtpCodec#close()} when the call ends.
     *
     * @throws IllegalStateException if {@code g722_encode_init} returns a null pointer
     */
    @Override
    public G722RtpCodec forCall(String fmt) {
        return new G722RtpCodec();
    }
}
