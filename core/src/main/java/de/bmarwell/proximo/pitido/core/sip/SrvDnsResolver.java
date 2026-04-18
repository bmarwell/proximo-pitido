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
package de.bmarwell.proximo.pitido.core.sip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import javax.enterprise.context.ApplicationScoped;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/**
 * Resolves the real SIP server hostname for a SIP domain by performing a DNS SRV lookup
 * for {@code _sip._tcp.{domain}} (RFC 3263).
 *
 * <p>Liberty's SIP stack performs plain A-record lookups and cannot resolve SIP domain names
 * that only publish SRV records (e.g. {@code tel.t-online.de}). This resolver finds the
 * highest-priority SRV target so the Request-URI points to a real IP address.
 *
 * <p>Results are cached after the first successful lookup.
 */
@ApplicationScoped
public class SrvDnsResolver {

    private static final System.Logger LOGGER = System.getLogger(SrvDnsResolver.class.getName());

    private volatile String cachedHost;

    /**
     * Returns the SIP server hostname for the given domain, using a cached result after the first call.
     * Falls back to the domain itself if the SRV lookup fails.
     */
    public String resolve(String sipDomain) {
        if (cachedHost == null) {
            cachedHost = resolveFresh(sipDomain);
        }
        return cachedHost;
    }

    private String resolveFresh(String sipDomain) {
        try {
            var srvValues = querySrvValues(sipDomain);
            return findBestTarget(srvValues, sipDomain).orElse(sipDomain);
        } catch (NamingException ex) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "SRV lookup failed for [{0}], falling back to domain: {1}",
                    sipDomain,
                    ex.getMessage());
            return sipDomain;
        }
    }

    /**
     * Queries DNS SRV records and returns the raw values as a plain list.
     * Isolating JNDI types in this method keeps the rest of the class analyzable by static tools.
     */
    private List<String> querySrvValues(String sipDomain) throws NamingException {
        Attributes attrs = createDirContext().getAttributes("_sip._tcp." + sipDomain, new String[] {"SRV"});
        var srvAttr = attrs.get("SRV");
        if (srvAttr == null) {
            return List.of();
        }
        var result = new ArrayList<String>(srvAttr.size());
        for (int i = 0; i < srvAttr.size(); i++) {
            result.add((String) srvAttr.get(i));
        }
        return result;
    }

    /** Overridable for testing — allows injecting a mock {@link DirContext}. */
    protected DirContext createDirContext() throws NamingException {
        var env = new Hashtable<String, String>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns:");
        return new InitialDirContext(env);
    }

    private Optional<String> findBestTarget(List<String> srvValues, String sipDomain) {
        return srvValues.stream()
                .map(this::parseSrvRecord)
                .min(Comparator.comparingInt(SrvRecord::priority))
                .map(record -> logAndReturn(record, sipDomain));
    }

    private String logAndReturn(SrvRecord record, String sipDomain) {
        LOGGER.log(
                System.Logger.Level.INFO,
                "SRV lookup for [{0}] resolved to [{1}] (priority {2}, tcp)",
                sipDomain,
                record.host(),
                record.priority());
        return record.host();
    }

    /** Parses a single SRV record value in {@code "priority weight port target"} format. */
    private SrvRecord parseSrvRecord(String srvValue) {
        String[] parts = srvValue.split(" ");
        int priority = Integer.parseInt(parts[0]);
        String host = parts[3];
        String normalizedHost = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        return new SrvRecord(priority, normalizedHost);
    }

    private record SrvRecord(int priority, String host) {}
}
