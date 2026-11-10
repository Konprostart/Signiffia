# Licence i atribucija

Signiffia je licencirana pod **Apache License 2.0** (vidi [LICENSE](LICENSE)).

Ovaj dokument navodi sve spoljne komponente, njihove licence i način korišćenja.
Nijedan projekat nije kopiran u celini; korišćene su biblioteke i, gde je navedeno,
samo koncepti iz referentnih projekata.

---

## Glavne biblioteke

### Android apksig
- **Verzija:** 8.11.0
- **Koordinata:** `com.android.tools.build:apksig:8.11.0`
- **Licenca:** Apache License 2.0
- **Izvor:** https://android.googlesource.com/platform/tools/apksig
- **Kako se koristi:** kompletan APK/AAB signing i verifikacioni engine
  (`SigningEngine.kt`). Signiffia ne implementira sopstveni APK potpisni algoritam.

### BouncyCastle
- **Verzije:** `bcprov-jdk18on:1.79`, `bcpkix-jdk18on:1.79`, `bcutil-jdk18on:1.79`
- **Licenca:** Bouncy Castle Licence (MIT stil; tekst u nastavku)
- **Izvor:** https://www.bouncycastle.org/
- **Kako se koristi:** JKS keystore provider (Android platforma ne isporučuje JKS),
  generisanje X.509 sertifikata (`JcaX509v3CertificateBuilder`), PKCS#12 podrška.
  BouncyCastle se registruje kao JCA provider na startu aplikacije.

```
Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
Permission is hereby granted, free of charge, to any person obtaining a copy of this
software and associated documentation files (the "Software"), to deal in the Software
without restriction, including without limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
to whom the Software is furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
```

### AndroidX, Jetpack Compose, Material 3, Activity, Lifecycle
- **Verzije:** prema Compose BOM `2024.02.00` i `androidx.core:core-ktx:1.12.0`,
  `androidx.activity:activity-compose:1.8.2`, `androidx.lifecycle:*:2.7.0`
- **Licenca:** Apache License 2.0
- **Izvor:** https://developer.android.com/jetpack
- **Kako se koristi:** korisnički interfejs (Compose + Material 3), SAF launcher-i,
  lifecycle/ViewModel, `PackageInfoCompat`.

### Kotlin, Kotlin standard library, kotlinx.coroutines
- **Verzije:** Kotlin 1.9.22, kotlinx-coroutines-android 1.7.3
- **Licenca:** Apache License 2.0
- **Izvor:** https://kotlinlang.org/ , https://github.com/Kotlin/kotlinx.coroutines
- **Kako se koristi:** programski jezik i asinhroni rad.

### JUnit 4
- **Verzija:** 4.13.2 — **Licenca:** Eclipse Public License 1.0
- **Kako se koristi:** samo za JVM unit testove (`testImplementation`), nije deo APK-a.

---

## Referentni projekti (bez preuzetog koda)

Sledeći projekti su proučavani kao reference za dizajn i organizaciju. Nijedan fajl nije
kopiran u Signiffia.

| Projekat | Licenca | Šta je korišćeno kao referenca |
|---|---|---|
| [apk-signer-pro](https://github.com/ameermuawiya/apk-signer-pro) | Apache-2.0 | Organizacija Android interfejsa za izbor APK-a i keystore-a. Signiffia namerno **ne** čuva lozinke u običnom tekstu, što je nedostatak uočen u takvim alatima. |
| [WebToApp](https://github.com/shiaho777/web-to-app) | Unlicense | Ideja APK pipeline-a (uvoz/izvoz keystore-a). Signiffia ne preuzima automatsko brisanje/regenerisanje ključa. |
| [App Manager](https://github.com/MuntashirAkon/AppManager) | GPL-3.0-or-later | **Samo koncept** sigurnijeg čuvanja lozinki. Nijedan red GPL koda nije preuzet; implementacija u `data/AndroidSecretCipher.kt` i `data/KeyRepository.kt` je originalna i kompatibilna sa Apache-2.0. |

---

## Tekstovi licenci

- Apache License 2.0: [LICENSE](LICENSE)
- Bouncy Castle Licence: naveden iznad
- Eclipse Public License 1.0 (JUnit): https://www.eclipse.org/legal/epl-v10.html

Distribuirani APK sadrži samo biblioteke sa dozvolama kompatibilnim sa Apache-2.0
(Apache-2.0 i Bouncy Castle Licence). GPL komponente nisu prisutne.
