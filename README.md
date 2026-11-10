# Signiffia

**Potpisivanje i provera Android aplikacija direktno na telefonu.**

Signiffia je samostalna Android aplikacija koja potpisuje i proverava APK i AAB fajlove bez
računara. Signing engine je zvanična Android **apksig** biblioteka — nema sopstvene
kriptografije i nema cloud servisa.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green.svg)
![Offline](https://img.shields.io/badge/offline-100%25-success.svg)

---

## Sadržaj

- [Šta radi](#šta-radi)
- [Instalacija](#instalacija)
- [Korišćenje](#korišćenje)
- [Bezbednost ključeva](#bezbednost-ključeva)
- [Tehnologije](#tehnologije)
- [Izrada iz izvornog koda](#izrada-iz-izvornog-koda)
- [Testiranje](#testiranje)
- [Struktura projekta](#struktura-projekta)
- [Licence](#licence)
- [Ograničenja](#ograničenja)

---

## Šta radi

### Potpisivanje

- Potpisivanje **APK** fajlova bez obzira čime su napravljeni (Android Studio/Gradle, Kotlin,
  Flutter, React Native, WebToApp, iappyxOS ili bilo koji drugi validan APK).
- Potpisivanje **AAB** (Android App Bundle) fajlova ispravnim JAR (v1) potpisom za Google Play upload.
- Potpisivanje **split arhiva** (APKS/XAPK/APKM): svaki APK deo se potpisuje istim ključem,
  a ostali fajlovi arhive ostaju nepromenjeni.
- Potpisne šeme: **v1, v2, v3, v3.1** (kada je primenljivo) i **v4** kao dodatna opcija
  (pravi `.idsig` fajl za inkrementalnu instalaciju).
- Originalni fajl se **nikada ne menja**. Potpisuje se kopija, a rezultat se čuva tamo gde
  korisnik izabere (Storage Access Framework).
- Posle potpisivanja potpis se **odmah proverava**; ako provera ne prođe, izlazni fajl se
  briše i korisnik dobija jasnu grešku.

### Provera

- Provera potpisa APK-a (v1/v2/v3/v3.1/v4), AAB-a (JAR) i svakog dela split arhive.
- Prikaz sertifikata: subject, izdavalac, algoritam, veličina ključa, period važenja.
- **SHA-256 i SHA-1 fingerprint** sertifikata (dodir za kopiranje).
- Detekcija split arhiva i potpisivanje svih APK delova istim ključem.
- Detekcija izmenjenog APK-a, nepotpisanog APK-a i oštećenih fajlova.
- Prikaz grešaka i upozorenja iz apksig-a na razumljiv način.

### Ključevi

- Kreiranje novog potpisnog ključa: RSA 2048/3072/4096 ili EC P-256/P-384, sa sopstvenim
  subject podacima i periodom važenja.
- Uvoz postojećih **JKS** i **PKCS12** keystore fajlova (i keystore-a sa više alijasa).
- Izbor ključa i alijasa za potpisivanje.
- Izvoz keystore fajla (standardni `.p12` / `.jks`).
- Šifrovan **backup** svih ili pojedinih ključeva i **restore** na drugi uređaj.
- Provera backup-a pre vraćanja (keystore se otvara i fingerprint se poredi).
- Prikaz fingerprinta i upozorenje pre brisanja ključa.

### Ograničenja

- Zipalign se ne menja: Signiffia čuva poravnanje koje APK već ima.

---

## Instalacija

1. Otvori **[Releases](../../releases)** stranicu repozitorijuma.
2. Preuzmi `Signiffia-<verzija>.apk`.
3. Otvori preuzeti fajl na telefonu.
4. Ako Android prijavi da je instalacija blokirana, dozvoli instalaciju iz tog izvora
   (*Podešavanja → Aplikacije → Pristup posebnim dozvolama → Instalacija nepoznatih
   aplikacija*), pa potvrdi.
5. Pokreni Signiffia.

Potrebno je **Android 8.0 (API 26) ili novije**.

> Aplikacija nema nijednu dozvolu — ni INTERNET. Fajlove biraš isključivo kroz sistemski
> birač fajlova, koji daje pristup samo izabranom fajlu.

---

## Korišćenje

### Prvi put: napravi ili uvezi ključ

1. **Ključevi → Novi ključ**.
2. Unesi naziv, alijas (npr. `release`), tip ključa i podatke sertifikata.
3. Postavi lozinku (minimum 8 karaktera) i **zapamti je** — bez nje ključ ne može da se koristi.
4. Odmah napravi backup: **Ključevi → (ključ) → Napravi šifrovani backup**.

Ako već imaš keystore: **Ključevi → Uvezi keystore**, izaberi `.jks`/`.p12` i unesi lozinku.
Originalni fajl ostaje netaknut.

### Potpisivanje APK-a

1. **Potpiši APK ili AAB → Izaberi APK**.
2. Proveri informacije o APK-u (paket, verzija, min/target SDK, trenutni potpis).
3. Izaberi ključ (i alijas, ako keystore ima više njih).
4. Ostavi uključene **v2** i **v3** (preporučeno). Uključi **v1** ako APK treba da radi na
   Androidu 6 i starijem.
5. **Potpiši APK i sačuvaj** → izaberi gde da se sačuva potpisani fajl.
6. Signiffia potpisuje, proverava potpis i prikazuje rezultat uključujući SHA-256 fajla i
   fingerprint sertifikata.

Ako je uključen **v4**, posle čuvanja APK-a možeš sačuvati i prateći `.idsig` fajl.

### Provera potpisa

**Proveri potpis → Izaberi APK ili AAB** — rezultat pokazuje da li je potpis ispravan, koje
šeme su prisutne, ko je potpisnik i koji je SHA-256 fingerprint.

### Split arhive (APKS / XAPK / APKM)

Izaberi arhivu u **Potpiši APK ili AAB**. Signiffia prikaže sve APK delove, potpiše svaki
istim ključem i sačuva novu arhivu sa istom ekstenzijom. `manifest.json`, ikone, OBB i drugi
fajlovi ostaju netaknuti. Provera prikazuje potpis svakog dela i upozorava ako se potpisnici
razlikuju.

### AAB za Google Play

**Potpiši APK ili AAB → izaberi `.aab`** → Signiffia automatski koristi JAR (v1) potpis, koji
Google Play prihvata za upload. AAB se ne može instalirati na telefon.

### Backup i vraćanje

- **Backup i vraćanje → Napravi backup**: izaberi ključeve, unesi backup lozinku (min. 8
  karaktera), sačuvaj `.sgnbak` fajl. Fajl je šifrovan AES-256-GCM.
- **Vrati iz backup-a**: izaberi `.sgnbak`, unesi lozinku. Signiffia prvo proveri svaki
  keystore i fingerprint, prikaže sadržaj, pa tek onda vraća ključeve.

---

## Bezbednost ključeva

- Keystore fajlovi su u privatnom skladištu aplikacije (`files/keys/`), nedostupni drugim
  aplikacijama.
- Lozinke keystore-a se **nikada ne čuvaju u običnom tekstu**. Šifrovane su AES-256-GCM
  ključem iz **Android Keystore-a** (hardverski gde uređaj to podržava).
- Aplikacija **nema INTERNET dozvolu**, tako da ključevi ne mogu da napuste uređaj.
- Nema logovanja lozinki, nema analitike, nema reklama, nema naloga.
- Neispravan ključ se **nikada ne briše niti zamenjuje** automatski. Brisanje je moguće samo
  uz eksplicitnu potvrdu korisnika.
- Backup je dodatno šifrovan lozinkom koju korisnik izabere (PBKDF2-HMAC-SHA256, 210 000
  iteracija). Lozinke ključeva su unutar šifrovanog dela backup-a.
- Ako se Android Keystore ključ izgubi (reinstalacija, novi uređaj), keystore fajlovi ostaju
  sačuvani, a lozinka se ponovo unosi (**Ponovo unesi lozinku** u detaljima ključa).

> Signiffia ne pravi i ne čuva tvoj produkcioni ključ za druge aplikacije. Ključeve koje
> napraviš u aplikaciji ti kontrolišeš i izvoziš ih kad želiš.

---

## Tehnologije

| Komponenta | Verzija |
|---|---|
| Kotlin | 1.9.22 |
| Jetpack Compose (BOM) | 2024.02.00 |
| Material 3 | preko Compose BOM-a |
| Android Gradle Plugin | 8.11.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 (Android 8.0) |
| Java | 17 |
| Android apksig | 8.11.0 |
| BouncyCastle | 1.79 (`bcprov`, `bcpkix`, `bcutil`) |

Arhitektura:

- `core/` — čist JVM kod bez Android zavisnosti: apksig potpisivanje/provera, keystore
  generisanje i uvoz, šifrovani backup. Zbog toga je pokriven JVM unit testovima.
- `data/` — skladište ključeva i šifrovanje lozinki (Android Keystore).
- `ui/` — Jetpack Compose Material 3 interfejs i `AppViewModel`.

---

## Izrada iz izvornog koda

Potrebno: JDK 17, Android SDK sa platformom 36 i build-tools 35.0.0, Gradle 8.14.3.

```bash
# debug APK
./gradlew :app:assembleDebug

# unit testovi (40 testova)
./gradlew :app:testDebugUnitTest

# release APK (potpisuje se keystore-om iz signing/ ako postoje kredencijali)
export SIGNIFFIA_STORE_PASSWORD='...'
export SIGNIFFIA_KEY_ALIAS='signiffia'
export SIGNIFFIA_KEY_PASSWORD='...'
./gradlew :app:assembleRelease
```

`gradle` (bez wrappera) radi isto ako je Gradle 8.14.3 instaliran.

Izlaz: `app/build/outputs/apk/debug/app-debug.apk` i
`app/build/outputs/apk/release/app-release.apk`.

> Keystore za objavljivanje (`signing/signiffia-release.jks`) nije u repozitorijumu. Za svoj
> release napravi svoj ključ i postavi gornje promenljive okruženja.

---

## Testiranje

**Rezultat poslednjeg izvršavanja: 40 testova, 0 padova** (uključujući potpisivanje
stvarnog Gradle release APK-a i proveru zvaničnim `apksigner` alatom).

Testovi se izvršavaju na JVM-u (`app/src/test/`) i pokrivaju:

- generisanje RSA/EC ključa i ponovno otvaranje keystore-a;
- uvoz JKS i PKCS12 keystore-a, pogrešnu lozinku, oštećen fajl;
- potpisivanje v1/v2/v3 i v4 (+`.idsig`), proveru potpisa;
- detekciju izmenjenog APK-a (tamper) i nepotpisanog APK-a;
- da ulazni APK ostaje nepromenjen (SHA-256 pre i posle);
- AAB JAR potpis i odsustvo APK Signing Block-a u AAB-u;
- backup/restore round-trip, pogrešnu backup lozinku, oštećen backup, fingerprint mismatch;
- gubitak Android Keystore ključa i oporavak ponovnim unosom lozinke;
- da se keystore ne briše i ne regeneriše greškom.

```bash
gradle :app:testDebugUnitTest
```

Rezultati: `app/build/reports/tests/testDebugUnitTest/index.html`.

Detaljan izveštaj o izvršenim testovima je u [docs/TESTING.md](docs/TESTING.md).

---

## Struktura projekta

```
app/src/main/java/dev/signiffia/
├── MainActivity.kt          # ulazna tačka, Compose
├── SigniffiaApp.kt          # registracija BouncyCastle providera
├── core/
│   ├── CertUtils.kt         # fingerprint, subject, valjanost
│   ├── KeystoreTools.kt     # JKS/PKCS12, generisanje ključa, provera
│   ├── SigningEngine.kt     # apksig potpisivanje i provera (APK + AAB)
│   ├── SplitArchive.kt      # APKS/XAPK/APKM potpisivanje i provera po delovima
│   ├── JksKeyStore.kt       # JKS provider (Android nema JKS podršku)
│   └── BackupManager.kt     # AES-256-GCM backup kontejner
├── data/
│   ├── SecretCipher.kt      # interfejs za šifrovanje lozinki
│   ├── AndroidSecretCipher.kt # Android Keystore AES-GCM
│   └── KeyRepository.kt     # skladište keystore-a + metapodaci
└── ui/                      # Compose ekrani, tema, ViewModel
app/src/test/java/dev/signiffia/   # JVM unit testovi
docs/                              # dodatna dokumentacija
```

---

## Licence

Signiffia je objavljena pod **Apache License 2.0** — vidi [LICENSE](LICENSE).

Korišćene biblioteke:

- **Android apksig** — Apache-2.0 (glavni signing engine)
- **BouncyCastle** — Bouncy Castle Licence (MIT stil)
- **AndroidX / Jetpack Compose / Material 3** — Apache-2.0
- **Kotlin / kotlinx.coroutines** — Apache-2.0

Detalji i atribucija: [LICENSES.md](LICENSES.md).

Projekti korišćeni samo kao referenca (bez preuzetog koda): `apk-signer-pro` (Apache-2.0),
`web-to-app` (Unlicense), App Manager (GPL-3.0 — samo koncept, nijedan red GPL koda nije
preuzet).

---

## Ograničenja

- Ne menja `AndroidManifest.xml`, ne optimizuje niti kompresuje APK.
- Ako APK koristi `extractNativeLibs=false`, poravnanje native biblioteka se čuva onako kako
  je u ulaznom fajlu (Signiffia ne pomera sadržaj).
- AAB se proverava JAR proverom; kompletnu validaciju za Google Play (bundletool) Signiffia ne
  radi.

---

## Privatnost

Signiffia ne prikuplja podatke, nema mrežne komunikacije i ne zahteva nalog. Sve što radiš
ostaje na uređaju.
