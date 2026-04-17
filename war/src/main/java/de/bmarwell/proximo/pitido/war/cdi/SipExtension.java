package de.bmarwell.proximo.pitido.war.cdi;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.sip.SipFactory;

/// By default, SipFactory is not injectable.
/// Let's make it injectable using the InitialContext reference defined in `web.xml`.
public class SipExtension implements Extension {

    public static final String IC_SIP_FACTORY = "java:comp/env/sip/SipFactory";

    void afterBeanDiscovery(@Observes AfterBeanDiscovery event, BeanManager beanManager) {
        event.addBean()
            .types(SipFactory.class)
            .scope(ApplicationScoped.class)
            .beanClass(SipExtension.class)
            .<SipFactory>createWith(_ -> getSipFactoryFromInitialContext());
    }

    private static SipFactory getSipFactoryFromInitialContext() {
        try {
            System.err.println("Looking up in " + IC_SIP_FACTORY);
            InitialContext initialContext = new InitialContext();
            return (SipFactory) initialContext.lookup(IC_SIP_FACTORY);
        } catch (NamingException e) {
            System.err.println("Unable to lookup SipFactory in " + IC_SIP_FACTORY);
            throw new IllegalStateException("SipFactory not available in InitialContext, did you enable the Liberty Feature?", e);
        }
    }

}
