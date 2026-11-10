# Izveštaj o testiranju — Signiffia 1.0.0

Datum: 2026-11-10
Okruženje: Linux (PocketDev), JDK 17.0.20.1, Gradle 8.14.3, Android SDK 36,
build-tools 35.0.0 (ARM64).

## Kako je testirano

```bash
# Svi JVM unit testovi (uključuju potpisivanje stvarnog, Gradle-om napravljenog APK-a)
SIGNIFFIA_REAL_APK=$PWD/app/build/outputs/apk/release/app-release.apk \
  gradle :app:testDebugUnitTest

# Nezavisna provera potpisa zvaničnim alatom
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Testovi koriste stvarne kriptografske operacije (bez mock-ova): generišu RSA/EC ključeve,
potpisuju APK i AAB, proveravaju potpise apksig-om, prave i otvaraju šifrovane backup-e.

## Rezultat

**40 testova, 0 padova, 0 preskočenih.**

| Test klasa | Testova | Vreme | Šta pokriva |
|---|---:|---:|---|
| `KeystoreToolsTest` | 9 | 5.9 s | generisanje RSA/EC ključa, ponovno otvaranje, JKS/PKCS12, pogrešna lozinka, oštećen fajl, neusklađen ključ/sertifikat |
| `SigningEngineTest` | 12 | 10.1 s | potpisivanje v1/v2/v3/v4, provera, tamper, AAB JAR potpis, stvarni APK |
| `KeyRepositoryTest` | 10 | 10.0 s | skladištenje ključeva, šifrovanje lozinki, uvoz, izvoz, brisanje, backup/restore |
| `BackupManagerTest` | 6 | 11.7 s | AES-GCM backup round-trip, pogrešna lozinka, oštećenje, fingerprint mismatch |
| `SplitArchiveTest` | 3 | 5.6 s | potpisivanje APKS/XAPK/APKM delova, očuvanje metapodataka, greška dela |

Ukupno vreme izvršavanja: ~45 s (bez kompilacije).

## Spisak izvršenih testova

### KeystoreToolsTest

- generated RSA keystore can be reopened and the key matches the certificate
- generated EC keystore works and uses a P-256 curve
- wrong password gives a clear error and never corrupts the file
- JKS keystores are read and written by the built-in JKS provider
- PKCS12 type is detected from the header
- keystore with a mismatched key is rejected
- generation validates the input
- verifyKeystore checks the expected fingerprint
- corrupt keystore file is rejected without crashing

### SigningEngineTest

- signs an APK with v1 v2 and v3 and verification passes
- v2 and v3 only signing verifies on Android 7+
- v1 only signing verifies for older platforms
- v3 without v2 is upgraded automatically
- v4 signing writes an idsig file that verifies
- the original APK is never modified
- tampered APK fails verification instead of being reported as valid
- unsigned APK is reported as unsigned
- garbage input fails with a clear message
- output must differ from input
- AAB gets a JAR v1 signature and no v2 block
- **signing a real built APK produces a Play and device installable signature**

### KeyRepositoryTest

- generated key is stored encrypted and can sign
- imported PKCS12 and JKS keystores are usable
- import with a wrong password stores nothing
- delete removes the file and the record
- rename keeps the same key material
- a lost device key locks passwords but never deletes the keystore
- backup and restore move keys to a fresh install
- restoring the same backup twice does not overwrite the first copy
- export writes a readable keystore file
- repository index survives a reopen

### BackupManagerTest

- backup roundtrip restores usable keystores
- wrong backup password is rejected
- corrupted backup is rejected
- backup with a fingerprint mismatch is rejected
- short passphrase is refused
- random file is not detected as a backup

### SplitArchiveTest

- all APK parts are signed with the same key and other files are preserved
- signed parts differ from the unsigned ones
- archive with a broken part fails without leaving an output

## Nezavisna provera (interoperabilnost)

1. **Objavljeni release APK** (`app-release.apk`, potpisan projektnim release ključem)
   proveren zvaničnim `apksigner`:

   ```
   Verifies
   Verified using v1 scheme (JAR signing): false   (nije potreban za minSdk 26)
   Verified using v2 scheme (APK Signature Scheme v2): true
   Verified using v3 scheme (APK Signature Scheme v3): true
   ```

   Sa `--min-sdk-version 18` apksigner potvrđuje i v1 potpis: `true`.

2. **APK potpisan Signiffia engine-om** (test `signing a real built APK...`): uzet je
   stvarni Gradle release APK (9,6 MB, 153 fajla), potpisan kroz `SigningEngine` u testu,
   a zatim proveren zvaničnim `apksigner`:

   ```
   Verifies
   Verified using v1 scheme (JAR signing): true   (--min-sdk-version 18)
   Verified using v2 scheme (APK Signature Scheme v2): true
   Verified using v3 scheme (APK Signature Scheme v3): true
   Signer #1 certificate DN: C=RS, O=Signiffia, CN=Signiffia Test
   ```

3. **AAB potpisan engine-om** proveren JDK `jarsigner -verify`: `jar verified.`
   (`META-INF/MANIFEST.MF` + `.SF` + `.RSA`), bez APK Signing Block-a.

4. **Manifest objavljenog APK-a**: `aapt2 dump badging` — 0 `uses-permission` zapisa,
   paket `dev.signiffia`, verzija `1.0.0` (versionCode 1), minSdk 26, targetSdk 36.

## Šta nije testirano u ovom okruženju

- **Instalacija i rad interfejsa na fizičkom uređaju / emulatoru.** Okruženje nema
  `adb` niti `pm` pristup, pa UI nije pokrenut na uređaju. APK je proveren alatima
  (`apksigner`, `aapt2`), ali prvo pokretanje treba potvrditi na telefonu.
- **Upload AAB-a na Google Play** (nije izvedeno; potreban je Play nalog).
- **Potpisivanje APK-a iz Flutter/React Native projekata** — engine je isti apksig koji
  koriste svi alati, a test sa stvarnim Gradle APK-om pokriva realan format APK-a.
- **Rad bez interneta na uređaju** — aplikacija nema INTERNET dozvolu, pa mrežni pristup
  nije moguć na nivou Android manifesta; to je provereno kroz `aapt2 dump badging`.
