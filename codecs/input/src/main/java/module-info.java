/**
 * Input codec and decoding pipeline for Próximo Pitido.
 *
 * <p>This module contains reusable audio decoding and channel mixing logic.
 * It has no SIP servlet dependency and can be reused in CLI tools, tests, or
 * any non-container runtime.
 *
 * <h2>Native access</h2>
 * {@link de.bmarwell.proximo.pitido.codecs.input.OggOpusPcmDecoder} uses the Foreign
 * Function and Memory (FFM) API ({@code java.lang.foreign}) to call {@code libopus.so.0}
 * directly for Opus decoding.
 * {@link de.bmarwell.proximo.pitido.codecs.input.LibsoxrChannelMixer} uses the FFM API
 * to probe for {@code libsoxr.so.0} at startup.
 * At runtime the JVM must be started with
 * {@code --enable-native-access=de.bmarwell.proximo.pitido.codecs.input}.
 */
module de.bmarwell.proximo.pitido.codecs.input {
    // CDI 2.0 (javax namespace) — provided by the Liberty container at runtime.
    // Transitive because @ApplicationScoped is a runtime-visible annotation on exported types;
    // consumers seeing those types must be able to read the annotation class.
    requires transitive jakarta.enterprise.cdi.api;
    requires java.annotation; // javax.annotation.PostConstruct / @PreDestroy
    requires javax.inject; // @Inject

    // JDK platform modules
    requires java.desktop; // javax.sound.sampled.* — WAV decoding

    // Third-party compile dependencies
    // Transitive because MediaType appears in the exported PcmDecoder.supports() signature.
    requires transitive org.apache.tika.core;

    // MicroProfile Config — provided by Liberty; annotation-only use (@ConfigProperty)
    requires static microprofile.config.api;

    exports de.bmarwell.proximo.pitido.codecs.input;

    // Open CDI bean packages for runtime reflection by the Liberty CDI container.
    // Unqualified because Liberty's CDI implementation runs in the unnamed module
    // (OSGi classloader) and a qualified "opens … to" cannot name it.
    opens de.bmarwell.proximo.pitido.codecs.input;
}
