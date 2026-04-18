/**
 * German (de-DE) language plug-in for Próximo Pitido.
 *
 * <p>This module provides the German time announcement and language-selection
 * announcement, discovered at runtime via CDI
 * ({@code Instance<LanguageFactory>} in the WAR module).
 *
 * <p>The package is not exported — callers interact only through the
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} interface.
 * The package is opened so that Liberty's CDI container can discover and
 * instantiate the {@code @Dependent} bean via reflection.
 */
module de.bmarwell.proximo.pitido.languages.de.de {
    // spi is transitive, so the api module is available without a separate requires
    requires de.bmarwell.proximo.pitido.spi;

    // CDI 2.0 — @Dependent, @Named
    requires jakarta.enterprise.cdi.api;
    requires javax.inject;

    // Open for Liberty CDI runtime reflection (bean discovery and instantiation).
    // Unqualified — Liberty's CDI implementation is in the unnamed module.
    opens de.bmarwell.proximo.pitido.languages.de.de;
}
