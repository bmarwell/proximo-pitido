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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
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
 * <p>Address resolution priority:
 *
 * <ol>
 *   <li>{@code SIP_PUBLIC_HOST} / {@code sip.public.host} — explicit operator override; used
 *       as-is without any network call.</li>
 *   <li>Public IP auto-detected via {@link PublicIpDiscoveryService} — used when
 *       {@code SIP_REGISTRATION_ENABLED=true} and {@code SIP_PUBLIC_HOST} is absent.
 *       Behind NAT (home router, Docker host), a local IP in the Contact header is unreachable
 *       from the registrar, so a public IP is necessary.</li>
 *   <li>Outbound interface IP detected via a connected {@link DatagramSocket} pointed at the
 *       configured SIP registrar — reliable in Docker and Podman environments because it consults
 *       the OS routing table rather than enumerating network interfaces.
 *       No data is sent; the socket is used only for routing-table lookup.</li>
 *   <li>First non-loopback, non-link-local IPv4 address on any active network interface —
 *       final fallback used when registration is disabled or all other detection methods fail.</li>
 * </ol>
 */
@ApplicationScoped
public class LocalSipHostProvider {

    private static final System.Logger LOGGER = System.getLogger(LocalSipHostProvider.class.getName());

    private static final String FALLBACK_ADDRESS = "127.0.0.1";

    @Inject
    @ConfigProperty(name = "sip.public.host")
    Optional<String> configuredHost;

    @Inject
    @ConfigProperty(name = "sip.registrar")
    Optional<String> registrar;

    @Inject
    @ConfigProperty(name = "sip.registration.enabled", defaultValue = "true")
    boolean registrationEnabled;

    @Inject
    PublicIpDiscoveryService publicIpDiscoveryService;

    /** CDI no-args constructor. */
    public LocalSipHostProvider() {}

    /** Returns the address to advertise in the SIP {@code Contact} header. */
    public String get() {
        if (this.configuredHost.isPresent()) {
            String host = this.configuredHost.get();
            LOGGER.log(System.Logger.Level.INFO, "SIP Contact address (sip.public.host / SIP_PUBLIC_HOST): {0}", host);

            return host;
        }

        if (!this.registrationEnabled) {
            String host = detectLocalAddress();
            LOGGER.log(System.Logger.Level.INFO, "Local SIP host (auto-detected, registration disabled): {0}", host);

            return host;
        }

        Optional<String> publicIp = this.publicIpDiscoveryService.discover();

        if (publicIp.isPresent()) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "SIP Contact address (public IP via PublicIpDiscoveryService): {0}",
                    publicIp.get());

            return publicIp.get();
        }

        LOGGER.log(
                System.Logger.Level.WARNING,
                "All public IP discovery services unreachable; falling back to local IP for Contact header."
                        + " Incoming calls may fail if this host is behind NAT.");
        String host = detectLocalAddress();
        LOGGER.log(System.Logger.Level.INFO, "Local SIP host (auto-detected fallback): {0}", host);

        return host;
    }

    private String detectLocalAddress() {
        if (this.registrar.isPresent()) {
            Optional<String> routedAddress = detectViaRouting(this.registrar.get());

            if (routedAddress.isPresent()) {
                return routedAddress.get();
            }
        }

        try {
            return findLocalIpv4Address().orElse(FALLBACK_ADDRESS);
        } catch (SocketException socketException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not auto-detect local SIP host, using loopback",
                    socketException);

            return FALLBACK_ADDRESS;
        }
    }

    /**
     * Determines the outbound interface IP by connecting a UDP socket to the registrar.
     *
     * <p>No data is sent.
     * The OS routing table selects the correct interface, which is the one Liberty will use
     * for outbound SIP connections — making this reliable in Docker and Podman environments
     * where interface enumeration may return multiple candidates.
     */
    private Optional<String> detectViaRouting(String registrarHost) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(registrarHost), 5060);
            InetAddress localAddress = socket.getLocalAddress();

            if (localAddress.isLoopbackAddress()
                    || localAddress.isAnyLocalAddress()
                    || localAddress.isLinkLocalAddress()) {
                return Optional.empty();
            }

            if (!(localAddress instanceof Inet4Address)) {
                return Optional.empty();
            }

            return Optional.of(localAddress.getHostAddress());
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Routing-table IP detection failed for registrar [{0}], falling back to NIC enumeration",
                    registrarHost,
                    ioException);

            return Optional.empty();
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
        } catch (SocketException socketException) {
            return false;
        }
    }
}
