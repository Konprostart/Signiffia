# Signiffia 1.0.0

Potpisivanje i provera Android aplikacija direktno na telefonu, potpuno offline.

## Šta je novo

- **APK potpisivanje** zvaničnim Android **apksig** engine-om: v1, v2, v3, v3.1, i v4 (opciono).
- **AAB potpisivanje** ispravnim JAR (v1) potpisom za Google Play upload.
- **Split arhive** (APKS / XAPK / APKM): svi APK delovi se potpisuju istim ključem, ostali
  fajlovi arhive ostaju nepromenjeni.
- **Provera potpisa** APK-a, AAB-a i svakog dela split arhive, sa rosterom potpisnika i
  SHA-256 / SHA-1 fingerprintom.
- **Ključevi**: kreiranje RSA 2048/3072/4096 i EC P-256/P-384, uvoz JKS i PKCS12,
  izvoz, preimenovanje, brisanje uz potvrdu.
- **Šifrovani backup i restore** (AES-256-GCM + PBKDF2-HMAC-SHA256) sa proverom pre
  vraćanja.
- Lozinke keystore-a šifrovane **Android Keystore** ključem; nikada u običnom tekstu.
- Oporavak ako Android Keystore ključ nestane: keystore ostaje, lozinka se ponovo unosi.

## Instalacija

1. Preuzmi `Signiffia-1.0.0.apk` ispod.
2. Otvori fajl na telefonu i dozvoli instalaciju iz tog izvora ako Android zatraži.
3. Potreban je **Android 8.0 (API 26)** ili noviji.

Aplikacija **nema nijednu dozvolu** — ni INTERNET.

## Bezbednost

- Originalni APK se nikada ne menja.
- Postojeći ključ se nikada ne briše niti regeneriše zbog greške.
- Nema reklama, analitike, naloga ni praćenja.
- APK sadrži licence u `assets/licenses/`.

## Provera integriteta

```
SHA-256: 4cc80426520dc4773feda8560784655d7fe6c292bb731276b528bcaf5812f4d9
Size:    9666701 bytes
Signer:  CN=Signiffia, O=Signiffia Open Source, C=RS
Cert SHA-256: C5:23:49:29:BD:38:82:F0:46:98:37:EE:9D:18:E5:8B:C7:19:25:6E:9A:D0:F6:8A:77:F3:44:68:5B:F3:79:1F
```

Provereno zvaničnim `apksigner` alatom: v1, v2 i v3 potpisi su ispravni.

## Poznato

- Aplikacija nije pokrenuta na fizičkom uređaju u okruženju za izradu (nema `adb`), pa je
  prvi pokretanje potrebno potvrditi na telefonu.
- AAB je proveren JAR proverom i apksig-om, ali nije uploadovan na Google Play.
- Signiffia menja samo potpis: ne menja `AndroidManifest.xml`, ne optimizuje APK i ne menja
  zipalign poravnanje.
