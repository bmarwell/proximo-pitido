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
package de.bmarwell.proximo.pitido.war;

import de.bmarwell.proximo.pitido.codecs.sip.AmrWbEncodeService;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;

/**
 * CDI producer that exposes a single application-scoped {@link AmrWbEncodeService}.
 *
 * <p>The service wraps {@code concurrent/codecExecutor}, a container-managed executor declared in
 * {@code server.xml}.
 * Because the AMR-WB native library ({@code libvo-amrwbenc.so.0}) is not thread-safe — it contains
 * approximately 120 KB of global mutable state — all {@code E_IF_encode} calls must be serialised
 * through a single executor thread.
 * Using the container-managed executor (rather than {@code Executors.newSingleThreadExecutor()})
 * satisfies the Liberty concurrent-feature contract and avoids the unmanaged-threading prohibition.
 */
@ApplicationScoped
public class AmrWbEncodeServiceProducer {

    @Resource(lookup = "concurrent/codecExecutor")
    ManagedExecutorService codecExecutor;

    /**
     * Produces the application-scoped {@link AmrWbEncodeService}.
     *
     * <p>The returned instance is backed by {@code concurrent/codecExecutor}.
     * Liberty guarantees that this executor uses a single thread when the executor configuration
     * does not declare a {@code maxConcurrency} higher than one; the pool is sized so that all
     * AMR-WB encode calls are serialised.
     *
     * @return a fully initialised {@link AmrWbEncodeService}
     */
    @Produces
    @ApplicationScoped
    public AmrWbEncodeService produceAmrWbEncodeService() {
        return new AmrWbEncodeService(this.codecExecutor);
    }

    /**
     * Disposes the {@link AmrWbEncodeService} when the application stops.
     *
     * <p>The underlying executor is container-managed and must not be shut down here;
     * Liberty handles its lifecycle.
     *
     * @param service the service instance being disposed (unused)
     */
    public void disposeAmrWbEncodeService(@Disposes AmrWbEncodeService service) {
        // no-op: codecExecutor is container-managed; Liberty shuts it down.
    }
}
