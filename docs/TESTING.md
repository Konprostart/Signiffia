# Izveštaj o testiranju — Signiffia 1.3.0

## Okruženje

- Linux (PocketDev, ARM64), JDK 17.0.20.1, Gradle 8.14.3, Android SDK 36,
  build-tools 35.0.0 (ARM64).
- JVM unit testovi u `app/src/test/` — bez Android emulatora.

## Kako je testirano

```bash
# Svi JVM unit testovi
gradle :app:testDebugUnitTest

# Integracioni test sa stvarnim, Gradle-om napravljenim APK-om (opciono)
SIGNIFFIA_REAL_APK=$PWD/app/build/outputs/apk/release/app-release.apk \
  gradle :app:testDebugUnitTest

# Nezavisna provera potpisa zvaničnim alatom
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Testovi koriste stvarne kriptografske operacije (bez mock-ova): generišu RSA/EC ključeve,
potpisuju APK i AAB, proveravaju potpise apksig-om, prave i otvaraju šifrovane backup-e.

## Rezultat

**117 testova, 0 padova, 1 preskočen.** Preskočen je isključivo integracioni test koji
zahteva stvarni, Gradle-om napravljen APK (`SIGNIFFIA_REAL_APK`); kada se ta promenljiva
postavi, test se zaista izvršava i proverava potpis. Nijedan test koji ništa ne proverava
ne izgleda kao „uspešan“.

| Test klasa | Šta pokriva |
|---|---|
| `KeystoreToolsTest` | generisanje RSA/EC ključa, ponovno otvaranje, JKS/PKCS12, pogrešna lozinka, oštećen fajl, neusklađen ključ/sertifikat |
| `KeystoreSizesTest` | sve veličine ključeva: RSA 2048/3072/4096 i EC P-256/P-384/P-521 (generisanje + potpisivanje) |
| `SigningEngineTest` | potpisivanje v1/v2/v3/v4, provera, tamper, AAB JAR potpis, veliki APK, veliki AAB član, stvarni APK (integraciono) |
| `KeyRepositoryTest` | skladištenje ključeva, šifrovanje lozinki, uvoz, izvoz, brisanje, backup/restore, **multi-alias sa različitim lozinkama** |
| `BackupManagerTest` | AES-GCM backup round-trip, pogrešna lozinka, oštećenje, fingerprint mismatch, neispravan fajl |
| `SplitArchiveTest` | potpisivanje APKS/XAPK/APKM delova, očuvanje metapodataka, greška dela, **path traversal**, **previše stavki** |
| `LockModelTest` | čista logika zaključavanja: režimi, odgode (0/30 s/1 min/5 min), `shouldRelockOnForeground` granični slučajevi, serijalizacija za perzistenciju |
| `VaultCryptoTest` | AES-GCM round-trip, jedinstven nonce, tamper, pogrešan ključ, AAD vezivanje, wrap/unwrap, generisanje ključa |
| `VaultCodecTest` | kodiranje/dekodiranje trezora, očuvanje fingerprinta, multi-alias, oštećen/neispravan zapis, rollback/revoke guard |
| `VaultMigrationTest` | **end-to-end oporavak**: seal trezor → izgubljeni svi uređaji → ključ za oporavak → restore → isti fingerprint → potpisivanje APK-a |
| `RecoveryKeyTest` | **BIP39 zvanični test vektori** (entropija→mnemonic→seed), checksum, nepoznata reč, neispravan broj reči, wrap MVK-a |
| `EnrollmentTokenTest` | jednokratnost, istek, replay odbijanje, jedinstvenost tokena |
| `EphemeralKeyExchangeTest` | ECDH zajednička tajna, kodiranje ključa, derivacija wrapping ključa, potvrdni kod, wrap MVK-a |
| `PasswordKdfTest` | Argon2id determinizam, različite lozinke/saltovi, 32-bajtni izlaz, constant-time provera |
| `SyncClientTest` | wire protokol (registracija, prijava, upload/download trezora, uređaji, revoke, enrollment) sa lažnim transportom |

## Šta je pokriveno po zahtevima

- **RSA 2048 / 3072 / 4096** — `KeystoreSizesTest` (generisanje + potpisivanje svakog).
- **EC P-256 / P-384 / P-521** — `KeystoreSizesTest` (generisanje + potpisivanje svakog).
- **Različite lozinke po alijasu** — `KeyRepositoryTest.multi-alias keystore with a
  different password per alias…`: pravi JKS sa dva privatna ključa zaštićena različitim
  lozinkama, uvozi ga i proverava da se **oba** alijasa mogu koristiti za potpisivanje.
- **Veliki fajlovi** — `SigningEngineTest` potpisuje ~8 MiB APK i proverava JAR/AAB sa
  ~4 MiB članom; obrada je streaming.
- **Neispravne / zlonamerne arhive** — `SplitArchiveTest` odbija path traversal (`..`) i
  arhivu sa preko 10 000 stavki; `BackupManagerTest` odbija oštećene i neispravne backup-e.
- **Oštećeni fajlovi i pogrešne lozinke** — `KeystoreToolsTest`, `BackupManagerTest`,
  `SigningEngineTest` (tamper).
- **Backup i restore na drugom uređaju** — `KeyRepositoryTest` simulira novi uređaj
  (`FakeCipher` sa drugim ključem) i vraća ključeve iz backup-a.

## Preskočeni testovi

- `SigningEngineTest.signing a real built APK produces a Play and device installable
  signature` — **preskače se** (JUnit `Assume`) kada `SIGNIFFIA_REAL_APK` nije postavljen.
  To je svesno: test ne sme da izgleda uspešan ako ništa nije proverio. Postavi
  `SIGNIFFIA_REAL_APK` na stvarni APK da bi se izvršio.

## Nezavisna provera (interoperabilnost)

1. **Objavljeni release APK** proveren zvaničnim `apksigner`: v1/v2/v3 ispravni.
2. **APK potpisan Signiffia engine-om** proveren zvaničnim `apksigner` (`--min-sdk-version 18`).
3. **AAB potpisan engine-om** proveren JDK `jarsigner -verify` (bez APK Signing Block-a).
4. **Manifest objavljenog APK-a**: `aapt2 dump badging` — paket `dev.signiffia`, minSdk 26,
   targetSdk 36; dve `normal` biometrijske dozvole (`USE_BIOMETRIC`, `USE_FINGERPRINT`),
   bez opasnih dozvola i bez INTERNET-a.

## Šta NIJE testirano u ovom okruženju

- **Instalacija i rad interfejsa na fizičkom uređaju / emulatoru.** Okruženje nema
  `adb` niti `pm`, pa UI nije pokrenut na uređaju. U nastavku je ručna provera za telefon.
- **Biometrija i PIN/šifra/šara uređaja (zaključavanje aplikacije).** `BiometricPrompt` i
  `KeyguardManager` potvrda ne mogu se izvršiti na JVM-u niti u ovom okruženju (bez uređaja).
  Automatski je testirana samo **čista logika** odluke o zaključavanju (`LockModelTest`);
  sam sistemski dijalog zahteva ručnu proveru na uređaju (checklist u nastavku).
- **Upload AAB-a na Google Play** (potreban Play nalog).
- **bundletool validacija AAB-a.** Signiffia namerno ne uključuje bundletool: JAR (v1)
  potpis je format koji Play prihvata za upload, a dodavanje bundletool-a bi značajno
  povećalo APK bez koristi za sam čin potpisivanja.
- **Multi-device sinhronizacija na stvarnom serveru i dva uređaja.** Kriptografska logika
  trezora/oporavka/uparivanja je automatski testirana na JVM-u, ali stvarni tok
  „prvi telefon → server → drugi telefon" zahteva deployovan sync server i dva fizička
  uređaja. Referentni server je u `server/` i **nije** deo ovog testnog okruženja.
- **Passkey (WebAuthn).** Nije uključen u 1.3.0 (zahteva `androidx.credentials` + Play
  Services + WebAuthn relying party); radi **lozinka naloga** kao fallback.
- **Biometrijski/PIN ključ uređaja (`AndroidDeviceKey`).** Ključ uređaja koristi
  `setUserAuthenticationRequired(true)`, što se može potvrditi samo na pravom uređaju.

## Ručna provera na Android telefonu (checklist)

Izvrši na pravom uređaju (Android 8.0+):

1. Instaliraj `app-debug.apk` (ili release) i pokreni aplikaciju.
2. **Ključevi → Novi ključ** — napravi RSA 2048 i EC P-256 ključ; proveri da se pojavljuju
   u listi i da je fingerprint prikazan.
3. **Ključevi → Uvezi keystore** — uvezi postojeći `.jks`/`.p12`; proveri multi-alias
   keystore sa različitim lozinkama po alijasu.
4. **Potpiši APK ili AAB** — izaberi APK, potpiši (v1+v2+v3), sačuvaj; instaliraj
   potpisani APK i pokreni ga.
5. **Potpiši APK ili AAB** sa `.aab` — proveri da je rezultat JAR (v1) potpisan.
6. **Proveri potpis** — proveri potpisani APK i namerno izmenjen APK (mora prijaviti grešku).
7. **Uporedi potpise** — uporedi dve verzije istog APK-a (isti ključ) i APK potpisan
   drugim ključem (mora upozoriti).
8. **Backup i vraćanje** — napravi `.sgnbak`, deinstaliraj aplikaciju, ponovo instaliraj,
   vrati backup i proveri da su ključevi dostupni i upotrebljivi.
9. **Veliki fajl** — potpiši APK od nekoliko stotina MB i proveri da aplikacija ne padne
   (prikazuje jasnu grešku ako je fajl prevelik).
10. **Offline** — isključi Wi-Fi i mobilne podatke; aplikacija mora raditi identično.
11. **Zaključavanje → PIN uređaja** — uključi režim „PIN / šifra / šara uređaja“, napusti
    aplikaciju i vrati se: mora tražiti potvrdu identiteta. Proveri odgode (odmah / 30 s /
    1 min / 5 min) i da se posle ponovnog pokretanja aplikacija otvara zaključana.
12. **Zaključavanje → biometrija + PIN rezerva** — na uređaju sa otiskom/licem proveri brzo
    otključavanje i da PIN radi kao rezerva; na uređaju bez biometrije proveri da se koristi
    PIN. Na Androidu 8.0/8.1, 9, 10 i 11+ proveri da nema pada.
13. **SAF birač fajlova** — dok je zaključavanje uključeno sa odgodom „odmah“, izaberi fajl u
    Potpiši / Proveri / Uvezi: aplikacija se ne sme zaključati odmah po povratku iz birača.
14. **Pregled nedavnih aplikacija** — sa uključenim zaključavanjem proveri da je sadržaj
    Signiffia skriven (crn/prazan snimak) i da slikanje ekrana nije moguće.
15. **Uređaj bez ekrana zaključavanja** — ukloni PIN u Android podešavanjima; proveri da je
    aktiviranje zaštite onemogućeno uz uputstvo, i da zaključana aplikacija nudi bezbedan
    oporavak (isključi zaključavanje).
16. **Nalog i sinhronizacija — prvi uređaj** — kreiraj nalog (HTTPS server), napravi ključ za
    oporavak (24 reči), sinhronizuj. Proveri da server prima samo šifrovane podatke.
17. **Nalog i sinhronizacija — novi uređaj** — prijavi se, vrati trezor ključem za oporavak;
    proveri da su ključevi dostupni sa **istim** fingerprintom i da mogu da potpišu APK.
18. **Upravljanje uređajima** — lista uređaja, povlačenje izgubljenog uređaja; proveri da
    povučeni uređaj više ne može da preuzme trezor.
19. **Pogrešan ključ za oporavak** — unos pogrešnih reči mora biti odbijen bez gubitka
    postojećih ključeva.

## Release build provera

- `assembleRelease` **bez** kredencijala mora **pasti** sa jasnom greškom (nikada ne
  potpisuje debug ključem).
- `assembleRelease` **sa** kredencijalima pravi release APK; proveri zvaničnim
  `apksigner verify --print-certs` da potpisnik NIJE debug ključ (`CN=Android Debug`).
