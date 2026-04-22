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

import de.bmarwell.proximo.pitido.api.AudioPlayer;
import de.bmarwell.proximo.pitido.codecs.input.PcmDecoderFactory;
import de.bmarwell.proximo.pitido.core.LanguageMenuConfig;
import de.bmarwell.proximo.pitido.core.LanguageSelector;
import de.bmarwell.proximo.pitido.spi.LanguageFactory;
import de.bmarwell.proximo.pitido.war.media.CallMedia;
import de.bmarwell.proximo.pitido.war.media.RtpAudioPlayer;
import de.bmarwell.proximo.pitido.war.media.SdpNegotiator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.sip.SipServletRequest;
import javax.servlet.sip.SipServletResponse;
import javax.servlet.sip.SipSession;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Accepts incoming SIP calls (INVITE), builds the language menu from discovered
 * {@link LanguageFactory} beans, and dispatches to:
 *
 * <ul>
 *   <li>the direct time-announcement path when exactly one language is available, or</li>
 *   <li>the language-selection menu when two or more languages are available.</li>
 * </ul>
 *
 * <p>Both paths send {@code 180 Ringing} immediately and perform the rest of the work on a
 * managed executor thread so the Liberty SIP thread is never blocked.
 */
@ApplicationScoped
public class CallAcceptor {

    private static final System.Logger LOGGER = System.getLogger(CallAcceptor.class.getName());

    @Inject
    Instance<LanguageFactory> languageFactories;

    @Inject
    SdpNegotiator sdpNegotiator;

    @Inject
    PcmDecoderFactory pcmDecoderFactory;

    @Inject
    SipCallBlacklist sipCallBlacklist;

    @Inject
    @ConfigProperty(name = "sip.languages.enabled", defaultValue = "")
    String enabledLanguagesConfig;

    @Inject
    CallSessionManager callSessionManager;

    @Inject
    DtmfDispatcher dtmfDispatcher;

    @Inject
    MenuRunner menuRunner;

    @Inject
    AnnouncementLoop announcementLoop;

    @Inject
    HoldHandler holdHandler;

    @Resource(lookup = "concurrent/codecExecutor")
    ManagedExecutorService managedExecutorService;

    /**
     * Handles an incoming INVITE.
     * Rejects with {@code 403 Forbidden} for blacklisted callers, {@code 480 Temporarily
     * Unavailable} when no language factory is registered.
     * Sends {@code 180 Ringing} immediately and delegates further processing to a managed
     * executor task.
     */
    public void accept(SipServletRequest req) throws IOException {
        String callId = req.getSession().getId();

        if (this.callSessionManager.get(callId) != null) {
            // Re-INVITE on an established session: handle hold/unhold.
            this.holdHandler.handle(req);

            return;
        }

        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}INVITE from [{1}] to [{2}]",
                SipCallHeaders.callPrefix(callId),
                req.getFrom(),
                req.getTo());

        if (this.sipCallBlacklist.isBlacklisted(req)) {
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "{0}Rejecting blacklisted call from [{1}]",
                    SipCallHeaders.callPrefix(callId),
                    req.getFrom());
            req.createResponse(SipServletResponse.SC_FORBIDDEN).send();

            return;
        }

        var sorted = LanguageSelector.sorted(this.languageFactories);
        var menu = buildLanguageMenu(sorted);

        if (menu.isEmpty()) {
            rejectNoLanguage(req, callId);

            return;
        }

        req.createResponse(SipServletResponse.SC_RINGING).send();
        this.managedExecutorService.execute(() -> processAcceptedInvite(req, menu, callId));
    }

    private void processAcceptedInvite(
            SipServletRequest req, SequencedMap<Integer, LanguageFactory> menu, String callId) {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return;
        }

        try {
            if (menu.size() == 1) {
                acceptAndAnnounce(req, menu.firstEntry().getValue());

                return;
            }

            acceptAndPlayMenu(req, menu);
        } catch (IOException ioException) {
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "{0}Failed to accept call from [{1}]",
                    SipCallHeaders.callPrefix(callId),
                    req.getFrom(),
                    ioException);
        }
    }

    private void acceptAndAnnounce(SipServletRequest req, LanguageFactory factory) throws IOException {
        SipSession session = req.getSession();
        String sessionId = session.getId();
        CallMedia media = negotiateAndRespond(req, sessionId, "language [" + factory.displayName() + "]");

        Instant startTime = Instant.now();
        String callerIdentitySummary = SipCallHeaders.buildCallerIdentitySummary(req);
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Caller identity: {1}",
                SipCallHeaders.callPrefix(sessionId),
                callerIdentitySummary);
        LinkedHashMap<Integer, LanguageFactory> singleMenu = new LinkedHashMap<>();
        singleMenu.put(1, factory);

        Future<?> callFuture = this.managedExecutorService.submit(() -> {
            // forCall() must run on the announcement thread so that the confined Arena is owned
            // by the thread that will also call encode() and close() — preventing WrongThreadException.
            var callCodec = media.codec().forCall();

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                callCodec.close();
                return;
            }

            AudioPlayer player = new RtpAudioPlayer(media, callCodec, this.pcmDecoderFactory);

            try {
                this.announcementLoop.play(session, player, factory, sessionId, media);
            } finally {
                callCodec.close();
            }
        });

        this.callSessionManager.register(
                sessionId,
                new CallState(
                        session,
                        callFuture,
                        CompletableFuture.completedFuture(null),
                        singleMenu,
                        media,
                        startTime,
                        callerIdentitySummary));
    }

    private void acceptAndPlayMenu(SipServletRequest req, SequencedMap<Integer, LanguageFactory> menu)
            throws IOException {
        SipSession session = req.getSession();
        String sessionId = session.getId();
        CallMedia media =
                negotiateAndRespond(req, sessionId, "language-selection menu (" + menu.size() + " languages)");

        Instant startTime = Instant.now();
        String callerIdentitySummary = SipCallHeaders.buildCallerIdentitySummary(req);
        LOGGER.log(
                System.Logger.Level.DEBUG,
                "{0}Caller identity: {1}",
                SipCallHeaders.callPrefix(sessionId),
                callerIdentitySummary);

        Future<?> callFuture = this.managedExecutorService.submit(() -> {
            // forCall() must run on the menu thread so that the confined Arena is owned
            // by the thread that will also call encode() and close() — preventing WrongThreadException.
            var callCodec = media.codec().forCall();
            AudioPlayer player = new RtpAudioPlayer(media, callCodec, this.pcmDecoderFactory);

            try {
                this.menuRunner.run(session, player, menu, sessionId, media);
            } finally {
                callCodec.close();
            }
        });
        Future<?> receiverFuture = this.dtmfDispatcher.startReceiver(media, sessionId);

        this.callSessionManager.register(
                sessionId,
                new CallState(session, callFuture, receiverFuture, menu, media, startTime, callerIdentitySummary));
    }

    /**
     * Negotiates SDP, logs the accepted codec, and sends {@code 200 OK} with the SDP answer.
     *
     * @param description human-readable summary for the INFO log line, e.g.
     *     {@code "language [English]"} or {@code "language-selection menu (3 languages)"}
     */
    private CallMedia negotiateAndRespond(SipServletRequest req, String sessionId, String description)
            throws IOException {
        CallMedia media = this.sdpNegotiator.negotiate(req);
        LOGGER.log(
                System.Logger.Level.INFO,
                "{0}200 OK — {1}, codec [{2}], remote RTP [{3}]",
                SipCallHeaders.callPrefix(sessionId),
                description,
                media.codec().sdpName(),
                media.remoteRtp());
        SipServletResponse response = req.createResponse(SipServletResponse.SC_OK);
        response.setContent(media.sdpAnswer().getBytes(StandardCharsets.UTF_8), "application/sdp");
        response.send();

        return media;
    }

    /**
     * Builds an ordered digit-to-factory menu from all discovered language factories,
     * applying the {@code sip.languages.enabled} filter if configured.
     *
     * <p>When the config is blank, all languages are included with digits assigned sequentially
     * by {@link LanguageFactory#defaultOrder()}.
     * When explicit digits are configured (e.g. {@code 1=de-DE,2=en-GB}), those digits are used
     * directly; unrecognised locale tags are silently skipped.
     */
    private SequencedMap<Integer, LanguageFactory> buildLanguageMenu(List<LanguageFactory> sorted) {
        SequencedMap<Integer, String> configured = LanguageMenuConfig.parse(this.enabledLanguagesConfig);

        if (configured.isEmpty()) {
            LinkedHashMap<Integer, LanguageFactory> menu = new LinkedHashMap<>();

            for (int index = 0; index < sorted.size(); index++) {
                menu.put(index + 1, sorted.get(index));
            }

            return menu;
        }

        LinkedHashMap<Integer, LanguageFactory> menu = new LinkedHashMap<>();

        for (Map.Entry<Integer, String> entry : configured.entrySet()) {
            sorted.stream()
                    .filter(factory -> factory.locale().toLanguageTag().equals(entry.getValue()))
                    .findFirst()
                    .ifPresent(factory -> menu.put(entry.getKey(), factory));
        }

        return menu;
    }

    private static void rejectNoLanguage(SipServletRequest req, String callId) throws IOException {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "{0}No language factories registered — rejecting incoming call with 480 Temporarily Unavailable",
                SipCallHeaders.callPrefix(callId));
        req.createResponse(SipServletResponse.SC_TEMPORARLY_UNAVAILABLE).send();
    }
}
