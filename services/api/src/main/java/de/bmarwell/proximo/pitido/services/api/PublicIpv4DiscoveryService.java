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
 * Discovers the public IPv4 address of this host.
 *
 * <p>Implementations query remote IP-echo services and cache the result.
 * When all services are unreachable, {@link Optional#empty()} is returned.
 *
 * <p>This interface exists so that the {@code core} module can depend on the contract
 * without coupling to any specific implementation.
 * The implementation is provided at runtime by {@code proximo-pitido-services-ip}.
 */
public interface PublicIpv4DiscoveryService {

    /**
     * Returns the public IPv4 address of this host.
     *
     * @return the discovered address, or {@link Optional#empty()} when all services fail
     */
    Optional<String> discover();
}
