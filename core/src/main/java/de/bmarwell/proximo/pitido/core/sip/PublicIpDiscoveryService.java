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
 * Discovers the public IPv4 address of this host by querying well-known plain-text IP-echo services.
 *
 * <p>Services are tried in order; the first successful, valid response is used.
 * The result is cached for {@value #CACHE_HOURS} hour so that repeated calls during a registration
 * cycle do not generate unnecessary HTTP traffic.
 *
 * <p>A fresh JAX-RS {@link Client} is created for each {@link #discover()} invocation and closed
 * immediately afterwards.
 * Each HTTP request times out after {@value #REQUEST_TIMEOUT_SECONDS} seconds.
 * A failed request is logged at DEBUG level and the next service is tried.
 * When all services fail, {@link Optional#empty()} is returned; the caller is responsible for
 * falling back to a locally detected address.
 *
 * <h2>Services queried (in order)</h2>
 *
 * <ol>
 *   <li>{@code https://v4.ident.me} — plain text, IPv4 only</li>
 *   <li>{@code https://api.ipify.org} — plain text, IPv4 only</li>
 *   <li>{@code https://checkip.amazonaws.com} — plain text, IPv4 only</li>
 * </ol>
 */
@ApplicationScoped
public class PublicIpDiscoveryService {

    private static final System.Logger LOGGER = System.getLogger(PublicIpDiscoveryService.class.getName());

    static final List<URI> DISCOVERY_URLS = List.of(
            URI.create("https://v4.ident.me"),
            URI.create("https://api.ipify.org"),
            URI.create("https://checkip.amazonaws.com"));

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

    private final AtomicReference<String> cachedIp = new AtomicReference<>();
    private volatile Instant cacheExpiry = Instant.MIN;

    /** CDI no-args constructor. */
    public PublicIpDiscoveryService() {}

    /**
     * Returns the public IPv4 address of this host, querying remote services if the cache is stale.
     *
     * @return the discovered IPv4 address, or {@link Optional#empty()} when all services are
     *     unreachable
     */
    public Optional<String> discover() {
        String cached = this.cachedIp.get();

        if (cached != null && Instant.now().isBefore(this.cacheExpiry)) {
            return Optional.of(cached);
        }

        return queryDiscoveryServices();
    }

    private Optional<String> queryDiscoveryServices() {
        Client client = this.clientFactory.get();

        try {
            for (URI url : DISCOVERY_URLS) {
                Optional<String> ip = queryService(client, url);

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

    private Optional<String> queryService(Client client, URI url) {
        try {
            String body = client.target(url)
                    .request(MediaType.TEXT_PLAIN_TYPE)
                    .get(String.class)
                    .strip();

            if (!isValidPublicIpv4Address(body)) {
                LOGGER.log(System.Logger.Level.DEBUG, "Unexpected response from {0}: {1}", url, body);
                return Optional.empty();
            }

            LOGGER.log(System.Logger.Level.INFO, "Discovered public IP {0} via {1}", body, url);
            return Optional.of(body);
        } catch (ProcessingException processingException) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Failed to query public IP from {0}: {1}",
                    url,
                    processingException.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns {@code true} only when {@code candidate} is a literal dotted-decimal IPv4 address.
     *
     * <p>Uses a round-trip check — {@link InetAddress#getHostAddress()} must equal the original
     * input — to reject DNS hostnames that {@link InetAddress#getByName(String)} would otherwise
     * silently resolve.
     */
    static boolean isValidPublicIpv4Address(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 15) {
            return false;
        }

        try {
            InetAddress addr = InetAddress.getByName(candidate);
            return addr instanceof Inet4Address && addr.getHostAddress().equals(candidate);
        } catch (UnknownHostException unknownHostException) {
            return false;
        }
    }
}
