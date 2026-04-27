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

import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecMetadata;
import java.io.IOException;

/**
 * Wraps an {@link RtpCodec} and overrides {@link #payloadType()} to return the payload type
 * actually negotiated with the caller rather than the codec's static default.
 *
 * <p>VoLTE callers (e.g. Deutsche Telekom) assign dynamic payload types (96–127) per-call via
 * {@code a=rtpmap} lines.
 * The underlying codec carries its conventional default PT for identification purposes only.
 * This wrapper carries the actual PT assigned by the caller so that outgoing RTP packet headers
 * and the SDP answer use the same PT value the caller expects.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Created by {@link CallMedia#getOrCreateCodec()} after the codec instance is created via
 * {@code codecFactory.forCall(offeredFmtp)} on the executor thread.
 * This ensures that the {@link java.lang.foreign.Arena#ofConfined() confined arena} created by
 * native codecs is owned by the thread that uses and closes it, preventing
 * {@link java.lang.WrongThreadException}.
 *
 * <p>All codec methods delegate transparently to the wrapped codec instance.
 *
 * @param delegate                the RtpCodec instance
 * @param negotiatedPayloadType   the payload type assigned by the caller in the SDP offer
 * @param offeredFmtp             the raw {@code a=fmtp} parameter string from the caller's SDP offer;
 *                                empty string if no {@code a=fmtp} line was present
 */
record NegotiatedRtpCodec(RtpCodec delegate, int negotiatedPayloadType, String offeredFmtp) implements RtpCodec {

    @Override
    public RtpCodecMetadata metadata() {
        return new WrappedMetadata(this.delegate.metadata(), this.negotiatedPayloadType);
    }

    @Override
    public byte[] encode(short[] pcmSamples) throws IOException {
        return this.delegate.encode(pcmSamples);
    }

    @Override
    public String fmtpAnswer(String offeredFmtp) {
        return this.delegate.fmtpAnswer(offeredFmtp);
    }

    @Override
    public String fmtpParams() {
        return this.delegate.fmtpParams();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    /**
     * Wraps delegate metadata and overrides {@code payloadType()} to return the negotiated value.
     *
     * <p>All other metadata methods delegate to the wrapped metadata unchanged.
     */
    private record WrappedMetadata(RtpCodecMetadata delegate, int negotiatedPayloadType) implements RtpCodecMetadata {

        @Override
        public int payloadType() {
            return this.negotiatedPayloadType;
        }

        @Override
        public int rtpClockRate() {
            return this.delegate.rtpClockRate();
        }

        @Override
        public int inputSampleRate() {
            return this.delegate.inputSampleRate();
        }

        @Override
        public int samplesPerFrame() {
            return this.delegate.samplesPerFrame();
        }

        @Override
        public int rtpTimestampIncrement() {
            return this.delegate.rtpTimestampIncrement();
        }

        @Override
        public String sdpName() {
            return this.delegate.sdpName();
        }

        @Override
        public int sdpChannelCount() {
            return this.delegate.sdpChannelCount();
        }
    }
}
