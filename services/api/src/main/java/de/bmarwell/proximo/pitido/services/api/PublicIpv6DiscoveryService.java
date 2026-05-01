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
package de.bmarwell.proximo.pitido.services.api;

import java.util.Optional;

/**
 * Discovers the public IPv6 address of this host.
 *
 * <p>Implementations query remote IPv6-only IP-echo services and cache the result.
 * This is primarily useful on DS-Lite connections, where the host has no public IPv4 address
 * and must advertise an IPv6 address in the SIP {@code Contact} header.
 *
 * <p>When all services are unreachable (e.g. the host has no IPv6 connectivity),
 * {@link Optional#empty()} is returned and the caller falls back to IPv4.
 *
 * <p>Returned addresses are bare IPv6 literals without enclosing brackets,
 * e.g. {@code "2001:db8::1"}.
 * Callers that embed the address in a SIP URI must add brackets themselves
 * ({@code sip:user@[2001:db8::1]}) per RFC 3261 §19.1.1.
 */
public interface PublicIpv6DiscoveryService {

    /**
     * Returns the public IPv6 address of this host.
     *
     * @return the discovered address without brackets, or {@link Optional#empty()} when all services fail
     */
    Optional<String> discover();
}
