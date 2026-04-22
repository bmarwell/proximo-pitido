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
package de.bmarwell.proximo.pitido.war.listener;

import de.bmarwell.proximo.pitido.core.sip.DigestMd5Computer;
import de.bmarwell.proximo.pitido.core.sip.LocalSipHostProvider;
import de.bmarwell.proximo.pitido.core.sip.SipDigestChallenge;
import de.bmarwell.proximo.pitido.core.sip.SrvDnsResolver;
import java.io.IOException;
import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.sip.Address;
import javax.servlet.sip.ServletParseException;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipURI;
import javax.servlet.sip.URI;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Orchestrates SIP REGISTER with the configured registrar using RFC 3261 Digest MD5 authentication.
 *
 * <p>State machine:
 * <ul>
 *   <li>0 = IDLE — ready to start a new registration cycle</li>
 *   <li>1 = AUTH_IN_PROGRESS — authenticated REGISTER has been sent</li>
 *   <li>2 = REGISTERED — 200 OK received; all further challenges are ignored</li>
 * </ul>
 *
 * <p>Works with any RFC 3261-compliant SIP registrar (Deutsche Telekom, SIPGate, easybell, O2,
 * Vodafone DSL, etc.). Configure via {@code server.env}; see README for env-var mapping.
 */
@ApplicationScoped
public class SipRegistrationListener {

    private static final System.Logger LOGGER = System.getLogger(SipRegistrationListener.class.getName());
    private static final int INITIAL_REGISTER_DELAY_MILLIS = 2_000;
    private static final int STARTUP_RETRY_DELAY_MILLIS = 2_000;

    /**
     * Fraction of the configured {@code expires} interval at which the registration is renewed.
     * Two thirds of {@code expires} leaves a comfortable margin before the registrar removes the binding.
     */
    private static final double RE_REGISTRATION_FACTOR = 2.0 / 3.0;

    private final AtomicInteger regState = new AtomicInteger(0);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Allows exactly one stale-nonce retry per session. Some providers respond with
     * {@code stale=true} on the first auth attempt when a prior session consumed the nonce.
     * A second {@code stale=true} indicates something more fundamental is wrong; we give up.
     */
    private final AtomicBoolean staleRetryUsed = new AtomicBoolean(false);

    /**
     * When {@code false}, the application skips SIP REGISTER entirely and accepts direct calls
     * from softphones on the local network.
     * All SIP credentials become optional when registration is disabled.
     * Maps to {@code SIP_REGISTRATION_ENABLED}.
     */
    @Inject
    @ConfigProperty(name = "sip.registration.enabled", defaultValue = "true")
    boolean registrationEnabled;

    /** SIP domain of the registrar, e.g. {@code tel.t-online.de} or {@code sip.sipgate.de}. Maps to {@code SIP_REGISTRAR}. */
    @Inject
    @ConfigProperty(name = "sip.registrar", defaultValue = "")
    String registrar;

    /** SIP subscriber ID / phone number used to build the SIP URI. Maps to {@code SIP_SIPID}. */
    @Inject
    @ConfigProperty(name = "sip.sipid", defaultValue = "")
    String sipId;

    /** Authentication username (often an e-mail address). Maps to {@code SIP_USER_ID}. */
    @Inject
    @ConfigProperty(name = "sip.user.id", defaultValue = "")
    String loginUserId;

    /** Authentication password. Maps to {@code SIP_USER_PASSWORD}. */
    @Inject
    @ConfigProperty(name = "sip.user.password", defaultValue = "")
    String loginPassword;

    @Inject
    @ConfigProperty(name = "sip.registration.expires", defaultValue = "3600")
    int expires = 3600;

    @Inject
    SrvDnsResolver srvDnsResolver;

    @Inject
    DigestMd5Computer digestComputer;

    @Inject
    LocalSipHostProvider localSipHostProvider;

    @Resource(lookup = "concurrent/scheduler")
    ManagedScheduledExecutorService managedScheduledExecutorService;

    /**
     * The servlet context, supplied by {@link de.bmarwell.proximo.pitido.war.SipTimeServlet#init}
     * when Liberty's SIP stack calls {@code init()}.
     * Declared {@code volatile} because it is written on Liberty's servlet init thread and read on a
     * virtual thread in {@link #scheduleRegistration(ServletContext)}.
     */
    private volatile ServletContext servletContext;

    private volatile ScheduledFuture<?> startupRegistrationTask;
    private volatile ScheduledFuture<?> reRegistrationTask;

    /**
     * Schedules the initial REGISTER via the container's managed scheduler.
     * The {@value #INITIAL_REGISTER_DELAY_MILLIS} ms delay lets Liberty finish initialising the SIP
     * application router before the first request goes out.
     *
     * <p>Called from {@link de.bmarwell.proximo.pitido.war.SipTimeServlet#init} rather than from a
     * CDI {@code @Initialized} observer.
     * The CDI {@code @Initialized(ApplicationScoped.class)} event fires before Liberty sets
     * {@code SipFactory} on the {@link ServletContext}; by the time {@code SipServlet.init()} is
     * called, the SIP stack is further along and the factory becomes available sooner.
     *
     * <p>The short delay is still required because the Liberty SIP application router may finish
     * its own initialisation concurrently with servlet init.
     * {@link #registerWithStartupRetry()} retries indefinitely, so the thread will recover even if
     * the factory is not available immediately after the delay.
     */
    public void scheduleRegistration(ServletContext sc) {
        if (!this.registrationEnabled) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "SIP registration disabled (sip.registration.enabled=false) — "
                            + "listening for direct calls only");
            return;
        }

        this.shuttingDown.set(false);
        this.servletContext = sc;
        this.startupRegistrationTask = this.managedScheduledExecutorService.schedule(
                this::registerWithStartupRetry, INITIAL_REGISTER_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Sends the initial (unauthenticated) REGISTER request. The registrar will respond with
     * {@code 401 Unauthorized}; the Digest challenge is handled in {@link #registerWithAuth}.
     */
    public void register() {
        SipFactory sipFactory = resolveSipFactory();

        if (sipFactory == null) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Cannot send initial REGISTER yet: ServletContext or SipFactory is not ready");
            return;
        }

        sendInitialRegister(sipFactory);
    }

    /**
     * Retries {@link #resolveSipFactory()} in a loop until the factory becomes available,
     * then sends the initial REGISTER.
     *
     * <p>The Liberty SIP stack may take considerably longer to initialise than CDI on loaded
     * systems.
     * Rather than aborting after a fixed number of attempts, this method retries indefinitely
     * until the SipFactory is available or the virtual thread is interrupted.
     * The virtual thread is a daemon thread, so it does not prevent JVM shutdown.
     *
     * <p>Logging:
     * <ul>
     *   <li>Attempt 1: INFO — SipFactory not yet ready.</li>
     *   <li>Attempts 2–N: DEBUG — suppressed to avoid log spam.</li>
     *   <li>Every tenth attempt: WARNING — Liberty SIP stack may be unusually slow.</li>
     * </ul>
     */
    private void registerWithStartupRetry() {
        for (int attempt = 1; !Thread.currentThread().isInterrupted(); attempt++) {
            if (this.shuttingDown.get()) {
                return;
            }

            if (regState.get() == 2) {
                LOGGER.log(System.Logger.Level.DEBUG, "Skipping REGISTER retry loop — state already REGISTERED");
                return;
            }

            SipFactory sipFactory = resolveSipFactory();

            if (sipFactory != null) {
                sendInitialRegister(sipFactory);
                return;
            }

            logSipFactoryNotReady(attempt);

            try {
                Thread.sleep(STARTUP_RETRY_DELAY_MILLIS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void logSipFactoryNotReady(int attempt) {
        if (attempt == 1) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "SipFactory not ready — waiting for Liberty SIP stack to initialise (retry interval: {0}ms)",
                    STARTUP_RETRY_DELAY_MILLIS);
            return;
        }

        if (attempt % 10 == 0) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "SipFactory still not available after {0} attempts — Liberty SIP stack may be slow to start",
                    attempt);
            return;
        }

        LOGGER.log(System.Logger.Level.DEBUG, "SipFactory not ready yet (attempt {0})", attempt);
    }

    private SipFactory resolveSipFactory() {
        if (this.servletContext == null) {
            return null;
        }

        Object sipFactoryAttribute = this.servletContext.getAttribute(SipFactory.class.getName());

        if (!(sipFactoryAttribute instanceof SipFactory sipFactory)) {
            return null;
        }

        return sipFactory;
    }

    private void sendInitialRegister(SipFactory sipFactory) {
        LOGGER.log(System.Logger.Level.INFO, "Sending initial REGISTER for sip:[{0}@{1}]", this.sipId, this.registrar);

        var applicationSession = sipFactory.createApplicationSession();
        Objects.requireNonNull(applicationSession.getApplicationName(), "applicationName must not be null");
        var fromUri = sipFactory.createSipURI(this.sipId, this.registrar);

        try {
            var requestURI = buildRequestUri(sipFactory);
            var registerRequest = sipFactory.createRequest(applicationSession, "REGISTER", fromUri, fromUri);
            registerRequest.setExpires(this.expires);
            registerRequest.setRequestURI(requestURI);
            registerRequest.setAddressHeader("Contact", buildContactAddress(sipFactory));

            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "REGISTER request: session=[{0}] fromTo=[{1}] requestUri=[{2}]",
                    applicationSession,
                    fromUri,
                    requestURI);
            registerRequest.send();
        } catch (ServletParseException | IOException sipEx) {
            LOGGER.log(System.Logger.Level.ERROR, "Initial REGISTER failed", sipEx);
        }
    }

    /**
     * Re-sends REGISTER with Digest credentials in response to a {@code 401} or {@code 407} challenge.
     *
     * <p>Uses {@code sipFactory.createRequest(origRequest, true)} to preserve the original Call-ID —
     * required by RFC 3261 §10.2. A new Call-ID causes some registrars to return {@code stale=true}
     * indefinitely.
     *
     * <p>Computes the Digest hash manually because Liberty's {@code addAuthHeader()} uses the full
     * Request-URI (SRV-resolved hostname) as the Digest URI, but the correct URI is
     * {@code sip:<registrar-domain>} (the unauthenticated REGISTER target).
     *
     * <p>Protected by {@link #regState} so only one auth attempt is in flight at a time.
     */
    public void registerWithAuth(SipServletResponse challenge, SipFactory sipFactory) {
        if (!regState.compareAndSet(0, 1)) {
            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Auth REGISTER already in flight or registered (state={0}), ignoring [{1}] challenge",
                    regState.get(),
                    challenge.getStatus());
            return;
        }

        LOGGER.log(
                System.Logger.Level.INFO,
                "Handling [{0}] challenge, re-registering with credentials",
                challenge.getStatus());

        try {
            var origRequest = challenge.getRequest();
            var registerRequest = sipFactory.createRequest(origRequest, true);
            registerRequest.setHeader("Authorization", buildAuthHeader(challenge));
            registerRequest.setExpires(this.expires);
            registerRequest.setRequestURI(buildRequestUri(sipFactory));
            registerRequest.setAddressHeader("Contact", buildContactAddress(sipFactory));

            LOGGER.log(
                    System.Logger.Level.DEBUG,
                    "Sending authenticated REGISTER (Digest MD5, uri=sip:{0})",
                    this.registrar);
            registerRequest.send();
        } catch (ServletParseException | IOException sipEx) {
            regState.set(0);
            LOGGER.log(System.Logger.Level.ERROR, "Authenticated REGISTER failed", sipEx);
        }
    }

    /**
     * Resolves the registrar-granted expiry interval from a REGISTER 200 OK.
     *
     * <p>RFC 3261 §10.3 requires registrars to return the effective binding expiry in Contact
     * header field values using the {@code expires} parameter.
     * Some providers omit the top-level {@code Expires} header in 200 OK responses, so relying on
     * {@link SipServletResponse#getExpires()} alone can schedule re-registration far too late.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Prefer a positive Contact expires value whose URI user equals this listener's SIP ID.</li>
     *   <li>Otherwise use the shortest positive Contact expires value.</li>
     *   <li>Fallback to {@link SipServletResponse#getExpires()} (may be {@code -1}).</li>
     * </ol>
     *
     * @param response REGISTER 200 OK response
     * @return effective granted expiry in seconds, or {@code -1} when no expiry is present
     */
    public int resolveGrantedExpires(SipServletResponse response) {
        int fallbackExpires = response.getExpires();
        int contactExpires = resolveGrantedContactExpires(response);

        if (contactExpires > 0) {
            return contactExpires;
        }

        return fallbackExpires;
    }

    /**
     * Marks registration as successful (state = REGISTERED) and schedules renewal.
     *
     * @param grantedExpires the {@code Expires} value from the registrar's {@code 200 OK} response,
     *     or {@code -1} if the registrar did not include an {@code Expires} header (the configured
     *     {@code sip.registration.expires} value is used as the fallback).
     */
    public void markRegistered(int grantedExpires) {
        regState.set(2);
        int effectiveExpires = grantedExpires > 0 ? grantedExpires : this.expires;
        LOGGER.log(
                System.Logger.Level.INFO,
                "Registration state: REGISTERED (granted-expires={0}s, effective-expires={1}s)",
                grantedExpires,
                effectiveExpires);

        if (grantedExpires > 0 && grantedExpires < this.expires) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Registrar granted a shorter expiry ({0}s) than configured ({1}s) — re-registration will use the granted value",
                    grantedExpires,
                    this.expires);
        }

        scheduleReRegistration(effectiveExpires);
    }

    private int resolveGrantedContactExpires(SipServletResponse response) {
        ListIterator<Address> contactHeaders;

        try {
            contactHeaders = response.getAddressHeaders("Contact");
        } catch (ServletParseException servletParseException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not parse Contact headers in REGISTER response",
                    servletParseException);
            return -1;
        }

        if (contactHeaders == null) {
            return -1;
        }

        int shortestPositiveExpires = Integer.MAX_VALUE;

        while (contactHeaders.hasNext()) {
            Address contact = contactHeaders.next();
            int contactExpires = contact.getExpires();

            if (contactExpires <= 0) {
                continue;
            }

            if (contact.getURI() instanceof SipURI sipUri && this.sipId.equals(sipUri.getUser())) {
                return contactExpires;
            }

            if (contactExpires < shortestPositiveExpires) {
                shortestPositiveExpires = contactExpires;
            }
        }

        if (shortestPositiveExpires == Integer.MAX_VALUE) {
            return -1;
        }

        return shortestPositiveExpires;
    }

    /**
     * Schedules a re-registration at {@value #RE_REGISTRATION_FACTOR} of the effective expires
     * interval.
     * Skipped when {@link #servletContext} is not yet set (e.g. in unit tests or before servlet
     * init).
     *
     * @param effectiveExpires the expiry interval in seconds actually granted by the registrar
     */
    private void scheduleReRegistration(int effectiveExpires) {
        if (this.servletContext == null) {
            return;
        }

        long delaySeconds = (long) (effectiveExpires * RE_REGISTRATION_FACTOR);
        LOGGER.log(
                System.Logger.Level.INFO,
                "Re-registration scheduled in {0}s (effective-expires={1}s)",
                delaySeconds,
                effectiveExpires);
        cancelTask(this.reRegistrationTask);
        this.reRegistrationTask = this.managedScheduledExecutorService.schedule(
                () -> {
                    resetForReRegistration();
                    registerWithStartupRetry();
                },
                delaySeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Resets REGISTERED → IDLE so a fresh registration cycle can begin.
     * Also clears the stale-nonce guard so the new cycle can handle a stale challenge.
     * Package-private for use in tests.
     */
    void resetForReRegistration() {
        regState.set(0);
        staleRetryUsed.set(false);
        LOGGER.log(System.Logger.Level.INFO, "Registration state reset to IDLE for re-registration");
    }

    /**
     * Returns {@code true} when the registration state is IDLE (0).
     * Package-private for use in tests.
     */
    boolean isIdle() {
        return regState.get() == 0;
    }

    /**
     * Returns {@code true} when the registration state is REGISTERED (2).
     * Package-private for use in tests.
     */
    boolean isRegistered() {
        return regState.get() == 2;
    }

    /**
     * Returns {@code true} the first time a stale-nonce retry is requested; {@code false} on
     * subsequent calls. Prevents infinite retry loops when the registrar keeps returning stale.
     */
    public boolean canRetryAfterStale() {
        return staleRetryUsed.compareAndSet(false, true);
    }

    /**
     * Resets AUTH_IN_PROGRESS → IDLE so a fresh auth cycle can begin.
     * Uses CAS(1, 0) so that the REGISTERED (2) state is never accidentally cleared.
     */
    public void resetAuthState() {
        regState.compareAndSet(1, 0);
    }

    @PreDestroy
    void onShutdown() {
        this.shuttingDown.set(true);
        cancelTask(this.startupRegistrationTask);
        cancelTask(this.reRegistrationTask);
    }

    private URI buildRequestUri(SipFactory sipFactory) throws ServletParseException {
        String sipServer = srvDnsResolver.resolve(this.registrar);
        return sipFactory.createURI("sip:" + sipServer + ":5060;transport=tcp");
    }

    private Address buildContactAddress(SipFactory sipFactory) {
        return sipFactory.createAddress(sipFactory.createSipURI(this.sipId, localSipHostProvider.get()));
    }

    private String buildAuthHeader(SipServletResponse challengeResponse) {
        String wwwAuth = challengeResponse.getHeader("WWW-Authenticate");
        if (wwwAuth == null) {
            wwwAuth = challengeResponse.getHeader("Proxy-Authenticate");
        }
        if (wwwAuth == null) {
            throw new IllegalArgumentException("No WWW-Authenticate / Proxy-Authenticate in challenge");
        }
        LOGGER.log(System.Logger.Level.DEBUG, "WWW-Authenticate challenge: [{0}]", wwwAuth);
        var challenge = SipDigestChallenge.parse(wwwAuth);
        return digestComputer.buildAuthorizationHeader(
                this.loginUserId, this.loginPassword, challenge, "sip:" + this.registrar);
    }

    private static void cancelTask(ScheduledFuture<?> task) {
        if (task == null) {
            return;
        }

        task.cancel(true);
    }
}
