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

import java.net.InetAddress;
import java.util.Optional;

/**
 * Root contract for public-IP discovery services.
 *
 * <p>The two sub-interfaces — {@link PublicIpv4DiscoveryService} and
 * {@link PublicIpv6DiscoveryService} — specialise this contract for each address family.
 * Callers inject the appropriate sub-interface directly; this base interface exists only
 * to share the {@link #discover()} contract.
 *
 * <p>Note: this interface is intentionally <em>not</em> sealed.
 * Weld (CDI 2.0, used by Open Liberty) generates client-proxy subclasses at runtime
 * for every {@code @ApplicationScoped} bean.
 * If this interface were sealed, the JVM would reject those generated proxies with an
 * {@code IncompatibleClassChangeError} because the proxy class is not in the {@code permits}
 * list.
 *
 * <p>Implementations query remote IP-echo services and cache the result.
 * When all services are unreachable, {@link Optional#empty()} is returned.
 */
public interface PublicIpDiscoveryService {

    /**
     * Returns the public IP address of this host.
     *
     * <p>The concrete type of the returned {@link InetAddress} matches the implementing
     * sub-interface:
     * {@link java.net.Inet4Address} for {@link PublicIpv4DiscoveryService} and
     * {@link java.net.Inet6Address} for {@link PublicIpv6DiscoveryService}.
     *
     * @return the discovered address, or {@link Optional#empty()} when all services fail
     */
    Optional<InetAddress> discover();
}
