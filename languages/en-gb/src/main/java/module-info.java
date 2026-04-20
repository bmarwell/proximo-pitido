/**
 * British English (en-GB) language plug-in for Próximo Pitido.
 *
 * <p>This module provides the British-English time announcement in the traditional
 * BT speaking-clock style: <em>"At the third stroke, the time from Próximo Pitido
 * will be …"</em>, followed by three strokes, with the third stroke sounding at the
 * exact announced second.
 *
 * <p>Discovered at runtime via CDI ({@code Instance<LanguageFactory>} in the WAR module).
 * The package is not exported — callers interact only through the
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} interface.
 * The package is opened so that Liberty's CDI container can discover and
 * instantiate the {@code @Dependent} bean via reflection.
 */
module de.bmarwell.proximo.pitido.languages.en.gb {
    // spi is transitive, so the api module is available without a separate requires
    requires de.bmarwell.proximo.pitido.spi;

    // CDI 2.0 — @Dependent, @Named
    requires jakarta.enterprise.cdi.api;
    requires javax.inject;

    // Open for Liberty CDI runtime reflection (bean discovery and instantiation).
    // Unqualified — Liberty's CDI implementation is in the unnamed module.
    opens de.bmarwell.proximo.pitido.languages.en.gb;
}
