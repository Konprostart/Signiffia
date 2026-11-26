# Signiffia Sync Server

Signiffia is a fully offline APK/AAB signer. This directory contains the **optional**, explicitly
enabled **encrypted-sync server** that turns it into a multi-device app. The server is an
**untrusted relay** — it can never decrypt a user's vault.

This is a **separate** Gradle project from the Android app and is **not** part of the APK.

## Trust model

The server never holds, and can never decrypt:

- the Master Vault Key (MVK),
- the recovery key (24-word mnemonic),
- any signing private key,
- any keystore password.

It only ever receives ciphertext:

| Object | What it actually is |
|---|---|
| `vault object` | `AES-256-GCM(plaintext entries, MVK)` — the plaintext is the same `BackupEntry` list the `.sgnbak` backup uses |
| `wrapped MVK (recovery)` | `AES-256-GCM(MVK, recovery key)` — stored so a new device can recover |
| `wrapped MVK (per device)` | `AES-256-GCM(MVK, device key)` — local to each device |

Authentication and encryption are strictly separate: the account password (or a passkey)
authenticates to the server, but only the MVK (recovery key / device key) can decrypt the vault.
A successful login never grants the vault.

## Security properties

- **Session tokens are stored only as SHA-256 hashes** — a database leak exposes no usable
  bearer tokens.
- **Password verifier scheme**: the client derives `verifier = Argon2id(password, salt)` locally
  and sends only the verifier (never the raw password) over TLS; the server compares it in
  constant time.
- **Rate limiting** on `/auth/login` and `/auth/register` (keyed by `X-Forwarded-For` /
  `X-Real-IP`; set these headers in the reverse proxy).
- **Monotonic vault version + epoch**: a stale-version or stale-epoch upload is rejected, so an
  attacker cannot roll back or replay a vault object. The client binds version + epoch into the
  AES-GCM AAD, so substitution is additionally detected on the client.
- **Revocation**: revoking a device bumps the epoch and stops that device from authenticating,
  which blocks *future* synchronization. It **cannot erase data already downloaded** to a lost
  device (documented in the app and here).
- **Passkey/WebAuthn**: single-use, short-lived challenges; the origin is verified; the
  cryptographic ceremony is delegated to webauthn4j (Apache-2.0, FIDO-conformance tested).
- **No secrets in logs**: request/response bodies are never logged.

## Architecture

```
app (Android) ── HTTPS ──► reverse proxy (Caddy/nginx) ──► this server ──► PostgreSQL
                                                              │
                                                        only ciphertext + metadata
```

## Requirements

- JDK 17+
- PostgreSQL 13+ (or the bundled `docker-compose` service)
- A domain + TLS certificate (required for passkeys and for production in general)

## Configuration

The server is configured with environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port |
| `SIGNIFFIA_STORE` | `postgres` | `postgres` (production) or `memory` (development/tests) |
| `SIGNIFFIA_DB_URL` | `jdbc:postgresql://localhost:5432/signiffia` | JDBC URL |
| `SIGNIFFIA_DB_USER` | `signiffia` | database user |
| `SIGNIFFIA_DB_PASSWORD` | `signiffia` | database password |
| `SIGNIFFIA_RP_ID` | `localhost` | WebAuthn relying-party id (your domain, no scheme/port) |
| `SIGNIFFIA_ORIGIN` | `http://localhost:8080` | WebAuthn origin (`https://<your-domain>`) |

## Build

```bash
cd server
gradle --no-daemon build      # compiles and runs the unit tests
gradle --no-daemon installDist
./build/install/server/bin/server
```

## Deployment (Docker Compose — recommended)

```bash
cd server
cp .env.example .env   # set SIGNIFFIA_DB_PASSWORD (and any overrides)
docker compose up -d --build
```

This starts PostgreSQL (with a named volume) and the server. The server binds to
`127.0.0.1:8080`; put a TLS reverse proxy in front of it.

### HTTPS / domain

Passkeys and production require HTTPS. Terminate TLS in a reverse proxy and forward to
`127.0.0.1:8080`. Set the client IP so rate limiting works:

```nginx
# nginx example
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header Host $host;
}
```

Set `SIGNIFFIA_RP_ID` and `SIGNIFFIA_ORIGIN` to your public `https://` domain. The Android
client refuses non-HTTPS server URLs.

## Database migrations

Schema migrations are **versioned and additive**. On startup the server applies any pending
migrations in a transaction and records them in `schema_migrations`, so upgrades never lose
data. Never hand-edit an applied migration — add a new one instead.

## Database backup and restore

The database holds only ciphertext and metadata, so a backup can never decrypt a vault.

```bash
# Backup
pg_dump "$DATABASE_URL" > signiffia-server-$(date +%F).sql

# Restore
psql "$DATABASE_URL" < signiffia-server-$(date +%F).sql
```

After a restore, users simply re-authenticate and re-download their (still encrypted) vault.
A stale vault version is rejected by the client's version/epoch check.

## Upgrading the server without data loss

1. Pull the new image / deploy the new jar.
2. On startup the server applies new migrations additively (existing tables are untouched).
3. Roll back by redeploying the previous image (additive migrations are forward-compatible).

## API endpoints

The vault/sync API uses a dependency-free binary wire format (big-endian, length-prefixed).
The WebAuthn/passkey API uses JSON.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | liveness |
| GET | `/auth/salt` | no | fetch the account's Argon2id salt |
| POST | `/auth/register` | no | create account (username, salt, verifier) |
| POST | `/auth/login` | no | password login → session |
| GET | `/vault/meta` | Bearer | current vault version + epoch |
| PUT | `/vault/object` | Bearer | upload encrypted vault snapshot |
| GET | `/vault/object` | Bearer | download encrypted vault snapshot |
| GET | `/devices` | Bearer | list devices |
| POST | `/devices/revoke` | Bearer | revoke a device (bumps epoch) |
| POST | `/enroll/begin` | Bearer | start device pairing (single-use ticket) |
| POST | `/enroll/confirm` | Bearer | confirm pairing |
| POST | `/webauthn/register/begin` | Bearer | passkey registration options |
| POST | `/webauthn/register/complete` | Bearer | complete passkey registration |
| POST | `/webauthn/auth/begin` | no | passkey authentication options |
| POST | `/webauthn/auth/complete` | no | complete passkey authentication → session |

## Known limitation

Revocation stops a device from fetching **future** vault updates, but it **cannot erase
secrets already downloaded** to a compromised device. End-to-end encryption protects data in
transit and at rest on the server, not data already on a lost/stolen device.

## License

Apache-2.0. Server dependencies: Ktor (Apache-2.0), PostgreSQL JDBC (BSD-2-Clause), HikariCP
(Apache-2.0), WebAuthn4J (Apache-2.0). No GPL/AGPL/SSPL.
