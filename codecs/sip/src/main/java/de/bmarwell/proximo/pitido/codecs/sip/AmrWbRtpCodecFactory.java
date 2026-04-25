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

import java.util.Arrays;
import javax.enterprise.context.ApplicationScoped;

/**
 * AMR-WB (Adaptive Multi-Rate Wideband, G.722.2) RTP codec (dynamic payload type 98).
 *
 * <p>AMR-WB delivers wideband audio (50 Hz–7 kHz) at 6.60–23.85 kbps.
 * Deutsche Telekom and most German mobile carriers use AMR-WB as the primary VoLTE codec.
 * Without this codec, mobile VoLTE callers fall back to G.711 PCMA (narrowband 8 kHz),
 * even though their device is capable of HD voice.
 *
 * <p>This implementation uses mode 8 (23.85 kbps) — the highest quality AMR-WB mode — so that
 * all mobile callers receive maximum audio fidelity.
 * The SDP negotiation selects this codec only when the caller's SIP INVITE offers AMR-WB.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 4867 §8.1, the SDP attribute lines for octet-aligned AMR-WB are:
 * <pre>
 * a=rtpmap:98 AMR-WB/16000
 * a=fmtp:98 octet-align=1
 * </pre>
 *
 * <h2>RTP payload format (RFC 4867 §4.4 octet-aligned)</h2>
 *
 * <p>Each RTP packet contains exactly one AMR-WB frame in octet-aligned mode:
 * <ol>
 *   <li>1-byte CMR header — {@code 0xF0} ("no codec mode request").</li>
 *   <li>1-byte Table of Contents (ToC) — frame type index (bits 6–3), quality flag
 *       {@code Q=1} (bit 2), and two padding bits; no continuation flag ({@code F=0}).</li>
 *   <li>Speech data bytes produced by {@code E_IF_encode}.</li>
 * </ol>
 *
 * <h2>Native backend — libvo-amrwbenc via FFM</h2>
 *
 * <p>Encoding is performed by {@code libvo-amrwbenc} via the Foreign Function and Memory (FFM)
 * API.
 * Install the library on the host system before starting the server:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libvo-amrwbenc0}</li>
 *   <li>Arch Linux: {@code yay -S vo-amrwbenc} (AUR)</li>
 *   <li>RHEL / UBI 9: {@code microdnf install vo-amrwbenc} (after EPEL)</li>
 * </ul>
 *
 * <p>Note: {@code libopencore-amrwb} (part of the {@code opencore-amr} package) only provides
 * an AMR-WB <em>decoder</em>.
 * AMR-WB <em>encoding</em> requires the separate {@code libvo-amrwbenc} library.
 *
 * <p>Three FFM functions are bound:
 * <ul>
 *   <li>{@code E_IF_init()} — allocates and returns an opaque encoder state pointer;
 *       called once per call leg.</li>
 *   <li>{@code E_IF_encode(state, mode, speech, serial, allow_dtx)} — encodes one 20 ms frame;
 *       called per RTP packet.</li>
 *   <li>{@code E_IF_exit(state)} — releases the encoder state; called when the call ends.</li>
 * </ul>
 *
 * <h2>Factory / per-call separation</h2>
 *
 * <p>AMR-WB ACELP carries pitch and gain predictor state across packets; sharing encoder state
 * between calls corrupts audio.
 * This {@code @ApplicationScoped} factory is stateless; {@link #forCall(String)} calls
 * {@code E_IF_init()} to obtain a fresh encoder state, then returns a plain (non-CDI)
 * {@code AmrWbRtpCodec} instance.
 * When the call ends, {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager}
 * calls {@link RtpCodec#close()}, which calls {@code E_IF_exit(state)} to release the native state.
 *
 * @see G722RtpCodecFactory
 * @see NativeRtpCodecFactory
 */
@ApplicationScoped
public class AmrWbRtpCodecFactory extends NativeRtpCodecFactory<AmrWbRtpCodec> {

    private static final System.Logger LOGGER = System.getLogger(AmrWbRtpCodecFactory.class.getName());

    private static final RtpCodecMetadata METADATA = new AmrWbMetadata();

    /**
     * Probes for {@code libvo-amrwbenc.so.0} on construction.
     *
     * <p>Sets {@link NativeRtpCodecFactory#available} to {@code true} when the library is found.
     * Library remains loaded for the lifetime of the JVM via {@link Arena#global()}.
     */
    public AmrWbRtpCodecFactory() {
        probeLibrary("libvo-amrwbenc.so.0", "AMR-WB RTP codec");
    }

    @Override
    public int preference() {
        // Preferred over G.722 (50) for mobile VoLTE callers: lower bitrate, same wideband quality.
        // PSTN trunks never offer AMR-WB, so this codec activates only for mobile callers.
        // Preference is 40 (octet-aligned): tried first to diagnose bandwidth-efficient audio issues.
        // RFC 4867 specifies bandwidth-efficient as the DEFAULT packetisation format, but we
        // temporarily prioritise octet-aligned to test if the audio corruption is codec-format-specific.
        return 40;
    }

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
    }

    /**
     * Returns a new per-call encoder instance initialised at the best mode allowed by the
     * caller's offered {@code mode-set}.
     *
     * <p>Parses the {@code mode-set} parameter from {@code offeredFmtp} (e.g.
     * {@code "octet-align=1;mode-set=0,1,2"}) and selects the highest mode index present.
     * Falls back to {@link #DEFAULT_ENCODING_MODE} when no {@code mode-set} is offered.
     *
     * <p>RFC 4867 §8.3.2 requires that both parties only use modes from the negotiated set;
     * using a mode outside the set produces frames the remote decoder will refuse.
     *
     * @param offeredFmtp the fmtp parameter string from the caller's SDP offer, or empty
     * @return a fully initialised per-call {@link AmrWbRtpCodecFactory} with the negotiated mode
     * @throws IllegalStateException if the codec is not available or {@code E_IF_init} fails
     */
    @Override
    public AmrWbRtpCodec forCall(String offeredFmtp) {
        if (!this.available) {
            throw new IllegalStateException(
                    "AMR-WB codec is not available — libvo-amrwbenc was not loaded; check probe() logs");
        }

        return new AmrWbRtpCodec(offeredFmtp);
    }

    static boolean isOctetAlignParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip());
    }

    /**
     * This implementation uses octet-aligned packetisation (RFC 4867 §4.4).
     *
     * <p>Callers may advertise AMR-WB under multiple dynamic payload types or modes:
     * <ul>
     *   <li>Octet-aligned: indicated by {@code a=fmtp} with {@code octet-align=1} parameter,
     *       OR by empty fmtp when no packetisation format is specified (RFC 4867 default).
     *       This is the preferred RFC 4867 §4.4 format.
     *   <li>Bandwidth-efficient (RFC 4867 §4.3): explicitly specified with bandwidthEfficient codec.
     * </ul>
     *
     * <p>Accepts both explicit {@code octet-align=1} and empty fmtp (ambiguous default case).
     * Also accepts fmtp with mode parameters (e.g. {@code mode-set=0,1,2}) that do not explicitly
     * specify {@code octet-align=0}.
     * Rejects only offers that explicitly specify {@code octet-align=0} (caller wants bandwidth-efficient).
     * Parameters are parsed as semicolon-separated {@code key=value} pairs per RFC 4867 §8.2.
     */
    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        // Reject explicit octet-align=0 (caller explicitly wants bandwidth-efficient)
        boolean hasExplicitOctetAlignZero = Arrays.stream(offeredFmtp.split(";"))
                .map(String::strip)
                .anyMatch(AmrWbRtpCodecFactory::isOctetAlignZeroParam);

        if (hasExplicitOctetAlignZero) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbRtpCodec.matchesFmtp: rejected fmtp (explicit octet-align=0): {0}",
                    offeredFmtp);
            return false;
        }

        // Reject mode parameters without explicit octet-align (pragmatic: such offers usually expect BW-efficient)
        String[] params = offeredFmtp.split(";");
        boolean hasOctetAlignExplicit =
                Arrays.stream(params).map(String::strip).anyMatch(AmrWbRtpCodecFactory::isOctetAlignParam);
        boolean hasModeParams = Arrays.stream(params)
                .map(String::strip)
                .anyMatch(p -> p.startsWith("mode-") || p.startsWith("max-red"));

        if (hasModeParams && !hasOctetAlignExplicit) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbRtpCodec.matchesFmtp: rejected fmtp (mode params without explicit octet-align): {0}",
                    offeredFmtp);
            return false;
        }

        // Accept: empty fmtp or explicit octet-align=1
        if (offeredFmtp.isEmpty()) {
            LOGGER.log(System.Logger.Level.TRACE, "AmrWbRtpCodec.matchesFmtp: matched empty fmtp");
        } else {
            LOGGER.log(System.Logger.Level.TRACE, "AmrWbRtpCodec.matchesFmtp: matched: {0}", offeredFmtp);
        }

        return true;
    }

    private static boolean isOctetAlignZeroParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip()) && "0".equals(kv[1].strip());
    }
}
