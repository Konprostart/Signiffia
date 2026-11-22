# Signiffia 1.1.0

Samostalna, offline Android aplikacija za potpisivanje i proveru APK/AAB fajlova i
upravljanje potpisnim ključevima.

## Napomena o potpisnom ključu (važno)

Verzija **1.1.0 je potpisana novim release ključem** — originalni ključ iz 1.0.0 više nije
dostupan. Sertifikat 1.0.0 i sertifikat 1.1.0 su **različiti** i ne postoji
`signing certificate lineage` (v3.1), pa se **1.1.0 ne može instalirati kao obična nadogradnja
preko 1.0.0** (Android će odbiti instalaciju zbog različitog sertifikata).

Pre instalacije 1.1.0 preko starije verzije: sačuvaj ključeve iz 1.0.0 (izvoz `.p12`/`.jks`
ili `.sgnbak` šifrovani backup), deinstaliraj 1.0.0, zatim sveže instaliraj 1.1.0 i vrati
ključeve.

Sertifikat 1.1.0 (novi trajni release ključ):

```
Cert SHA-256: 2dcdcf1b29b1daae924d67a36f9e0f4858dce96cdd4ce3b295a9b9b5f5c6a34c
DN:           CN=Signiffia, O=Signiffia Open Source, C=RS
```

## Šta je novo u odnosu na 1.0.0

- **Poređenje potpisa dve verzije** (APK/AAB): provera da li koriste isti potpisni ključ i
  da li je nova verzija kompatibilna kao nadogradnja postojeće.
- **Različite lozinke po alijasu** pri uvozu i ponovnom otključavanju multi-alias keystore-a.
- **Bezbedniji release build**: `assembleRelease` bez eksplicitnih kredencijala **pada** —
  nikada ne potpisuje release debug ključem.
- **Zaštita od zlonamernih arhiva** (ZIP bombe, previše stavki, path traversal) i
  **streaming** obrada velikih fajlova.
- Osetljivi privremeni fajlovi se brišu odmah po predaji.
- Ispravljena dokumentacija o reinstalaciji (keystore fajlovi NE preživljavaju
  deinstalaciju; `.sgnbak`/izvoz je jedini pouzdan oporavak) i o v3.1 (rotacija se
  proverava, ne generiše).

## Instalacija

1. Preuzmi `Signiffia-1.1.0.apk`.
2. Otvori fajl na telefonu i dozvoli instalaciju iz tog izvora ako Android zatraži.
3. Potreban je **Android 8.0 (API 26)** ili noviji.

Aplikacija **nema nijednu dozvolu** — ni INTERNET.

## Bezbednost

- Originalni APK/AAB se nikada ne menja.
- Postojeći ključ se nikada ne briše niti regeneriše zbog greške.
- Lozinke keystore-a šifrovane Android Keystore ključem (AES-256-GCM); nikada u običnom tekstu.
- Backup šifrovan lozinkom (PBKDF2-HMAC-SHA256 + AES-256-GCM).
- Nema reklama, analitike, naloga ni praćenja.

## Provera integriteta (release APK)

```
SHA-256: 539c42adb1d0e85d529ffbcf460efb5e594aae51f7054a8dc8d8053699e16a30
Size:    9685089 bytes
Signer:  CN=Signiffia, O=Signiffia Open Source, C=RS
Cert SHA-256: 2dcdcf1b29b1daae924d67a36f9e0f4858dce96cdd4ce3b295a9b9b5f5c6a34c
```

Provereno zvaničnim `apksigner`: **v2 i v3 potpisi ispravni** (v1 nije prisutan, što je
ispravno za `minSdk 26`). Release je potpisan produkcijskim ključem (NIKO debug ključ,
`CN=Android Debug`).

## Poznato

- Aplikacija nije pokrenuta na fizičkom uređaju u ovom okruženju (nema `adb`); ručna
  provera je u [TESTING.md](TESTING.md).
- AAB je proveren JAR proverom; bundletool validacija se ne vrši.
- Rotacija ključa (v3.1 / proof-of-rotation) se proverava, ali se ne generiše.
