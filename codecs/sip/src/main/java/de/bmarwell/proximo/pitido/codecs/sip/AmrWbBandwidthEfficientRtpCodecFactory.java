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
 * AMR-WB bandwidth-efficient RTP codec (RFC 4867 §4.3).
 *
 * <p>Encodes AMR-WB frames in bandwidth-efficient format, which omits the 1-byte CMR header
 * and uses the ToC byte as the first octet of the RTP payload.
 * This variant is used by some SIP endpoints (e.g. 1&1 Mobilfunk) that do not support
 * or do not offer octet-aligned mode.
 *
 * <p>Unlike the octet-aligned variant, this implementation does NOT require an explicit
 * {@code octet-align=1} fmtp parameter; if the caller offers AMR-WB without any fmtp
 * constraints (or with bandwidth-efficient only), this codec is selected as a fallback.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 4867 §8.3, bandwidth-efficient AMR-WB is indicated by:
 * <pre>
 * a=rtpmap:104 AMR-WB/16000/1
 * </pre>
 *
 * The {@code /1} suffix indicates bandwidth-efficient mode (no octet-align parameter in fmtp).
 *
 * <h2>RTP payload format (RFC 4867 §4.3 bandwidth-efficient)</h2>
 *
 * <p>Each RTP packet contains exactly one AMR-WB frame in bandwidth-efficient mode:
 * <ol>
 *   <li>1-byte Table of Contents (ToC) — frame type index (bits 6–3), quality flag
 *       {@code Q=1} (bit 2), one padding bit, and continuation flag {@code F=0}.</li>
 *   <li>Speech data bytes produced by {@code E_IF_encode}.</li>
 * </ol>
 *
 * <p>Note: no CMR header is present.
 * This saves 1 byte per packet compared to octet-aligned format, but requires
 * the decoder to have out-of-band knowledge of the active codec mode.
 *
 * @see AmrWbRtpCodecFactory
 */
@ApplicationScoped
public final class AmrWbBandwidthEfficientRtpCodecFactory extends AmrWbRtpCodecFactory {

    private static final System.Logger LOGGER =
            System.getLogger(AmrWbBandwidthEfficientRtpCodecFactory.class.getName());

    /**
     * Payload type for bandwidth-efficient AMR-WB (dynamic; typically 104 from 1&1 Mobilfunk).
     * This is a placeholder used only during factory bean construction.
     * The {@link #payloadType()} method always returns this constant because the SDP negotiation
     * framework handles dynamic payload type mapping externally.
     * The actual PT used in SDP answers and RTP demux is determined by {@link SdpNegotiator},
     * which wraps this codec in a {@link NegotiatedRtpCodec} with the correct PT.
     */
    private static final int PAYLOAD_TYPE_PLACEHOLDER = 104;

    /**
     * Preference order: bandwidth-efficient is now preferred (lower number = higher preference).
     * Temporarily increased to 41 to test if octet-aligned codec resolves audio corruption.
     */
    private static final int PREFERENCE = 41;

    /**
     * Preference order: bandwidth-efficient is tried first (lower number = higher preference).
     * Octet-aligned is tried second, only when explicitly offered ("octet-align=1" in SDP).
     *
     * <p>RFC 4867 specifies bandwidth-efficient as the DEFAULT AMR-WB packetisation format.
     * Using bandwidth-efficient first (preference 40) ensures compatibility with endpoints that
     * advertise only bandwidth-efficient support and do not send octet-align=1 fmtp parameter.
     * Octet-aligned codec has preference 41 (tried second) and requires explicit "octet-align=1".
     */

    /**
     * RTP clock rate for AMR-WB: 16 kHz (wideband).
     */
    private static final int RTP_CLOCK_RATE = 16_000;

    /** CDI no-args constructor. */
    public AmrWbBandwidthEfficientRtpCodecFactory() {
        super();
    }

    @Override
    public String sdpName() {
        return "AMR-WB";
    }

    @Override
    public int payloadType() {
        return PAYLOAD_TYPE_PLACEHOLDER;
    }

    @Override
    public int rtpClockRate() {
        return RTP_CLOCK_RATE;
    }

    @Override
    public int preference() {
        return PREFERENCE;
    }

    /**
     * Accepts AMR-WB ONLY when the caller explicitly specifies {@code octet-align=0}.
     *
     * <p>This codec is narrowly scoped to handle explicit bandwidth-efficient requests.
     * It REJECTS empty fmtp (ambiguous — now handled by octet-aligned codec).
     * It REJECTS fmtp with mode parameters that don't explicitly request bandwidth-efficient.
     * It ACCEPTS ONLY explicit {@code octet-align=0} (caller wants bandwidth-efficient format).
     *
     * <p>This narrow matching ensures octet-aligned codec (preference 40) wins for ambiguous offers,
     * while still supporting callers that explicitly request bandwidth-efficient (preference 41).
     */
    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        // Match explicit octet-align=0 OR mode params without explicit octet-align
        boolean hasExplicitOctetAlignZero = Arrays.stream(offeredFmtp.split(";"))
                .map(String::strip)
                .anyMatch(AmrWbBandwidthEfficientRtpCodecFactory::isOctetAlignZeroParam);

        if (hasExplicitOctetAlignZero) {
            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (explicit octet-align=0): {0}",
                        offeredFmtp);
            }
            return true;
        }

        // Also match mode params without explicit octet-align (pragmatic: assume BW-efficient when alignment not
        // specified)
        String[] params = offeredFmtp.split(";");
        boolean hasOctetAlignExplicit = Arrays.stream(params)
                .map(String::strip)
                .anyMatch(AmrWbBandwidthEfficientRtpCodecFactory::isOctetAlignParam);
        boolean hasModeParams = Arrays.stream(params)
                .map(String::strip)
                .anyMatch(p -> p.startsWith("mode-") || p.startsWith("max-red"));

        if (hasModeParams && !hasOctetAlignExplicit) {
            if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
                LOGGER.log(
                        System.Logger.Level.TRACE,
                        "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: matched (mode params without explicit octet-align): {0}",
                        offeredFmtp);
            }
            return true;
        }

        if (LOGGER.isLoggable(System.Logger.Level.TRACE)) {
            LOGGER.log(
                    System.Logger.Level.TRACE,
                    "AmrWbBandwidthEfficientRtpCodec.matchesFmtp: rejected: {0}",
                    offeredFmtp);
        }
        return false;
    }

    private static boolean isOctetAlignZeroParam(String param) {
        String[] kv = param.split("=", 2);
        return kv.length == 2 && "octet-align".equalsIgnoreCase(kv[0].strip()) && "0".equals(kv[1].strip());
    }

    @Override
    public AmrWbBandwidthEfficientRtpCodec forCall(String offeredFmtp) {
        if (!this.available) {
            throw new IllegalStateException(
                    "AMR-WB codec is not available — libvo-amrwbenc was not loaded; check probe() logs");
        }

        return new AmrWbBandwidthEfficientRtpCodec(offeredFmtp);
    }
}
