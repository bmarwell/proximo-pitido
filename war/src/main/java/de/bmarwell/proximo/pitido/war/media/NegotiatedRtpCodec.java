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

import de.bmarwell.proximo.pitido.codecs.sip.AmrWbRtpCodecFactory;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodec;
import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;

/**
 * Wraps a {@link RtpCodecFactory} and overrides {@link #payloadType()} to return the payload type
 * actually negotiated with the caller rather than the codec's static default.
 *
 * <p>VoLTE callers (e.g. Deutsche Telekom) assign dynamic payload types (96–127) per-call via
 * {@code a=rtpmap} lines.
 * The underlying codec (e.g. {@link AmrWbRtpCodecFactory}) carries a
 * conventional default PT for identification purposes only.
 * This wrapper carries the actual PT assigned by the caller so that outgoing RTP packet headers
 * and the SDP answer use the same PT value the caller expects.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator#negotiate} returns an instance
 * whose {@code delegate} is the CDI bean descriptor — no confined arena is allocated yet.
 * The announcement or menu-runner lambda calls {@link #forCall()} at its start, on the executor
 * thread that will also call {@link #encode} and {@link #close}.
 * This guarantees that the {@link java.lang.foreign.Arena#ofConfined() confined arena} created by
 * native codecs is owned by the thread that uses and closes it, preventing
 * {@link java.lang.WrongThreadException}.
 *
 * <p>All methods other than {@link #payloadType()} and {@link #forCall()} delegate transparently
 * to the wrapped codec instance.
 *
 * @param delegate               the codecFactory instance
 * @param negotiatedPayloadType  the payload type assigned by the caller in the SDP offer
 * @param offeredFmtp            the raw {@code a=fmtp} parameter string from the caller's SDP offer
 *                               for this payload type; empty string if no {@code a=fmtp} line was
 *                               present in the offer
 */
record NegotiatedRtpCodec(RtpCodecFactory delegate, int negotiatedPayloadType, String offeredFmtp)
        implements RtpCodecFactory {

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
    public <T extends RtpCodec> T forCall(String offeredFmtp) {
        return this.delegate.forCall(this.offeredFmtp);
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
    public int sdpChannelCount() {
        return this.delegate.sdpChannelCount();
    }

    @Override
    public String sdpName() {
        return this.delegate.sdpName();
    }

    @Override
    public boolean matchesFmtp(String offeredFmtp) {
        return this.delegate.matchesFmtp(offeredFmtp);
    }
}
