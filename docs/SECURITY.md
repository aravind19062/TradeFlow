# TradeFlow security model

This document covers authentication, tokens, key management, two-factor authentication, account
recovery and service-to-service identity. Live-trading-specific controls are described in
[DUAL_MODE_TRADING.md](DUAL_MODE_TRADING.md) §5–6.

## Tokens

| Token | Signed by | Audience | Lifetime | Where it lives | Used for |
|---|---|---|---|---|---|
| Access token | user-service (RS256) | `tradeflow` | 15 min | browser **memory only** | every API call |
| Refresh token | – (opaque, 256-bit random; SHA-256 stored) | – | 8 h idle, 24 h absolute | **HttpOnly, Secure, SameSite=Strict** cookie scoped to `/api/auth` | renewing the access token |
| Step-up token | user-service (RS256) | `tradeflow-live` | 10 min | browser memory | live-trading actions only |
| Service token | user-service (RS256) | `tradeflow` (role `SERVICE`) | 5 min | calling service's memory | `/internal/**` calls between services |

* **Asymmetric signing.** Only user-service holds the RSA private key. Every other service and the
  gateway verify with the public key ring and **cannot mint any token**. Verifiers accept only RS256
  (HS256 and `none` are rejected outright), check `iss`, `aud`, `exp` and `nbf`, and look the key up by `kid`.
* **Key ids** are derived from the key: the first 16 hex characters of SHA-256 over the DER public key
  (`KeyIds`). Public key files must be named `<kid>.pem`; anything else refuses to load.
* **Audiences separate privileges.** A step-up token is rejected by the ordinary services, and an ordinary
  access token is rejected wherever a live-trading action requires `tradeflow-live`.
* **Claims.** `sub` (user id), `roles`, `email`, `name`, `sid` (session id), `tv` (token version), `amr`
  (`pwd`, `otp`, `rec`), `auth_time`, `jti`.

## Sessions, expiry and revocation

* The refresh token **rotates on every use**. Presenting an already-rotated token more than 10 seconds
  after its rotation is treated as theft: the whole session (token family) is revoked and a
  `REFRESH_REUSE_DETECTED` security event is recorded. Within those 10 seconds (two tabs refreshing at
  once) the request is refused without revoking anything or issuing anything.
* **Sign out** revokes the session's refresh family and clears the cookie.
* **Sign out everywhere**, disabling MFA and an administrator MFA reset increment the user's
  **token version**: every refresh family is revoked and no outstanding access token can obtain a
  step-up token. Ordinary access tokens remain valid until they expire, so revocation reaches the demo
  APIs within at most 15 minutes. Live-trading actions check the token version on every call.
* The refresh endpoints require the `X-TradeFlow-Client` header as well as the SameSite=Strict cookie
  (CSRF defence in depth); the gateway has no CORS configuration, so other origins cannot send it.

## Two-factor authentication (TOTP)

* RFC 6238, HMAC-SHA1, 30-second steps, 6 digits: compatible with standard authenticator apps.
  ±1 step of clock drift is accepted, and **each step can be used once** (replay protection).
* **Enrolment** requires the account password, then a code from the new device. The seed is shown once
  (QR code and manual key) and is stored **encrypted** (AES-256-GCM, bound to the user id).
* **10 one-time recovery codes** (50 bits each, BCrypt-hashed) are shown once at enrolment and can be
  regenerated with password plus TOTP.
* **Sign-in** with MFA: password, then an MFA challenge (5 minutes, 5 attempts, single use), then a TOTP
  or recovery code. Wrong codes count towards the login throttle.
* **Disabling** requires the password and a second factor, and ends every session.
* **Live trading requires MFA.** Step-up accepts a TOTP code only (no recovery codes).

## Account recovery (lost device and recovery codes)

There is **no self-service bypass** of the second factor. Recovery is an audited administrator action:

1. The user contacts support. The operator verifies identity **out of band** (procedure owned by the
   operator; for example a video call against previously provided details) and records a reference.
2. An **administrator who has MFA enabled** calls
   `POST /api/admin/users/{id}/mfa/reset` with `reason` (≥ 20 characters), `verificationReference` and
   their **own current TOTP code**. Administrators cannot reset themselves.
3. Effect: the user's seed and recovery codes are deleted, every session is ended, the reset is recorded
   on **both** accounts (actor, reason, reference, IP, user agent), and **live-trading step-up is refused
   for 24 hours**.
4. The user still needs their password to sign in, and must enrol a new authenticator before live trading.

## Service-to-service identity

* Background jobs that have no user token (e.g. cross-service reconciliation) authenticate to
  user-service with **client credentials** (client id = `spring.application.name`, a per-service secret)
  at `POST /internal/auth/service-token` and receive a 5-minute token with role `SERVICE` and subject
  `service:<name>`. A compromised service can obtain only that, never a user token.
* `/internal/**` is denied by the gateway and accepted by services only with role `SERVICE`.
* live-trading-service uses its own client credentials (`TRADEFLOW_SERVICE_SECRET_LIVE`) to call
  `GET /internal/users/{id}/session-state` (user-service) and `GET /internal/readiness/{id}/eligibility`
  (learning-service) before every live action. It cannot mint tokens.
* Placeholder client secrets (`local-dev-only…`) are refused outside the `local` profile; real secrets
  must be ≥ 24 characters.

## Keys and secrets

| Material | Who can read it | Local Docker | Production |
|---|---|---|---|
| Token signing key (RSA-3072, PKCS#8) | user-service only | volume `jwt_private`, generated once by `keygen` | secret manager / KMS-backed file |
| Token public keys (`<kid>.pem`) | all services, gateway | volume `jwt_public` | config distribution |
| Data-encryption key ring (AES-256) | user-service only (TOTP seeds) | volume `user_data_keys` | secret manager |
| Live data-encryption key ring (AES-256) | live-trading-service only (broker tokens) | volume `live_data_keys` | secret manager |
| Service client secrets | user-service and the client service | `.env` / local placeholders | secret manager |

Nothing secret is committed. `make keys` creates the same material under `.secrets/` (git-ignored) for
running services outside Docker. Services refuse to start without their keys.

**Signing-key rotation:** (1) generate a new key pair; (2) add its public key to every verifier's ring and
restart them; (3) switch user-service to the new private key; (4) after 24 hours (the longest session) remove
the old public key. **Data-key rotation:** add a key id, set `active` to it; old ciphertexts stay readable
by their key id and can be re-encrypted (`DataEncryptor.needsRotation`).

## Live trading

* **Step-up for every state-changing live action**, checked again by live-trading-service: audience
  `tradeflow-live`, password + TOTP, at most 10 minutes since authentication, token version current, MFA on.
  The gateway also refuses step-up tokens outside `/api/live/**` and requires them for live order and broker POSTs.
* **Broker tokens** are encrypted at rest with a key ring only live-trading-service holds, bound to the
  connection, decrypted only at call time, never returned by an API, never logged (`toString` redacts).
  The frontend never sees them: the browser only ever holds TradeFlow's own tokens.
* **Every live action is independently authorised and audited** (allowed and refused) in a hash-chained,
  append-only log. Administrators can halt new orders but have no endpoint that places or cancels orders for a user.

## Security events

`security_events` (user-service) is append-only and records sign-ins and failures, MFA changes,
recovery-code use, step-ups, refresh-token reuse, sign-outs and administrator resets. Users see their own
activity on the Security page; administrators can list a user's events.
