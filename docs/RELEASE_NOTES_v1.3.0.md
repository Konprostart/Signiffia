# Signiffia 1.3.0 — Release Notes

## Multi-device nalog i šifrovana sinhronizacija

Signiffia 1.3.0 dodaje **opcionu** multi-device sinhronizaciju: jedan lični nalog, isti
potpisni ključevi i lozinke na više Android telefona. Sve je potpuno šifrovano end-to-end i
radi **samo** kada korisnik to eksplicitno omogući — aplikacija ostaje potpuno offline po
podrazumevanoj postavci.

### Kako radi

1. **Prvi uređaj:** kreiraj nalog (lozinka), postavi **ključ za oporavak** (24 reči) i uključi
   sinhronizaciju. Ključevi se šifruju Master Vault Key-em (MVK) i šalju na server kao
   šifrovani podaci.
2. **Novi uređaj:** prijavi se na nalog, zatim **vrati trezor ključem za oporavak** — ključevi
   se preuzimaju šifrovano, dešifruju lokalno i vraćaju bez regeneracije (fingerprinti ostaju
   isti).
3. **Upravljanje:** lista prijavljenih uređaja i **povlačenje** (revoke) izgubljenog telefona.

### Bezbednost

- **Server je nepoverljiv** — prima isključivo šifrovane podatke. Nikada ne dobija MVK, ključ
  za oporavak, privatne ključeve ni lozinke keystore-a.
- **Autentifikacija ≠ enkripcija** — lozinka naloga (Argon2id) služi samo za prijavu; trezor
  se otključava isključivo MVK-om (preko ključa uređaja ili ključa za oporavak).
- **AES-256-GCM** sa jedinstvenim nonce-ima i AAD vezivanjem verzije + epoche (zaštita od
  rollback-a i zamene podataka).
- **BIP39 ključ za oporavak** validiran prema zvaničnim test vektorima.
- Potpisivanje ostaje **potpuno lokalno** — postojeći `SigningEngine`, ključevi i fingerprinti
  su netaknuti.

### Važno upozorenje

- **Povlačenje uređaja** sprečava buduću sinhronizaciju, ali **ne može** da obriše podatke
  koji su već preuzeti na izgubljeni telefon.
- **Ključ za oporavak čuvaj van uređaja** — to je jedini način da vratiš ključeve ako izgubiš
  sve telefone. Niko ti ga ne može vratiti.

### Testovi

- **117 unit testova** (dodato 60 novih za trezor, ključ za oporavak, uparivanje, tokene,
  sinhronizaciju i migraciju). Bez padova.

### Passkey

Passkey (WebAuthn) je planiran kao primarna metoda prijave; u 1.3.0 radi **lozinka naloga** kao
potpuno funkcionalan fallback. Passkey zahteva `androidx.credentials` + Google Play Services
(ili Android 14+) + deployovan WebAuthn relying party, pa nije uključen u ovo izdanje da bi
ono ostalo zavisnosno-lako i testabilno.

### APK

- Paket: `dev.signiffia` · versionCode 4 · versionName 1.3.0 · minSdk 26 / targetSdk 36.
- Dozvole: `USE_BIOMETRIC`, `USE_FINGERPRINT` (normal) + `INTERNET` (normal, samo za opcionu
  sinhronizaciju). **Nema opasnih dozvola.**
- Potpis: isti Signiffia release ključ kao 1.1.0/1.2.0 → redovna in-place nadogradnja.
