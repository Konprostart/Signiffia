# Changelog

Sve značajne promene u ovom projektu. Format prati [Keep a Changelog](https://keepachangelog.com/),
projekt se pridržava [Semantic Versioning](https://semver.org/).

## [1.2.0] — neobjavljeno

### Dodato

- **Opciono zaključavanje aplikacije** (podrazumevano isključeno):
  - **PIN / šifra / šara uređaja** — preko Android `KeyguardManager` potvrde (bez sopstvenog
    PIN sistema, bez čuvanja PIN-a).
  - **Biometrija (otisak prsta / bezbedno lice) sa PIN-om uređaja kao rezervom** — preko
    `androidx.biometric` `BiometricPrompt` (API 26+; na API 30+ `BIOMETRIC_STRONG |
    DEVICE_CREDENTIAL`, na starijim biometrija + zaseban PIN korak radi izbegavanja poznatog
    pada na Androidu 9/10).
  - Ekran podešavanja (`Zaključavanje`) i ekran za otključavanje.
- Automatsko zaključavanje pri vraćanju iz pozadine, sa odgodom: odmah / 30 s / 1 min / 5 min.
- `FLAG_SECURE` (skrivanje sadržaja u pregledu nedavnih aplikacija i onemogućavanje slika
  ekrana) kada je zaključavanje uključeno.
- Podešavanja se čuvaju u `SharedPreferences` i opstaju nakon ponovnog pokretanja.
- Kada uređaj nema podešen PIN/šifru/šaru, aktiviranje zaštite je onemogućeno uz jasno
  uputstvo; bezbedan oporavak ako korisnik kasnije ukloni ekran zaključavanja.
- JVM unit testovi za čistu logiku zaključavanja (`LockModelTest`).

### Bezbednost

- Biometrijski podaci se obrađuju isključivo na uređaju (TEE/StrongBox); Signiffia ih nikada
  ne dobija, već samo potvrdu uspeha/neuspeha.
- PIN/šifra/šara se nikada ne čuva u aplikaciji — koristi se sistemski ekran za potvrdu.
- Zaštita od beskonačnih petlji zaključavanja i od pogrešnog zaključavanja nakon SAF birača
  fajlova (`AppLockGate`).
- Zaključavanje ne menja postojeći kripto sistem (AES-256-GCM + Android Keystore) niti
  format podataka.

### Zavisnosti

- Dodate dve Apache-2.0 zavisnosti: `androidx.biometric:biometric:1.1.0` i
  `androidx.fragment:fragment:1.6.2`. Dve `normal`-dozvole (`USE_BIOMETRIC`,
  `USE_FINGERPRINT`); i dalje **nema** opasnih dozvola, INTERNET-a ni mrežnog koda.

## [1.1.0] — neobjavljeno

> **Napomena o potpisu:** verzija 1.1.0 je potpisana **novim** release ključem (originalni
> ključ iz 1.0.0 više nije dostupan). Zbog toga se 1.1.0 **ne može instalirati kao nadogradnja
> preko 1.0.0** — sertifikati su različiti i nema `signing certificate lineage` (v3.1).

### Dodato

- Poređenje potpisa dve verzije (APK/AAB): provera da li koriste isti ključ i da li je
  nova verzija kompatibilna kao nadogradnja.
- Podrška za **različite lozinke po alijasu** pri uvozu i ponovnom otključavanju
  multi-alias keystore-a (JKS/PKCS12).

### Bezbednost

- Release build **nikada ne koristi debug ključ**: bez eksplicitnih kredencijala
  `assembleRelease` pada sa jasnom greškom.
- Zaštita od zlonamernih arhiva (ZIP bombe, prevelik broj stavki, path traversal) u
  `SplitArchive` i `BackupManager`.
- Streaming obrada velikih fajlova u split arhivama i pri JAR/AAB proveri (bez učitavanja
  celih fajlova u memoriju).
- Osetljivi privremeni fajlovi (potpisani izlazi, `.idsig`) se brišu odmah po predaji kroz
  SAF.

### Ispravljeno

- Netačna dokumentacija o oporavku ključeva nakon reinstalacije (keystore fajlovi NE
  preživljavaju deinstalaciju; `.sgnbak`/izvoz je jedini pouzdan oporavak).
- Netačna tvrdnja o podršci za v3.1 (rotacija ključa se proverava, ali se ne generiše).
- Integracioni test „real APK“ se sada iskreno označava kao preskočen kada nema env
  promenljive (umesto tihog prolaza).

## [1.0.0] — 2026-11-10

### Dodato

- Potpisivanje APK-a zvaničnom Android **apksig** bibliotekom (v1, v2, v3, v3.1, v4).
- Automatska provera potpisa nakon potpisivanja; neispravan izlaz se briše.
- Provera potpisa APK-a sa prikazom potpisnika, šema, grešaka i upozorenja.
- Prikaz SHA-256 i SHA-1 fingerprinta sertifikata (dodir za kopiranje).
- Kreiranje novih potpisnih ključeva (RSA 2048/3072/4096, EC P-256/P-384) sa
  sopstvenim subject podacima.
- Uvoz JKS i PKCS12 keystore-a; sopstveni JKS provider (Android nema JKS podršku).
- Izvoz keystore fajla i šifrovani backup / restore svih ili pojedinih ključeva.
- Lozinke keystore-a šifrovane AES-256-GCM ključem iz Android Keystore-a.
- Oporavak kada Android Keystore ključ više nije dostupan (ponovni unos lozinke).
- AAB potpisivanje JAR (v1) potpisom za Google Play upload i JAR provera.
- Split arhive (APKS/XAPK/APKM): potpisivanje svih APK delova istim ključem i provera
  svakog dela.
- Potpuno offline rad, bez INTERNET dozvole, bez reklama i praćenja.
- JVM unit testovi za kriptografiju, keystore, potpisivanje, verifikaciju, backup i
  split arhive.

### Bezbednost

- Originalni fajl se nikada ne menja; potpisuje se kopija.
- Postojeći ključ se nikada ne briše niti regeneriše zbog greške.
- Lozinke se ne loguju i ne napuštaju uređaj.
- Backup je zaštićen PBKDF2-HMAC-SHA256 (210 000 iteracija) + AES-256-GCM.
