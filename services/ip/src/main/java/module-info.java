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

/**
 * IP-discovery service implementations for Próximo Pitido.
 *
 * <p>This module provides CDI beans that implement the interfaces declared in
 * {@code de.bmarwell.proximo.pitido.services.api}.
 * It must appear on the WAR's runtime classpath so that the CDI container
 * can discover and inject the implementations.
 */
module de.bmarwell.proximo.pitido.services.ip {
    requires de.bmarwell.proximo.pitido.services.api;

    // CDI 2.0 — provided by Liberty at runtime
    requires transitive jakarta.enterprise.cdi.api;
    requires java.annotation;
    requires javax.inject;

    // JAX-RS 2.1 client — provided by Liberty's jaxrsClient feature
    requires java.ws.rs;

    // Open for CDI reflection (Liberty CDI runs in OSGi; unqualified opens required)
    opens de.bmarwell.proximo.pitido.services.ip;
}
