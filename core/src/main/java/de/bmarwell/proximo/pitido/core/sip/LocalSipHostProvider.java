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
package de.bmarwell.proximo.pitido.core.sip;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Provides the local SIP host address to use in the SIP {@code Contact} header.
 *
 * <p>Prefers the {@code sip.public.host} / {@code SIP_PUBLIC_HOST} config property (the
 * public-facing address behind NAT). Falls back to auto-detecting the first non-loopback,
 * non-link-local IPv4 address on any active network interface.
 */
@ApplicationScoped
public class LocalSipHostProvider {

    private static final System.Logger LOGGER = System.getLogger(LocalSipHostProvider.class.getName());

    private static final String FALLBACK_ADDRESS = "127.0.0.1";

    @Inject
    @ConfigProperty(name = "sip.public.host")
    Optional<String> configuredHost;

    /** Returns the address to advertise in the SIP {@code Contact} header. */
    public String get() {
        return configuredHost.orElseGet(this::detectLocalAddress);
    }

    private String detectLocalAddress() {
        try {
            return findLocalIpv4Address().orElse(FALLBACK_ADDRESS);
        } catch (SocketException ex) {
            LOGGER.log(System.Logger.Level.WARNING, "Could not auto-detect local SIP host, using loopback", ex);
            return FALLBACK_ADDRESS;
        }
    }

    private Optional<String> findLocalIpv4Address() throws SocketException {
        return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(this::isUsableInterface)
                .flatMap(ni -> Collections.list(ni.getInetAddresses()).stream())
                .filter(addr -> addr instanceof Inet4Address)
                .filter(addr -> !addr.isLoopbackAddress())
                .filter(addr -> !addr.isLinkLocalAddress())
                .map(addr -> addr.getHostAddress())
                .findFirst();
    }

    private boolean isUsableInterface(NetworkInterface ni) {
        try {
            return ni.isUp() && !ni.isLoopback() && !ni.isVirtual();
        } catch (SocketException ex) {
            return false;
        }
    }
}
