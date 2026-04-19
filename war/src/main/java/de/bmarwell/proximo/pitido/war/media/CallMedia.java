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
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Holds the negotiated media parameters for one call leg.
 *
 * <p>Created by {@link SdpNegotiator} from the SDP offer in the incoming INVITE.
 * The caller is responsible for closing {@link #localSocket()} when the call ends.
 *
 * @param localSocket the bound UDP socket used for RTP transmission; must be closed after the call
 * @param remoteRtp   the remote endpoint's RTP address and port, from the SDP {@code c=} and
 *                    {@code m=audio} lines
 * @param sdpAnswer   the fully formatted SDP answer body to include in the 200 OK response
 * @param codec       the negotiated RTP codec; determines payload type, encoding, and clock rate
 */
public record CallMedia(DatagramSocket localSocket, InetSocketAddress remoteRtp, String sdpAnswer, RtpCodec codec) {}
