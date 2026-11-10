# Changelog

Sve značajne promene u ovom projektu. Format prati [Keep a Changelog](https://keepachangelog.com/),
projekt se pridržava [Semantic Versioning](https://semver.org/).

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
