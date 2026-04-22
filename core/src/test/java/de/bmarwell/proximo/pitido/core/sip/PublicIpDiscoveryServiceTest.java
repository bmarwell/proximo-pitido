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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicIpDiscoveryServiceTest {

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<String> httpResponse;

    @InjectMocks
    PublicIpDiscoveryService discoveryService;

    @SuppressWarnings("unchecked")
    private void givenResponse(String body) throws IOException, InterruptedException {
        when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(this.httpResponse);
        when(this.httpResponse.body()).thenReturn(body);
    }

    @Test
    void discover_firstServiceRespondsWithValidIp_returnsThatIp() throws Exception {
        // given
        givenResponse("1.2.3.4");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("1.2.3.4", result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void discover_firstServiceFails_triesNextAndReturnsItsIp() throws Exception {
        // given
        when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"))
                .thenReturn(this.httpResponse);
        when(this.httpResponse.body()).thenReturn("5.6.7.8");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("5.6.7.8", result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void discover_allServicesFail_returnsEmpty() throws Exception {
        // given
        when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("no route to host"));

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertFalse(result.isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void discover_serviceReturnsInvalidBody_skipsToNextService() throws Exception {
        // given
        HttpResponse<String> secondResponse = org.mockito.Mockito.mock(HttpResponse.class);
        when(this.httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(this.httpResponse)
                .thenReturn(secondResponse);
        when(this.httpResponse.body()).thenReturn("not-an-ip-address");
        when(secondResponse.body()).thenReturn("9.10.11.12");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("9.10.11.12", result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void discover_secondCallWithinCacheWindow_doesNotQueryRemoteServices() throws Exception {
        // given
        givenResponse("1.2.3.4");
        this.discoveryService.discover();

        // when
        Optional<String> cached = this.discoveryService.discover();

        // then
        assertTrue(cached.isPresent());
        assertEquals("1.2.3.4", cached.get());
        verify(this.httpClient, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void discover_responseWithTrailingNewline_isStrippedAndAccepted() throws Exception {
        // given
        givenResponse("203.0.113.1\n");

        // when
        Optional<String> result = this.discoveryService.discover();

        // then
        assertTrue(result.isPresent());
        assertEquals("203.0.113.1", result.get());
    }
}
