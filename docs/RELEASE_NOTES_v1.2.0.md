# Signiffia 1.2.0

Samostalna, offline Android aplikacija za potpisivanje i proveru APK/AAB fajlova i
upravljanje potpisnim ključevima.

## Šta je novo u odnosu na 1.1.0

- **Opciono zaključavanje aplikacije** (podrazumevano isključeno), podesivo u
  **Zaključavanje** na početnom ekranu:
  - **PIN / šifra / šara uređaja** — preko Android `KeyguardManager` potvrde; Signiffia
    nikada ne čuva PIN.
  - **Otisak prsta / lice + PIN kao rezerva** — preko `androidx.biometric` `BiometricPrompt`;
    biometrijski podaci se obrađuju isključivo na uređaju.
- **Automatsko zaključavanje** pri vraćanju iz pozadine, sa odgodom: odmah / 30 s / 1 min /
  5 min (podrazumevano 30 s).
- **`FLAG_SECURE`** — sadržaj se skriva u pregledu nedavnih aplikacija i onemogućava slike
  ekrana dok je zaključavanje uključeno.
- Podešavanja se čuvaju u `SharedPreferences` i opstaju nakon ponovnog pokretanja.
- Ispravno ponašanje uz SAF birač fajlova i sistemski PIN/biometrijski dijalog (bez
  pogrešnog zaključavanja i bez beskonačnih petlji).
- Jasno uputstvo i bezbedan oporavak kada uređaj nema podešen PIN/šifru/šaru.
- JVM unit testovi za čistu logiku zaključavanja (`LockModelTest`); ukupno 57 testova.

## Potpisni ključ

1.2.0 nastavlja da koristi **isti** release ključ kao 1.1.0 (novi trajni release ključ).

```
Cert SHA-256: 2dcdcf1b29b1daae924d67a36f9e0f4858dce96cdd4ce3b295a9b9b5f5c6a34c
DN:           CN=Signiffia, O=Signiffia Open Source, C=RS
```

> Napomena o kompatibilnosti sa 1.0.0 i dalje važi: 1.0.0 je potpisana drugim (izgubljenim)
> ključem, pa ni 1.1.0 ni 1.2.0 nisu in-place nadogradnja preko 1.0.0. Nadogradnja 1.1.0 →
> 1.2.0 koristi **isti** ključ, pa je redovna in-place nadogradnja moguća.

## Dozvole i zavisnosti

- **Dve nove Apache-2.0 zavisnosti:** `androidx.biometric:biometric:1.1.0` i
  `androidx.fragment:fragment:1.6.2`.
- **Dve `normal` dozvole:** `USE_BIOMETRIC` i `USE_FINGERPRINT`. I dalje **nema opasnih
  dozvola**, nema INTERNET-a i nema mrežnog koda.

## Bezbednost

- Zaključavanje ne menja postojeći kripto sistem (AES-256-GCM + Android Keystore) niti
  format podataka/ključeva.
- PIN/šifra/šara i biometrijski podaci se nikada ne čuvaju u aplikaciji.
- Zaključavanje je zaštita **pristupa**, ne zamena za šifrovanje ključeva.

## Instalacija

1. Preuzmi `Signiffia-1.2.0.apk`.
2. Otvori fajl na telefonu i dozvoli instalaciju iz tog izvora ako Android zatraži.
3. Potreban je **Android 8.0 (API 26)** ili noviji.

## Provera integriteta (release APK)

Popuniti SHA-256 i veličinu nakon release build-a sa `SIGNIFFIA_*` kredencijalima
(vidi README → „Izrada iz izvornog koda“). Release build se **nikada** ne potpisuje debug
ključem.

## Poznato

- Biometrija i sistemski PIN dijalog nisu fizički testirani u build okruženju (nema `adb`);
  ručna provera je u [TESTING.md](TESTING.md).
- AAB se proverava JAR proverom; bundletool validacija se ne vrši.
- Rotacija ključa (v3.1 / proof-of-rotation) se proverava, ali se ne generiše.
