# Copilot Instructions – proximo-pitido-j

## Project

Java 25 / JakartaEE 8 / Open Liberty SIP Servlet speaking-clock application.
Name: _Próximo Pitido_ = Spanish for "the next beep" — the speaking-clock phrase
_"Al próximo pitido serán las tres horas…"_ ("At the next beep it will be three o'clock…").
Designed to work with any RFC 3261-compliant SIP provider (Deutsche Telekom, SIPGate, easybell, O2, Vodafone DSL, etc.).

## Intended call flow

- On startup: REGISTER with configured SIP provider.
- On incoming INVITE:
  - **Single language on classpath** → answer immediately, play time announcement.
  - **Multiple languages on classpath** → play language-selection menu in a cycle:
    `"Für Deutsch drücken Sie die 1."  /  "Press 2 for English."  / …`
    Caller presses a digit **at any time** (even mid-phrase) — playback stops immediately,
    chosen language plays the time announcement.
  - Language order is configurable; falls back to `LanguageFactory.getDefaultOrder()` if not configured.
- DTMF digits arrive as SIP INFO requests (RFC 2976) — `INFO` must be listed in `dar.properties`.

## Build & Run

- Maven 4: use `./mvnw` or `sdk use maven 4.0.0-rc-5`
- Start server: `./mvnw liberty:run -am -pl war`
- Logs: `war/target/wlp-usr/servers/proximo-pitido/logs/messages.log` and `trace.log`
- Before committing: `git config commit.gpgsign false` and `./mvnw spotless:apply`

## Module layout

| Module | Purpose |
|--------|---------|
| `api/` | Public API interfaces (`AudioPlayer`, `TimeAnnouncement`, `LanguageSelectionAnnouncement`) |
| `spi/` | SPI for language plugins (`LanguageFactory`) |
| `core/` | Provider-agnostic logic: SRV DNS, Digest auth, host detection |
| `war/` | Liberty WAR — SIP Servlets, Liberty config, secrets |

## Default Application Router (DAR) — `dar.properties`

**This is one of the most common sources of bugs for new engineers.**

Every SIP message that enters or leaves a Liberty SIP application is routed through the DAR
before it reaches any servlet. `dar.properties` maps each SIP method to the application that
handles it.

### Line format

```
METHOD: ("AppName", "RouteHeader", "Region", "Route", "Directive", "Order")
```

- `AppName` — matches `@SipServlet(applicationName = "…")`.
- `RouteHeader` — usually `"DAR:From"`.
- `Region` — `"NEUTRAL"` for a simple UAS.
- `Route` — extra SIP route to push; `""` for none.
- `Directive` — `"NO_ROUTE"` = stop here; `"CONTINUE"` = pass to next app.
- `Order` — priority when multiple apps compete; `"0"` = first.

### CRITICAL: REGISTER must NOT be in dar.properties

If `REGISTER` is listed, the DAR intercepts the app's outgoing REGISTER, creates a server-side
transaction, routes it back into the app as an incoming request, gets no handler, and Liberty
responds `405 Method Not Allowed` to the registrar — producing an infinite 405 loop.
Unlisted methods pass straight through Liberty to the network. **Leave REGISTER out.**

### INFO must be listed

SIP INFO carries DTMF digits (RFC 2976). If `INFO` is absent, keypress events during the
language-selection menu are silently dropped and the caller can never make a selection.

## SIP registration flow

1. `SipRegistrationServlet` (`@SipServlet(loadOnStartup=1)`) — starts registration from `init()` via a 2-second virtual thread delay (Liberty DAR initialises concurrently; sending SIP immediately fails).
2. `SipRegistrationListener` (`@ApplicationScoped` CDI bean) — does the actual REGISTER work.
3. SRV DNS: Liberty's SIP stack does **not** follow SRV records. `SrvDnsResolver` uses JNDI `DnsContextFactory` to resolve `_sip._tcp.<registrar>` manually.
4. TCP transport: Fritz!Box SIP ALG intercepts UDP/5060. Force TCP via `IBM-Destination: <host>:5060;transport=tcp` and `sipEndpoint`.
5. Contact header: Liberty does **not** auto-add Contact for app-originated REGISTERs — set it explicitly to `sip:<sipId>@<publicIP>`. `LocalSipHostProvider` resolves the contact address.
6. Digest auth: use `sipFactory.createRequest(origRequest, true)` (same Call-ID, RFC 3261 §10.2) and **manual** Digest MD5 — do NOT use `addAuthHeader()` (uses wrong Digest URI).

## Digest auth (RFC 2617 MD5)

```
HA1      = MD5(username : realm : password)
HA2      = MD5(REGISTER : sip:<registrar-domain>)
response = MD5(HA1 : nonce : nc : cnonce : auth : HA2)
```

- Digest URI is always `sip:<registrar-domain>` (the configured registrar, **not** the SRV-resolved hostname).
- `qop=auth`, `nc=00000001`, random 12-byte cnonce (hex-encoded).
- Auth username = whatever the provider requires (often an e-mail address).
- `DigestMd5Computer` in `core` handles the computation; `SipDigestChallenge` parses the challenge header.

**Why manual Digest?** Liberty's `addAuthHeader()` uses the full Request-URI (SRV-resolved hostname) as the Digest URI. The correct value is `sip:<registrar-domain>`; using the wrong URI produces a hash mismatch.

## State machine (`SipRegistrationListener`)

```
0 = IDLE → 1 = AUTH_IN_PROGRESS → 2 = REGISTERED
```

- `registerWithAuth()` CAS 0→1; ignores duplicate challenges when state ≥ 1.
- `markRegistered()` sets state to 2.
- `resetAuthState()` CAS 1→0 only (never resets from REGISTERED).
- `canRetryAfterStale()` allows exactly one stale-nonce retry per session (`AtomicBoolean staleRetryUsed`).

## MicroProfile Config ↔ env var mapping

| Env var | MP Config property |
|---------|--------------------|
| `SIP_REGISTRAR` | `sip.registrar` |
| `SIP_SIPID` | `sip.sipid` |
| `SIP_USER_ID` | `sip.user.id` |
| `SIP_USER_PASSWORD` | `sip.user.password` |
| `SIP_LOCAL_HOST` | `sip.local.host` |
| `SIP_PUBLIC_HOST` | `sip.public.host` |

**Warning**: do NOT add `<variable>` in `server.xml` with names matching MP Config properties (e.g. `sip.local.host`) — Liberty variables leak into MP Config and override env vars.

## Liberty gotchas

- `sipEndpoint host="*"` resolves to `127.0.1.1` on this host — use `SIP_LOCAL_HOST=192.168.x.y` (LAN IP) for the endpoint binding.
- `SIP_PUBLIC_HOST` is the public IP for the Contact header; distinct from `SIP_LOCAL_HOST`.
- Do **not** declare `<servlet>` in `sip.xml` — it overrides `@SipServlet(loadOnStartup=1)` and the servlet never loads.
- `SipFactory` and `SipProvider` are `null` inside `init()` if called synchronously. The 2-second virtual thread delay is required.

## Writing style

Applies to all prose: `README.adoc`, Javadoc, inline comments, and IDE spell-checker settings.

- **One sentence per line** — insert a line break after every sentence (full stop, exclamation mark, or question mark).
  Do not wrap sentences mid-way; do not run two sentences together on the same line.
  This makes diffs readable and sentence boundaries obvious.
- **British English spelling** — analogue, initialise, organise, recognise, behaviour, colour, catalogue, etc.
  Use the `-ise`/`-isation` suffix, not `-ize`/`-ization`.
- **Oxford comma** — always include the serial comma before the final item in a list of three or more: "reading, writing, and arithmetic."
- **AsciiDoc line breaks** — a blank line separates paragraphs.
  A bare line break (no `+`) within a paragraph creates a visual line break in the source only; AsciiDoc renders it as a space.
  Use this intentionally: one sentence per source line, paragraph boundaries marked by a blank line.

## Coding style

1. **One class per service** and **one class per responsibility** — if a class does two unrelated things, split it.
2. **No more than three indentation levels** — extract methods to flatten nesting.
3. **Avoid `else`** — prefer early returns and guard clauses. Use `else` only when it genuinely aids readability.
4. **Comments signal missing methods** — if a line needs an inline comment, that comment is the method name. Extract the block.
5. **Code for testability** — avoid `static` methods that depend on other state. Simple pure-function utilities in utility classes are fine as `static`. Inject collaborators rather than `new`-ing them inside methods.
   Any collaborator with pluggable implementations must be an `@ApplicationScoped` CDI bean so that it can be injected, stubbed, and replaced in tests. `static` factory methods are acceptable only for pure functions with no external state (e.g. encoding helpers, format converters).
   `PcmDecoderFactory` is the canonical example: it became `@ApplicationScoped` so tests can inject a fake factory and decoders can be discovered via CDI `Instance<PcmDecoder>` without hard-coded `if`-chains.
6. **Correct module placement** — pure-Java logic (no servlet/SIP container classes) belongs in `core`. Servlet/SIP-container-dependent code belongs in `war`.
   Audio decoding (PCM decoders, MIME detection, `PcmDecoderFactory`) lives in `core.media` so it can be used from a CLI or test without a SIP container.
   Only classes tightly coupled to an active RTP/SIP session (e.g. `RtpAudioPlayer`, `CallMedia`, `SdpNegotiator`) stay in `war.media`.
7. **Empty lines around control-flow statements** — `if`, `try`, and `return` must be preceded by a blank line, *unless* they are the first statement in their enclosing block.
   A closing brace of an `if` or `try` block must be followed by a blank line, *unless* it is the last statement in its enclosing block.
8. **`this.` prefix for instance fields** — always qualify instance field access with `this.` (e.g. `this.socket`, `this.remoteRtp`).
   Do *not* use `this.` when calling instance methods.
9. **No `Optional<>` in method parameters** — `Optional` must not appear as a method parameter type.
   If a parameter may be absent, use a nullable parameter documented with `@param … or {@code null} if …` in the Javadoc, or provide a dedicated method overload.
   Example:
   ```java
   var sorted = LanguageSelector.sorted(languageFactories);

   if (sorted.isEmpty()) {
       rejectNoLanguage(req);
       return;
   }

   if (sorted.size() == 1) {
       acceptAndAnnounce(req, sorted.get(0));
       return;
   }

   acceptAndPlayMenu(req, sorted);
   ```

## Unit test style

Write only the tests needed to verify the change.
Use JUnit 5 and Mockito (already on the classpath).
One test class per production class under test.

Structure every test method body with three comment labels:

```java
// given
// … set up preconditions …

// when
// … call the code under test …

// then
// … assert the expected outcome …
```

Example:

```java
@Test
void resetForReRegistration_fromRegistered_resetsToIdle() {
    // given
    var listener = new SipRegistrationListener();
    listener.markRegistered();

    // when
    listener.resetForReRegistration();

    // then
    assertTrue(listener.isIdle());
}
```

Use Mockito only when there is no simpler alternative.
Prefer plain instantiation and package-private helpers over deep mock graphs.

## Key files

- `war/src/main/java/.../war/media/RtpAudioPlayer.java` — RTP/PCMA sender over UDP; one instance per call
- `war/src/main/java/.../war/media/package-info.java` — documents that only SIP/RTP session-coupled classes belong here
- `core/src/main/java/.../core/media/PcmDecoderFactory.java` — `@ApplicationScoped` CDI bean; selects decoder via Tika MIME + extension
- `codecs/input/src/main/java/.../codecs/input/OggOpusPcmDecoder.java` — preferred decoder (OGG/Opus via libopus FFM)
- `core/src/main/java/.../core/media/WavPcmDecoder.java` — deprecated WAV decoder
- `war/src/main/java/.../listener/SipCallHandler.java` — `@ApplicationScoped` CDI bean; owns all call-session logic
- `war/src/main/java/.../listener/SipRegistrationListener.java` — REGISTER orchestration, state machine
- `core/src/main/java/.../core/sip/SrvDnsResolver.java` — JNDI-based `_sip._tcp` SRV lookup with caching
- `core/src/main/java/.../core/sip/DigestMd5Computer.java` — RFC 2617 Digest MD5 computation
- `core/src/main/java/.../core/sip/SipDigestChallenge.java` — parses `WWW-Authenticate` challenge header
- `core/src/main/java/.../core/sip/LocalSipHostProvider.java` — resolves Contact header address (public IP or auto-detected)
- `war/src/main/liberty/config/server.xml` — Liberty config, sipEndpoint, sipApplicationRouter
- `war/src/main/liberty/config/dar.properties` — DAR routing (REGISTER absent intentionally, INFO present for DTMF)
- `war/src/main/liberty/config/server.env` — **secrets**, never commit
- `war/src/main/webapp/WEB-INF/sip.xml` — minimal (app-name + display-name only)
