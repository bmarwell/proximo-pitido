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
 * Rioplatense Spanish (es-AR) language plug-in for Próximo Pitido.
 *
 * <p>This module provides the Buenos Aires / River Plate colloquial speaking-clock announcement:
 * <em>"Al próximo pitido van a ser las catorce horas y treinta minutos con veinte segundos."</em>
 *
 * <p>Discovered at runtime via CDI ({@code Instance<LanguageFactory>} in the WAR module).
 * The package is opened so that Liberty's CDI container can discover and instantiate the
 * {@code @Dependent} bean via reflection.
 */
module de.bmarwell.proximo.pitido.languages.es.ar {
    // spi is transitive, so the api module is available without a separate requires
    requires de.bmarwell.proximo.pitido.spi;

    // CDI 2.0 — @Dependent, @Named
    requires jakarta.enterprise.cdi.api;
    requires javax.inject;

    // Open for Liberty CDI runtime reflection (bean discovery and instantiation).
    // Unqualified — Liberty's CDI implementation is in the unnamed module.
    opens de.bmarwell.proximo.pitido.languages.es.ar;
}
