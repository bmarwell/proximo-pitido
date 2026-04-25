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

import de.bmarwell.proximo.pitido.codecs.sip.RtpCodecFactory;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the negotiated media parameters for one call leg.
 *
 * <p>Created by {@link SdpNegotiator} from the SDP offer in the incoming INVITE.
 * The caller is responsible for closing {@link #localSocket()} when the call ends.
 *
 * <p>{@link #held} is an {@link AtomicBoolean} embedded in this record.
 * The record keeps its identity (the reference is final) while the hold state is
 * mutated thread-safely by the SIP thread (re-INVITE handler) concurrently with
 * the RTP send thread.
 * Use {@link #hold()}, {@link #unhold()}, and {@link #isHeld()} rather than accessing
 * the component directly.
 *
 * @param localSocket               the bound UDP socket used for RTP transmission; must be
 *                                  closed after the call
 * @param remoteRtp                 the remote endpoint's RTP address and port, from the SDP
 *                                  {@code c=} and {@code m=audio} lines
 * @param sdpAnswer                 the fully formatted SDP answer body to include in the 200 OK
 *                                  response
 * @param codec                     the negotiated RTP codec; determines payload type, encoding,
 *                                  and clock rate
 * @param telephoneEventPayloadType the dynamic RTP payload type negotiated for RFC 4733
 *                                  telephone-event, or {@code -1} if the remote side did not
 *                                  offer telephone-event in its SDP
 * @param held                      thread-safe hold flag; mutated via {@link #hold()} and
 *                                  {@link #unhold()}, never replaced
 */
public record CallMedia(
        DatagramSocket localSocket,
        InetSocketAddress remoteRtp,
        String sdpAnswer,
        RtpCodecFactory codecFactory,
        String offeredFmtp,
        int telephoneEventPayloadType,
        AtomicBoolean held) {

    /**
     * Pauses RTP transmission.
     * The audio sender will stop sending packets and pause PCM consumption until
     * {@link #unhold()} is called.
     */
    public void hold() {
        this.held.set(true);
    }

    /**
     * Resumes RTP transmission after a hold.
     */
    public void unhold() {
        this.held.set(false);
    }

    /**
     * Returns {@code true} when the call is currently on hold.
     */
    public boolean isHeld() {
        return this.held.get();
    }
}
