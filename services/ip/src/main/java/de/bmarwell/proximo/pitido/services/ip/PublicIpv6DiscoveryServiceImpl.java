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
package de.bmarwell.proximo.pitido.services.ip;

import de.bmarwell.proximo.pitido.services.api.PublicIpv6DiscoveryService;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;

/**
 * Discovers the public IPv6 address of this host by querying IPv6-only plain-text IP-echo services.
 *
 * <p>Services are tried in order; the first successful, valid response is used.
 * The result is cached for {@value #CACHE_HOURS} hour so that repeated calls during a registration
 * cycle do not generate unnecessary HTTP traffic.
 *
 * <p>A fresh JAX-RS {@link Client} is created for each {@link #discover()} invocation and closed
 * immediately afterwards.
 * Each HTTP request times out after {@value #REQUEST_TIMEOUT_SECONDS} seconds.
 * A failed request is logged at DEBUG level and the next service is tried.
 * When all services fail (e.g. the host has no IPv6 connectivity),
 * {@link Optional#empty()} is returned; the caller is responsible for falling back to IPv4.
 *
 * <h2>Services queried (in order)</h2>
 *
 * <ol>
 *   <li>{@code https://v6.ident.me} — plain text, IPv6 only</li>
 *   <li>{@code https://api6.ipify.org} — plain text, IPv6 only</li>
 * </ol>
 */
@ApplicationScoped
public class PublicIpv6DiscoveryServiceImpl implements PublicIpv6DiscoveryService {

    private static final System.Logger LOGGER = System.getLogger(PublicIpv6DiscoveryServiceImpl.class.getName());

    static final List<URI> DISCOVERY_URLS =
            List.of(URI.create("https://v6.ident.me"), URI.create("https://api6.ipify.org"));

    private static final int CACHE_HOURS = 1;
    private static final long REQUEST_TIMEOUT_SECONDS = 2L;

    /**
     * Factory used to create a short-lived JAX-RS {@link Client} per discovery invocation.
     * Package-private so tests can substitute a supplier that returns a mock client.
     */
    Supplier<Client> clientFactory = () -> ClientBuilder.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private final AtomicReference<InetAddress> cachedIp = new AtomicReference<>();
    private volatile Instant cacheExpiry = Instant.MIN;

    /** CDI no-args constructor. */
    public PublicIpv6DiscoveryServiceImpl() {}

    @Override
    public Optional<InetAddress> discover() {
        InetAddress cached = this.cachedIp.get();

        if (cached != null && Instant.now().isBefore(this.cacheExpiry)) {
            return Optional.of(cached);
        }

        return queryDiscoveryServices();
    }

    private Optional<InetAddress> queryDiscoveryServices() {
        Client client = this.clientFactory.get();

        try {
            for (URI url : DISCOVERY_URLS) {
                Optional<InetAddress> ip = queryService(client, url);

                if (ip.isPresent()) {
                    this.cachedIp.set(ip.get());
                    this.cacheExpiry = Instant.now().plus(Duration.ofHours(CACHE_HOURS));
                    return ip;
                }
            }
        } finally {
            client.close();
        }

        return Optional.empty();
    }

    private Optional<InetAddress> queryService(Client client, URI url) {
        try {
            String body = client.target(url)
                    .request(MediaType.TEXT_PLAIN_TYPE)
                    .get(String.class)
                    .strip();

            Optional<InetAddress> addr = parseIpv6Address(body);

            if (addr.isEmpty()) {
                LOGGER.log(System.Logger.Level.DEBUG, "Unexpected response from {0}: {1}", url, body);
                return Optional.empty();
            }

            LOGGER.log(
                    System.Logger.Level.INFO,
                    "Discovered public IPv6 address {0} via {1}",
                    addr.get().getHostAddress(),
                    url);
            return addr;
        } catch (ProcessingException processingException) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Failed to query public IPv6 from {0}: {1}",
                    url,
                    processingException.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses {@code candidate} as a bare literal IPv6 address.
     *
     * <p>The check uses the presence of {@code ':'} as the IPv6 discriminator, then attempts to
     * parse the address with {@link InetAddress#getByName(String)}.
     * This avoids the round-trip equality trap where JVM normalisation expands a compressed form
     * such as {@code "2001:db8::1"} to {@code "2001:db8:0:0:0:0:0:1"}, which would otherwise
     * cause the input to fail an equality comparison against the normalised output.
     * IPv4-mapped addresses ({@code ::ffff:1.2.3.4}) are rejected because they embed an IPv4 address
     * and would be misleading in an IPv6-only context.
     *
     * @return the parsed {@link Inet6Address}, or {@link Optional#empty()} if {@code candidate}
     *     is not a valid public IPv6 literal
     */
    static Optional<InetAddress> parseIpv6Address(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }

        // Reject any bracketed form — services should return bare addresses
        if (candidate.startsWith("[") || candidate.endsWith("]")) {
            return Optional.empty();
        }

        // All IPv6 addresses contain ':'; reject IPv4 addresses and hostnames early
        if (!candidate.contains(":")) {
            return Optional.empty();
        }

        try {
            InetAddress addr = InetAddress.getByName(candidate);

            if (!(addr instanceof Inet6Address inet6)) {
                return Optional.empty();
            }

            // Reject IPv4-mapped addresses
            if (inet6.isIPv4CompatibleAddress()) {
                return Optional.empty();
            }

            return Optional.of(addr);
        } catch (UnknownHostException unknownHostException) {
            return Optional.empty();
        }
    }
}
