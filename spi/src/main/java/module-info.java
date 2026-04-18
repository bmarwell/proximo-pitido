/**
 * Service Provider Interface for Próximo Pitido language plug-ins.
 *
 * <p>Language implementations depend on this module.
 * Because {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} and
 * {@link de.bmarwell.proximo.pitido.spi.AbstractTimeAnnouncement} directly expose types
 * from the API module in their signatures, the API is re-exported transitively so that
 * consumers of the SPI do not need to declare a separate dependency on the API module.
 */
module de.bmarwell.proximo.pitido.spi {
    requires transitive de.bmarwell.proximo.pitido.api;

    exports de.bmarwell.proximo.pitido.spi;
}
