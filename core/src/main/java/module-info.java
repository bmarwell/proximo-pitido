/**
 * Provider-agnostic core logic for Próximo Pitido.
 *
 * <p>This module contains provider-agnostic SIP support logic such as digest authentication,
 * SRV DNS resolution, local host detection, and language selection utilities.
 * It has no dependency on the SIP servlet container; only pure-Java and CDI APIs are used.
 *
 * <p>{@link de.bmarwell.proximo.pitido.core.LanguageSelector} exposes
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} in its public signatures,
 * so the SPI module is re-exported transitively.
 *
 * <p>Codec and decoding implementations live in dedicated modules:
 * {@code de.bmarwell.proximo.pitido.codecs.input} and
 * {@code de.bmarwell.proximo.pitido.codecs.sip}.
 */
module de.bmarwell.proximo.pitido.core {
    // Internal project modules
    requires transitive de.bmarwell.proximo.pitido.spi;
    requires de.bmarwell.proximo.pitido.services.api;

    // CDI 2.0 (javax namespace) — provided by the Liberty container at runtime.
    // Transitive because @ApplicationScoped is a runtime-visible annotation on exported types;
    // consumers seeing those types must be able to read the annotation class.
    requires transitive jakarta.enterprise.cdi.api;
    requires java.annotation; // javax.annotation.PostConstruct / @PreDestroy
    requires javax.inject; // @Inject

    // JDK platform modules
    requires java.naming; // javax.naming.* — JNDI SRV DNS lookup (package-private use only)

    // JAX-RS 2.1 client API — provided at runtime by Liberty's jaxrsClient feature.
    // javax.ws.rs-api declares "requires transitive java.xml.bind"; jaxb-api on the
    // module path satisfies that at compile time; Liberty's jaxb feature covers runtime.
    requires java.ws.rs;

    // MicroProfile Config — provided by Liberty; annotation-only use (@ConfigProperty)
    requires static microprofile.config.api;

    // Exported packages — used by the WAR and language modules
    exports de.bmarwell.proximo.pitido.core;
    exports de.bmarwell.proximo.pitido.core.sip;

    // Open CDI bean packages for runtime reflection by the Liberty CDI container.
    // Unqualified because Liberty's CDI implementation runs in the unnamed module
    // (OSGi classloader) and a qualified "opens … to" cannot name it.
    opens de.bmarwell.proximo.pitido.core.sip;
}
