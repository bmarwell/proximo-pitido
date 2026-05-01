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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicIpv6DiscoveryServiceImplTest {

    @Mock
    Client client;

    @Mock
    WebTarget webTarget;

    @Mock
    Invocation.Builder requestBuilder;

    PublicIpv6DiscoveryServiceImpl discoveryService;

    @BeforeEach
    void setUp() {
        this.discoveryService = new PublicIpv6DiscoveryServiceImpl();
        this.discoveryService.clientFactory = () -> this.client;
    }

    private void wireClientChain() {
        when(this.client.target(any(URI.class))).thenReturn(this.webTarget);
        when(this.webTarget.request(any(MediaType.class))).thenReturn(this.requestBuilder);
    }

    @Test
    void discover_firstServiceRespondsWithValidIpv6_returnsThatAddress() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("2001:db8::1");

        // when
        Optional<InetAddress> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertInstanceOf(Inet6Address.class, result.get());
        verify(this.client, times(1)).target(PublicIpv6DiscoveryServiceImpl.DISCOVERY_URLS.getFirst());
    }

    @Test
    void discover_firstServiceFails_triesNextAndReturnsItsAddress() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class))
                .thenThrow(new ProcessingException("connection refused"))
                .thenReturn("2001:db8::2");

        // when
        Optional<InetAddress> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertInstanceOf(Inet6Address.class, result.get());
    }

    @Test
    void discover_allServicesFail_returnsEmpty() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenThrow(new ProcessingException("no route to host"));

        // when
        Optional<InetAddress> result = this.discoveryService.discover();

        // then
        assertFalse(result.isPresent());
    }

    @Test
    void discover_serviceReturnsInvalidBody_skipsToNextService() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class))
                .thenReturn("not-an-ip-address")
                .thenReturn("2001:db8::3");

        // when
        Optional<InetAddress> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertInstanceOf(Inet6Address.class, result.get());
    }

    @Test
    void discover_secondCallWithinCacheWindow_doesNotQueryRemoteServices() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("2001:db8::1");
        this.discoveryService.discover();

        // when
        Optional<InetAddress> cached = this.discoveryService.discover();

        // then
        assertTrue(cached.isPresent());
        assertInstanceOf(Inet6Address.class, cached.get());
        verify(this.client, times(1)).target(any(URI.class));
    }

    @Test
    void discover_responseWithTrailingNewline_isStrippedAndAccepted() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("2001:db8::1\n");

        // when
        Optional<InetAddress> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertInstanceOf(Inet6Address.class, result.get());
    }

    @Test
    void discover_firstServiceSucceeds_doesNotQueryFurtherServices() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("2001:db8::1");

        // when
        this.discoveryService.discover();

        // then
        verify(this.client, never()).target(PublicIpv6DiscoveryServiceImpl.DISCOVERY_URLS.get(1));
    }

    @Test
    void parseIpv6Address_rejectsIpv4Address() {
        // given / when / then
        assertTrue(PublicIpv6DiscoveryServiceImpl.parseIpv6Address("1.2.3.4").isEmpty());
    }

    @Test
    void parseIpv6Address_rejectsHostname() {
        // given / when / then
        assertTrue(
                PublicIpv6DiscoveryServiceImpl.parseIpv6Address("example.com").isEmpty());
    }

    @Test
    void parseIpv6Address_rejectsBracketedAddress() {
        // given / when / then
        assertTrue(
                PublicIpv6DiscoveryServiceImpl.parseIpv6Address("[2001:db8::1]").isEmpty());
    }

    @Test
    void parseIpv6Address_rejectsNull() {
        // given / when / then
        assertTrue(PublicIpv6DiscoveryServiceImpl.parseIpv6Address(null).isEmpty());
    }

    @Test
    void parseIpv6Address_rejectsBlank() {
        // given / when / then
        assertTrue(PublicIpv6DiscoveryServiceImpl.parseIpv6Address("  ").isEmpty());
    }

    @Test
    void parseIpv6Address_acceptsFullAddress() {
        // given / when / then
        assertTrue(PublicIpv6DiscoveryServiceImpl.parseIpv6Address("2001:db8:0:0:0:0:0:1")
                .isPresent());
    }

    @Test
    void parseIpv6Address_acceptsCompressedAddress() {
        // given / when / then
        // Both compressed ("2001:db8::1") and expanded ("2001:db8:0:0:0:0:0:1") forms are valid.
        // The validation does not require the input to survive a JVM normalisation round-trip.
        assertTrue(
                PublicIpv6DiscoveryServiceImpl.parseIpv6Address("2001:db8::1").isPresent());
    }
}
