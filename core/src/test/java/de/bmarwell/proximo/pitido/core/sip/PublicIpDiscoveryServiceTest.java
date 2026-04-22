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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class PublicIpDiscoveryServiceTest {

    @Mock
    Client client;

    @Mock
    WebTarget webTarget;

    @Mock
    Invocation.Builder requestBuilder;

    PublicIpDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        this.discoveryService = new PublicIpDiscoveryService();
        this.discoveryService.clientFactory = () -> this.client;
    }

    /** Stubs the JAX-RS fluent chain for tests that actually invoke {@link PublicIpDiscoveryService#discover()}. */
    private void wireClientChain() {
        when(this.client.target(any(URI.class))).thenReturn(this.webTarget);
        when(this.webTarget.request(any(MediaType.class))).thenReturn(this.requestBuilder);
    }

    @Test
    void discover_firstServiceRespondsWithValidIp_returnsThatIp() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("1.2.3.4");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("1.2.3.4", result.get());
        verify(this.client, times(1)).target(PublicIpDiscoveryService.DISCOVERY_URLS.getFirst());
    }

    @Test
    void discover_firstServiceFails_triesNextAndReturnsItsIp() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class))
                .thenThrow(new ProcessingException("connection refused"))
                .thenReturn("5.6.7.8");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("5.6.7.8", result.get());
    }

    @Test
    void discover_allServicesFail_returnsEmpty() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenThrow(new ProcessingException("no route to host"));

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertFalse(result.isPresent());
    }

    @Test
    void discover_serviceReturnsInvalidBody_skipsToNextService() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class))
                .thenReturn("not-an-ip-address")
                .thenReturn("9.10.11.12");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("9.10.11.12", result.get());
    }

    @Test
    void discover_secondCallWithinCacheWindow_doesNotQueryRemoteServices() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("1.2.3.4");
        this.discoveryService.discover();

        // when
        Optional<String> cached = this.discoveryService.discover();

        // then
        assertTrue(cached.isPresent());
        assertEquals("1.2.3.4", cached.get());
        verify(this.client, times(1)).target(any(URI.class));
    }

    @Test
    void discover_responseWithTrailingNewline_isStrippedAndAccepted() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("203.0.113.1\n");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("203.0.113.1", result.get());
    }

    @Test
    void discover_firstServiceSucceeds_doesNotQueryFurtherServices() {
        // given
        wireClientChain();
        when(this.requestBuilder.get(String.class)).thenReturn("1.2.3.4");

        // when
        this.discoveryService.discover();

        // then
        verify(this.client, never()).target(PublicIpDiscoveryService.DISCOVERY_URLS.get(1));
        verify(this.client, never()).target(PublicIpDiscoveryService.DISCOVERY_URLS.get(2));
    }

    @Test
    void isValidPublicIpv4Address_rejectsIpv6Address() {
        // given / when / then
        assertFalse(PublicIpDiscoveryService.isValidPublicIpv4Address("2001:db8::1"));
    }

    @Test
    void isValidPublicIpv4Address_rejectsHostname() {
        // given / when / then
        assertFalse(PublicIpDiscoveryService.isValidPublicIpv4Address("example.com"));
    }

    @Test
    void isValidPublicIpv4Address_acceptsLiteralIpv4() {
        // given / when / then
        assertTrue(PublicIpDiscoveryService.isValidPublicIpv4Address("203.0.113.1"));
    }
}
