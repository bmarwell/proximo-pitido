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
import java.io.IOException;

/**
 * Wraps a per-call {@link RtpCodec} instance and overrides {@link #payloadType()} to return the
 * payload type actually negotiated with the caller rather than the codec's static default.
 *
 * <p>VoLTE callers (e.g. Deutsche Telekom) assign dynamic payload types (96–127) per-call via
 * {@code a=rtpmap} lines.
 * The underlying codec (e.g. {@link de.bmarwell.proximo.pitido.codecs.sip.AmrWbRtpCodec}) carries a
 * conventional default PT for identification purposes only.
 * This wrapper carries the actual PT assigned by the caller so that outgoing RTP packet headers
 * and the SDP answer use the same PT value the caller expects.
 *
 * <p>All methods other than {@link #payloadType()} delegate to the wrapped per-call codec instance.
 *
 * @param delegate               the per-call codec instance obtained from {@link RtpCodec#forCall()}
 * @param negotiatedPayloadType  the payload type assigned by the caller in the SDP offer
 */
record NegotiatedRtpCodec(RtpCodec delegate, int negotiatedPayloadType) implements RtpCodec {

    /**
     * Returns the payload type assigned by the caller in the SDP offer's {@code a=rtpmap} line.
     * This value is used in outgoing RTP packet headers and in the SDP answer.
     */
    @Override
    public int payloadType() {
        return this.negotiatedPayloadType;
    }

    @Override
    public boolean isAvailable() {
        return this.delegate.isAvailable();
    }

    @Override
    public int preference() {
        return this.delegate.preference();
    }

    @Override
    public RtpCodec forCall() {
        return new NegotiatedRtpCodec(this.delegate.forCall(), this.negotiatedPayloadType);
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
    public byte[] encode(short[] pcmFrame) throws IOException {
        return this.delegate.encode(pcmFrame);
    }

    @Override
    public int sdpChannelCount() {
        return this.delegate.sdpChannelCount();
    }

    @Override
    public String sdpName() {
        return this.delegate.sdpName();
    }

    @Override
    public String fmtpParams() {
        return this.delegate.fmtpParams();
    }

    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        return this.delegate.matchesFmtp(offeredFmtp);
    }

    @Override
    public void close() {
        this.delegate.close();
    }
}
