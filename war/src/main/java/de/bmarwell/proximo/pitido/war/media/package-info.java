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

/**
 * SIP/RTP media classes that are tightly coupled to the Liberty SIP container.
 *
 * <p>Only classes that depend on an active SIP or RTP session belong here:
 * <ul>
 *   <li>{@link de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer} — sends decoded PCM audio
 *       as RTP/PCMA packets over UDP to the remote caller</li>
 *   <li>{@link de.bmarwell.proximo.pitido.war.media.CallMedia} — per-call SDP negotiation
 *       result: remote RTP address and local UDP socket</li>
 *   <li>{@link de.bmarwell.proximo.pitido.war.media.SdpNegotiator} — parses SDP offers and
 *       produces SDP answers for SIP {@code INVITE} exchanges</li>
 * </ul>
 *
 * <p>Classes that are reusable outside a SIP container — PCM decoders, MIME-type detection,
 * audio format abstractions — belong in
 * {@code de.bmarwell.proximo.pitido.core.media} in the {@code proximo-pitido-core} module.
 */
package de.bmarwell.proximo.pitido.war.media;
