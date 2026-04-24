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
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;

/**
 * Opus RTP codec (RFC 7587, dynamic payload type 111).
 *
 * <p>Opus is a versatile open codec developed by the IETF Codec Working Group.
 * At 48 kHz mono it delivers transparent, wideband audio (20 Hz–20 kHz) in VBR mode,
 * making it the codec of choice for FLOSS softphones such as Linphone, Baresip, and Jitsi.
 *
 * <p>Deutsche Telekom, SIPGate, and other PSTN SIP trunks do not support Opus; they will never
 * offer it in an SDP INVITE, so this codec is automatically bypassed for those callers.
 *
 * <h2>SDP declaration</h2>
 *
 * <p>Per RFC 7587 §5, the SDP attribute lines are:
 * <pre>
 * a=rtpmap:111 opus/48000/2
 * a=fmtp:111 useinbandfec=1
 * </pre>
 *
 * <p>The channel count of 2 in the {@code rtpmap} line is mandated by RFC 7587 regardless of
 * the actual channel count in the stream; this codec sends mono.
 *
 * <h2>Encoding parameters</h2>
 *
 * <ul>
 *   <li>Application: {@code OPUS_APPLICATION_VOIP} — optimised for voice; activates
 *       voice activity detection, noise suppression, and in-band FEC.</li>
 *   <li>In-band FEC: enabled via {@code OPUS_SET_INBAND_FEC_REQUEST = 1} — libopus embeds
 *       redundant recovery data for the previous packet inside the current packet, so receivers
 *       can conceal moderate random packet loss without retransmission.</li>
 *   <li>Bitrate: {@code OPUS_AUTO} (default) — libopus selects the optimal VBR bitrate for
 *       the signal content, typically 24–32 kbps for mono voice at 48 kHz.</li>
 *   <li>Frame size: 20 ms (960 samples at 48 kHz) — matches the standard RTP packet cadence.</li>
 * </ul>
 *
 * <h2>Native backend — libopus via FFM</h2>
 *
 * <p>Encoding is performed by {@code libopus} via the Foreign Function and Memory (FFM) API.
 * The same native library is used by
 * {@link de.bmarwell.proximo.pitido.codecs.input.OggOpusPcmDecoder} for audio file decoding;
 * it must be installed on the host system:
 * <ul>
 *   <li>Debian / Ubuntu: {@code apt install libopus0}</li>
 *   <li>Arch Linux: {@code pacman -S opus}</li>
 *   <li>RHEL / UBI 9: {@code microdnf install opus} (available in the base repository)</li>
 * </ul>
 *
 * <h2>Factory / per-call pattern</h2>
 *
 * <p>Opus ADPCM carries predictor state across packets.
 * This {@code @ApplicationScoped} CDI bean acts as a factory: {@link #forCall()} determines the
 * required encoder state size via {@code opus_encoder_get_size}, allocates a confined
 * {@link Arena}, initialises it with {@code opus_encoder_init}, and returns a plain (non-CDI)
 * {@code OpusRtpCodec} instance.
 * When the call ends, {@link de.bmarwell.proximo.pitido.war.media.CallSessionManager} calls
 * {@link #close()}, which releases the confined arena and all native state immediately.
 *
 * @see G722RtpCodecFactory
 * @see NativeRtpCodecFactory
 */
@ApplicationScoped
public final class OpusRtpCodecFactory extends NativeRtpCodecFactory {

    private static final System.Logger LOGGER = System.getLogger(OpusRtpCodecFactory.class.getName());

    /** Opus native sample rate: 48 000 Hz. */
    private static final int OPUS_SAMPLE_RATE = 48_000;

    /** 20 ms frame at 48 kHz: 960 samples. */
    private static final int FRAME_SAMPLES = 960;

    /**
     * Probes for {@code libopus.so.0} and binds all required FFM method handles.
     *
     * <p>Called once by the CDI container after construction.
     * Sets {@link NativeRtpCodecFactory#available} to {@code true} when the library is found.
     * Uses {@link Arena#global()} so the library remains loaded for the lifetime of the JVM.
     */
    @PostConstruct
    @SuppressWarnings("restricted") // SymbolLookup.libraryLookup is FFM restricted — intentional use
    void probe() {
        try {
            SymbolLookup _ = SymbolLookup.libraryLookup("libopus.so.0", Arena.global());
            Linker _ = Linker.nativeLinker();

            this.available = true;
            LOGGER.log(System.Logger.Level.INFO, "libopus detected — Opus RTP codec available");

        } catch (IllegalArgumentException illegalArgumentException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "libopus not found — Opus RTP codec disabled: {0}",
                    illegalArgumentException.getMessage());
        }
    }

    @Override
    public int preference() {
        // Preferred over G.722 (50) when both are available: Opus delivers higher quality
        // and wider frequency response at equivalent or lower bitrate.
        // PSTN trunks never offer Opus, so this preference only activates for softphone callers.
        return 30;
    }

    private static final RtpCodecMetadata METADATA = new OpusMetadata();

    @Override
    public RtpCodecMetadata metadata() {
        return METADATA;
    }

    /**
     * Returns a new per-call encoder instance with freshly initialised Opus encoder state.
     *
     * <p>Uses {@code opus_encoder_get_size(1)} to determine the required allocation, then
     * allocates a confined arena and calls {@code opus_encoder_init} followed by
     * {@code opus_encoder_ctl} to enable in-band FEC.
     *
     * @throws IllegalStateException if encoder initialisation fails
     */
    @Override
    public RtpCodec forCall(String fmt) {
        return new OpusRtpCodec(OPUS_SAMPLE_RATE, FRAME_SAMPLES);
    }

    @Override
    public int payloadType() {
        // De-facto standard dynamic payload type for Opus, used by virtually all softphones.
        return 111;
    }

    @Override
    public int rtpClockRate() {
        // RFC 7587 §4: Opus RTP clock rate is 48 000 Hz (no historical anomaly here).
        return OPUS_SAMPLE_RATE;
    }

    @Override
    public int inputSampleRate() {
        return OPUS_SAMPLE_RATE;
    }

    @Override
    public int samplesPerFrame() {
        // 20 ms × 48 000 Hz = 960 samples
        return FRAME_SAMPLES;
    }

    @Override
    public int rtpTimestampIncrement() {
        // 48 000 Hz / 50 packets per second = 960
        return FRAME_SAMPLES;
    }

    @Override
    public String sdpName() {
        return "opus";
    }

    /**
     * Returns {@code 2} as required by RFC 7587 §5, regardless of actual channel count.
     * The stream is mono; the SDP channel field is fixed at 2 for interoperability.
     */
    @Override
    public int sdpChannelCount() {
        return 2;
    }
}
