# Copilot Instructions – proximo-pitido-j

## Project

Java 25 / JakartaEE 8 / Open Liberty SIP Servlet application.
Registers as a callable SIP number with a configurable SIP registrar on startup; future goal: answer incoming calls with a time announcement.
Designed to work with any RFC 3261-compliant SIP provider (Deutsche Telekom, SIPGate, easybell, O2, Vodafone DSL, etc.).

## Build & Run

- Maven 4: use `./mvnw` or `sdk use maven 4.0.0-rc-5`
- Start server: `./mvnw liberty:run -am -pl war`
- Logs: `war/target/wlp-usr/servers/proximo-pitido/logs/messages.log` and `trace.log`
- Before committing: `git config commit.gpgsign false` and `./mvnw spotless:apply`

## Module layout

| Module | Purpose |
|--------|---------|
| `api/` | Public API interfaces |
| `spi/` | SPI interfaces |
| `core/` | Core implementation |
| `war/` | Liberty WAR — SIP Servlet, config, secrets |

## SIP registration flow

1. `SipTimeServlet` (`@SipServlet(loadOnStartup=1)`) — starts registration from `init()` via a 2-second virtual thread delay (Liberty DAR initialises concurrently; sending SIP immediately fails).
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
- `dar.properties` must **not** include REGISTER — Liberty creates both client+server transactions → 405 loop. Only include methods that the app handles as a UAS (e.g. INVITE, OPTIONS).
- `SipFactory` and `SipProvider` are `null` inside `init()` if called synchronously. The 2-second virtual thread delay is required.

## Coding style

1. **One class per service** and **one class per responsibility** — if a class does two unrelated things, split it.
2. **No more than three indentation levels** — extract methods to flatten nesting.
3. **Avoid `else`** — prefer early returns and guard clauses. Use `else` only when it genuinely aids readability.
4. **Comments signal missing methods** — if a line needs an inline comment, that comment is the method name. Extract the block.
5. **Code for testability** — avoid `static` methods that depend on other state. Simple pure-function utilities in utility classes are fine as `static`. Inject collaborators rather than `new`-ing them inside methods.
6. **Correct module placement** — pure-Java logic (no servlet/SIP container classes) belongs in `core`. Servlet/SIP-container-dependent code belongs in `war`.

## Key files

- `war/src/main/java/.../SipTimeServlet.java` — servlet entry point, `doResponse()` delegates to extracted methods per status
- `war/src/main/java/.../listener/SipRegistrationListener.java` — REGISTER orchestration, state machine
- `core/src/main/java/.../core/sip/SrvDnsResolver.java` — JNDI-based `_sip._tcp` SRV lookup with caching
- `core/src/main/java/.../core/sip/DigestMd5Computer.java` — RFC 2617 Digest MD5 computation
- `core/src/main/java/.../core/sip/SipDigestChallenge.java` — parses `WWW-Authenticate` challenge header
- `core/src/main/java/.../core/sip/LocalSipHostProvider.java` — resolves Contact header address (public IP or auto-detected)
- `war/src/main/liberty/config/server.xml` — Liberty config, sipEndpoint, sipApplicationRouter
- `war/src/main/liberty/config/dar.properties` — DAR routing (REGISTER absent intentionally)
- `war/src/main/liberty/config/server.env` — **secrets**, never commit
- `war/src/main/webapp/WEB-INF/sip.xml` — minimal (app-name + display-name only)
