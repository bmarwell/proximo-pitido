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
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecMetadata;

/**
 * Wraps an {@link RtpCodecFactory} and carries the negotiated payload type and offered fmtp parameters
 * from SDP negotiation.
 *
 * <p>Created by {@link SdpNegotiator} during SDP offer processing and passed to the executor thread.
 * The executor thread calls {@link #forCall(String)} to create the actual {@link RtpCodec} instance
 * with confined FFM arenas on the executor thread.
 *
 * <p>The {@code fmtpAnswer()} method uses the stored {@code offeredFmtp} to generate the correct
 * SDP answer parameters, enabling SDP answer building on the servlet thread before the executor thread
 * receives the factory.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Servlet thread:
 * <ol>
 *   <li>Negotiate codec selection from SDP offer
 *   <li>Create {@code NegotiatedRtpCodecFactory} with negotiated PT and offered fmtp
 *   <li>Call {@code fmtpAnswer()} for SDP answer generation
 *   <li>Pass to executor thread via {@link CallMedia}
 * </ol>
 *
 * <p>Executor thread:
 * <ol>
 *   <li>Call {@code forCall("")} (empty string; fmtp already captured)
 *   <li>Wrap result in try-with-resources or equivalent
 *   <li>Use codec for RTP encoding
 *   <li>Close codec when done
 * </ol>
 *
 * @param delegate              the underlying codec factory
 * @param negotiatedPayloadType the payload type assigned by the caller in the SDP offer
 * @param offeredFmtp           the raw {@code a=fmtp} parameter string from the caller's SDP offer;
 *                              empty string if no {@code a=fmtp} line was present
 */
public record NegotiatedRtpCodecFactory(RtpCodecFactory delegate, int negotiatedPayloadType, String offeredFmtp)
        implements RtpCodecFactory {

    @Override
    public boolean isAvailable() {
        return this.delegate.isAvailable();
    }

    @Override
    public int preference() {
        return this.delegate.preference();
    }

    @Override
    public RtpCodecMetadata metadata() {
        return new WrappedMetadata(this.delegate.metadata(), this.negotiatedPayloadType);
    }

    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        return this.delegate.matchesFmtp(offeredFmtp);
    }

    @Override
    public RtpCodec forCall(String offeredFmtp) {
        // Use the stored offeredFmtp from negotiation, not the parameter
        // (parameter is ignored; method signature matches RtpCodecFactory contract).
        RtpCodec actualCodec = this.delegate.forCall(this.offeredFmtp);
        return new NegotiatedRtpCodec(actualCodec, this.negotiatedPayloadType, this.offeredFmtp);
    }

    @Override
    public String fmtpAnswer(String offeredFmtp) {
        // Use the stored offeredFmtp from negotiation.
        return this.delegate.fmtpAnswer(this.offeredFmtp);
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
