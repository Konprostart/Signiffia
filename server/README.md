# Signiffia Sync Server — reference implementation

This directory contains a **reference** implementation of the untrusted Signiffia sync
server, together with the deployment, database and security documentation. It is a separate
project from the Android app and is **not** part of the app build.

## Trust model

The server is an **untrusted relay**. It never holds, and can never decrypt:

- the Master Vault Key (MVK),
- the recovery key,
- any signing private key,
- any keystore password.

It only ever receives ciphertext:

| Object | What it actually is |
|---|---|
| `vault object` | `AES-256-GCM(plaintext entries, MVK)` — the plaintext is the same `BackupEntry` list the `.sgnbak` backup uses |
| `wrapped MVK (recovery)` | `AES-256-GCM(MVK, recovery key)` |
| `wrapped MVK (per device)` | `AES-256-GCM(MVK, device key)` — relayed only during enrollment |

The integrity/authenticity of the vault is provided by AES-GCM with AAD binding the vault
version + epoch, so a malicious or compromised server cannot substitute or roll back vault
objects without detection.

## Wire protocol

The client and server exchange a dependency-free binary format (`WireCodec`): big-endian,
length-prefixed fields. Binary fields (ciphertext, keys, nonces) are raw bytes; strings are
UTF-8. Endpoints are identified by HTTP method + path; errors are non-200 with a UTF-8
message body.

## API endpoints

| Method | Path | Auth | Request body | Response body |
|---|---|---|---|---|
| GET | `/auth/salt` | no | `username` | `salt` (bytes) |
| POST | `/auth/register` | no | `username`, `salt`, `verifier` | (empty) |
| POST | `/auth/login` | no | `username`, `verifier` | `userId`, `sessionToken`, `deviceId` |
| PUT | `/vault/object` | Bearer | `vaultVersion`(long), `epoch`(long), `deviceId`, `createdAt`(long), `recordsSha256`, `nonce`, `ciphertext`, `wrappedMvkRecovery` | (empty) |
| GET | `/vault/object` | Bearer | (empty) | same fields as PUT; 404 when none |
| GET | `/devices` | Bearer | (empty) | `count`(int), then per device: `deviceId`, `name`, `platform`, `createdAt`, `lastSeenAt`, `revoked`(int 0/1) |
| POST | `/devices/revoke` | Bearer | `deviceId` | `newEpoch`(long) |
| POST | `/enroll/begin` | Bearer | `devicePublicKey` | `ticketId`, `expiresAtMillis`(long), `serverPublicKey`, `nonce` |
| POST | `/enroll/confirm` | Bearer | `ticketId`, `wrappedMvkForDevice` | (empty) |

Authentication is a **password verifier** scheme: the client derives `verifier =
Argon2id(password, salt)` locally and sends only the verifier (never the raw password) over
TLS. The server stores the verifier and compares in constant time. The account credential is
completely separate from the vault MVK.

> The verifier scheme is a pragmatic reference design. A production deployment should use a
> real PAKE (OPAQUE/auCPace) and passkey (WebAuthn) on the relying party. See the app's
> documentation for the passkey plan.

## Database schema (SQL)

The reference server uses an in-memory store; a production deployment should use this schema
(PostgreSQL shown):

```sql
CREATE TABLE users (
    user_id    TEXT PRIMARY KEY,
    username   TEXT UNIQUE NOT NULL,
    verifier   BYTEA NOT NULL,          -- Argon2id verifier, never the raw password
    created_at BIGINT NOT NULL
);

CREATE TABLE sessions (
    session_token TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_id     TEXT NOT NULL,
    created_at    BIGINT NOT NULL,
    expires_at    BIGINT NOT NULL
);

CREATE TABLE devices (
    device_id    TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    platform     TEXT NOT NULL,
    created_at   BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    revoked      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE vault_objects (
    object_id      BIGSERIAL PRIMARY KEY,
    user_id        TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    vault_version  BIGINT NOT NULL,
    epoch          BIGINT NOT NULL,
    device_id      TEXT NOT NULL,
    created_at     BIGINT NOT NULL,
    records_sha256 TEXT NOT NULL,
    nonce          BYTEA NOT NULL,
    ciphertext     BYTEA NOT NULL,
    wrapped_mvk_recovery BYTEA NOT NULL,
    UNIQUE (user_id, vault_version)
);

CREATE TABLE enroll_tokens (
    ticket_id    TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    device_public_key BYTEA NOT NULL,
    server_public_key BYTEA NOT NULL,
    nonce        BYTEA NOT NULL,
    expires_at   BIGINT NOT NULL,
    used         BOOLEAN NOT NULL DEFAULT FALSE
);
```

None of these columns contain the MVK, recovery key, private keys or keystore passwords in
plaintext — only ciphertext and opaque metadata.

## Deployment

### Docker (recommended)

```bash
cd server
docker build -t signiffia-server .
docker run -p 8080:8080 signiffia-server
```

### HTTPS

WebAuthn (passkey) and any production deployment **require** HTTPS. Put the server behind a
TLS terminator (Caddy, nginx, or a cloud load balancer) with a valid certificate, or mount
your certificate into the Ktor server. The Android client refuses non-HTTPS URLs.

## Database backup

```bash
pg_dump "$DATABASE_URL" > signiffia-server-$(date +%F).sql
```

Backing up the database only ever backs up ciphertext and metadata — there is nothing in the
database that can decrypt a user's vault.

## Server recovery

1. Restore the database from a dump: `psql "$DATABASE_URL" < backup.sql`.
2. Start the server; users re-authenticate and re-download their (still encrypted) vault.
3. Because the server holds no plaintext, a server restore can never decrypt or corrupt a
   user's vault — the worst case is a stale vault version, which the client's version/epoch
   check rejects.

## Security configuration checklist

- [ ] Terminate TLS with a valid certificate; enforce HTTPS.
- [ ] Store only ciphertext; never log request bodies (they may contain ciphertext but never
      plaintext — still, do not log them).
- [ ] Constant-time comparison of the account verifier.
- [ ] Enforce single-use + short TTL on enrollment tickets.
- [ ] On device revocation, bump the vault epoch and reject requests from the revoked device.
- [ ] Rate-limit `/auth/login` to slow credential guessing.
- [ ] Keep the database on a volume with backups enabled.

## Known limitation

Revocation stops a device from fetching **future** vault updates, but it **cannot erase
secrets that the compromised device already downloaded**. This is documented to the user in
the app and here: end-to-end encryption protects data in transit and at rest on the server,
not data already on a lost/stolen device.
