# Signiffia 1.3.1 — Release Notes

## Šta je novo u 1.3.1

1.3.1 dovršava multi-device sistem iz 1.3.0 u **produkcijski** oblik i dodaje **passkey**
prijavu.

### Produkcijski sync server

- **PostgreSQL** trajno skladištenje (JDBC + HikariCP) umesto in-memory demonstratora.
- **Verzionisane SQL migracije** — nadogradnja servera ne gubi podatke.
- `docker-compose.yml` (server + PostgreSQL) i kompletna dokumentacija za deployment, HTTPS,
  rate limiting, backup/restore i nadogradnju baze.
- **Session tokeni se čuvaju kao SHA-256 hash** — curenje baze ne otkriva bearer tokene.
- **Rate limiting** na prijavi i registraciji.
- **Stroga monotonost verzije i epoche trezora** — odbija zastarele/reproducirane upload-e.

### Passkey / WebAuthn (opciono)

- **Passkey prijava** (WebAuthn relying party preko webauthn4j, Apache-2.0) pored lozinke.
- **Lozinka ostaje uvek dostupan fallback.**
- Passkey je **samo autentifikacija** — nikada ne dešifruje trezor i ne zamenjuje ključ za
  oporavak.
- Android klijent koristi `androidx.credentials` (Android 14+ / Play Services).

### Bezbednost

- Popravljena sinhronizacija epoche: klijent uvek čita server-autoritativnu verziju/epohu pre
  upload-a, čime se sprečava pokvaren AES-GCM AAD usled tihog podizanja epoche na serveru.
- WebAuthn izazovi su jednokratni i kratkoživeći; origin se eksplicitno proverava.
- Server i dalje čuva **samo šifrovane podatke i metapodatke** — nikada MVK, ključ za
  oporavak, privatne ključeve ni lozinke keystore-a.

### Testovi

- **118 unit testova aplikacije** (0 padova, 1 iskreno preskočen integracioni).
- **26 server testova** (0 padova) — auth, vault, epoch/revoke, enrollment, rate limiting,
  WebAuthn logika.

### Passkey — ograničenja

- End-to-end passkey prijava zahteva **deployovan HTTPS relying party** i **fizički uređaj**
  (Android 14+ / Play Services). Backend ceremonija je implementirana i testirana na nivou
  logike; kriptografska verifikacija je delegirana webauthn4j (FIDO-konforman).

### APK

- Paket: `dev.signiffia` · versionCode 5 · versionName 1.3.1 · minSdk 26 / targetSdk 36.
- Dozvole: `USE_BIOMETRIC`, `USE_FINGERPRINT` (normal) + `INTERNET` (normal, samo za opcionu
  sinhronizaciju). **Nema opasnih dozvola.**
- Potpis: isti Signiffia release ključ kao 1.1.0/1.2.0/1.3.0 → redovna in-place nadogradnja.
