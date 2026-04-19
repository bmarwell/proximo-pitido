/**
 * RTP codec descriptors and encoders for SIP media negotiation.
 *
 * <p>This module contains codec-level SIP media concerns that are independent of
 * any specific SIP servlet container lifecycle.
 */
module de.bmarwell.proximo.pitido.codecs.sip {
    // CDI 2.0 (javax namespace) — provided by the Liberty container at runtime.
    // Transitive because @ApplicationScoped is a runtime-visible annotation on exported types;
    // consumers seeing those types must be able to read the annotation class.
    requires transitive jakarta.enterprise.cdi.api;
    requires java.annotation; // javax.annotation.PostConstruct

    exports de.bmarwell.proximo.pitido.codecs.sip;

    // Open CDI bean packages for runtime reflection by the Liberty CDI container.
    // Unqualified because Liberty's CDI implementation runs in the unnamed module
    // (OSGi classloader) and a qualified "opens … to" cannot name it.
    opens de.bmarwell.proximo.pitido.codecs.sip;
}
