/**
 * Provider-agnostic core logic for Próximo Pitido.
 *
 * <p>This module contains audio decoding, channel mixing, SIP digest authentication,
 * SRV DNS resolution, and local host detection.
 * It has no dependency on the SIP servlet container; only pure-Java and CDI APIs are used.
 *
 * <p>{@link de.bmarwell.proximo.pitido.core.LanguageSelector} exposes
 * {@link de.bmarwell.proximo.pitido.spi.LanguageFactory} in its public signatures,
 * so the SPI module is re-exported transitively.
 *
 * <h2>Native access</h2>
 * {@link de.bmarwell.proximo.pitido.core.media.OggOpusPcmDecoder} uses the Foreign
 * Function and Memory (FFM) API ({@code java.lang.foreign}) to call {@code libopus.so.0}
 * directly for Opus decoding.
 * {@link de.bmarwell.proximo.pitido.core.media.LibsoxrChannelMixer} uses the FFM API
 * to probe for {@code libsoxr.so.0} at startup.
 * At runtime the JVM must be started with
 * {@code --enable-native-access=de.bmarwell.proximo.pitido.core}
 * (or {@code ALL-UNNAMED} when the module system is bypassed by the container).
 * In Liberty this is configured in {@code war/src/main/liberty/config/jvm.options}.
 */
module de.bmarwell.proximo.pitido.core {
    // Internal project modules
    requires transitive de.bmarwell.proximo.pitido.spi;

    // CDI 2.0 (javax namespace) — provided by the Liberty container at runtime.
    // Transitive because @ApplicationScoped is a runtime-visible annotation on exported types;
    // consumers seeing those types must be able to read the annotation class.
    requires transitive jakarta.enterprise.cdi.api;
    requires java.annotation; // javax.annotation.PostConstruct / @PreDestroy
    requires javax.inject; // @Inject

    // JDK platform modules
    requires java.naming; // javax.naming.* — JNDI SRV DNS lookup (package-private use only)
    requires java.desktop; // javax.sound.sampled.* — WAV decoding

    // Third-party compile dependencies
    // Transitive because MediaType appears in the exported PcmDecoder.supports() signature.
    requires transitive org.apache.tika.core;

    // MicroProfile Config — provided by Liberty; annotation-only use (@ConfigProperty)
    requires static microprofile.config.api;

    // Exported packages — used by the WAR and language modules
    exports de.bmarwell.proximo.pitido.core;
    exports de.bmarwell.proximo.pitido.core.media;
    exports de.bmarwell.proximo.pitido.core.sip;

    // Open CDI bean packages for runtime reflection by the Liberty CDI container.
    // Unqualified because Liberty's CDI implementation runs in the unnamed module
    // (OSGi classloader) and a qualified "opens … to" cannot name it.
    opens de.bmarwell.proximo.pitido.core.media;
    opens de.bmarwell.proximo.pitido.core.sip;
}
