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
 * Encapsulates the negotiated codec state: payload type and offered fmtp parameters from SDP negotiation.
 *
 * <p>Replaces the need to pass both codec factory and metadata separately across thread boundaries.
 * Created by {@link SdpNegotiator} during SDP offer processing and passed to the executor thread.
 *
 * <p>Two phases of use:
 * <ul>
 *   <li><strong>Servlet thread (SDP negotiation):</strong> Call {@code fmtpAnswer()} to generate
 *       correct fmtp parameters for the SDP answer, without creating a codec instance.
 *   <li><strong>Executor thread (RTP encoding):</strong> Call {@code forCall()} to create the actual
 *       {@link RtpCodec} instance with confined FFM arenas on the thread where it will be used.
 * </ul>
 *
 * <p>Key invariant: both phases can complete successfully using only the wrapped factory and
 * negotiated state, without needing a per-call codec instance until the executor thread.
 *
 * @param delegate              the underlying codec factory (stateless)
 * @param negotiatedPayloadType the RTP payload type assigned by the caller in the SDP offer
 * @param offeredFmtp           the raw {@code a=fmtp} parameter string from the caller's SDP offer;
 *                              empty string if no {@code a=fmtp} line was present
 */
public record NegotiatedRtpCodecFactory(RtpCodecFactory delegate, int negotiatedPayloadType, String offeredFmtp) {

    /**
     * Creates an {@link RtpCodec} instance for this call, with negotiated payload type and fmtp.
     *
     * <p>Must be called on the thread where the codec will be used (typically executor thread).
     * The codec is wrapped to override {@code payloadType()} with the negotiated value.
     *
     * @return a codec instance configured for RTP encoding on this call
     */
    public RtpCodec forCall() {
        RtpCodec actualCodec = this.delegate.forCall(this.offeredFmtp);
        return new NegotiatedRtpCodec(actualCodec, this.negotiatedPayloadType, this.offeredFmtp);
    }

    /**
     * Generates the fmtp parameters for the SDP answer using stored negotiated state.
     *
     * <p>Delegates to the underlying factory's {@code fmtpAnswer()}, which uses only the
     * {@code offeredFmtp} to generate an answer—no codec instance needed.
     * Safe to call on any thread during SDP negotiation.
     *
     * @return the fmtp parameter string for the SDP answer
     */
    public String fmtpAnswer() {
        return this.delegate.fmtpAnswer(this.offeredFmtp);
    }

    /**
     * Returns codec metadata with {@code payloadType()} overridden to the negotiated value.
     *
     * @return metadata with negotiated payload type
     */
    public RtpCodecMetadata metadata() {
        return new WrappedMetadata(this.delegate.metadata(), this.negotiatedPayloadType);
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
