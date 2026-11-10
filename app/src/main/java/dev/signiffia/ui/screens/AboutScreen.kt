package dev.signiffia.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.signiffia.BuildConfig
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Signiffia",
            subtitle = "Verzija ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        ) {
            Row {
                StatusBadge("Apache-2.0", BadgeTone.Neutral)
                Spacer(Modifier.padding(3.dp))
                StatusBadge("offline", BadgeTone.Success)
                Spacer(Modifier.padding(3.dp))
                StatusBadge("bez dozvola", BadgeTone.Success)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Potpisuje i proverava Android APK i AAB fajlove direktno na telefonu. " +
                    "Signing engine je zvanična Android apksig biblioteka.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Kako se ključevi čuvaju") {
            Text(
                "• Keystore fajlovi su u privatnom skladištu aplikacije, nedostupni drugim aplikacijama.\n" +
                    "• Lozinke se čuvaju AES-256-GCM šifrovane ključem iz Android Keystore-a.\n" +
                    "• Lozinke se nikada ne upisuju u logove i ne šalju na internet.\n" +
                    "• Aplikacija nema INTERNET dozvolu, pa slanje nije ni moguće.\n" +
                    "• Obrisani ili neispravni ključevi se nikada ne zamenjuju novim bez tvoje odluke.\n" +
                    "• Backup je dodatno šifrovan lozinkom koju sam izabereš.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Napomena: izvoz keystore fajla je standardan i prenosiv. Backup je jedini pouzdan " +
                    "način da ključ preživi reinstalaciju ili novi telefon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Potpisne šeme") {
            InfoRow("v1 (JAR)", "Android 6 i stariji, i AAB")
            InfoRow("v2", "Android 7+, provera celog APK-a")
            InfoRow("v3", "Android 9+, rotacija ključa")
            InfoRow("v3.1", "Android 13+, rotacija preko lineage-a")
            InfoRow("v4", "inkrementalna instalacija (.idsig)")
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Upload key i app signing key") {
            Text(
                "Google Play app signing: kada je uključen, Google čuva pravi potpisni ključ " +
                    "a ti potpisuješ AAB tzv. upload ključem. Signiffia potpisuje AAB JAR (v1) " +
                    "potpisom, što je format koji Play prihvata za upload. " +
                    "Ovaj backup nije zamena za Google Play backup ključa.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Korišćene biblioteke i licence") {
            LicenseRow("Android apksig", "Apache-2.0", "Pokretanje potpisivanja i provere potpisa.")
            LicenseRow("BouncyCastle (bcprov, bcpkix, bcutil)", "Bouncy Castle Licence (MIT stil)", "JKS keystore, generisanje sertifikata.")
            LicenseRow("AndroidX / Jetpack Compose / Material 3", "Apache-2.0", "Korisnički interfejs.")
            LicenseRow("Kotlin i kotlinx.coroutines", "Apache-2.0", "Jezik i asinhroni rad.")
            LicenseRow("Signiffia", "Apache-2.0", "Sopstveni kod aplikacije.")
            Spacer(Modifier.height(10.dp))
            Text(
                "Kompletni tekstovi licenci su u aplikaciji (assets/licenses/) i u repozitorijumu " +
                    "(LICENSE i LICENSES.md).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Ograničenja") {
            Text(
                "• Split arhive (APKS/XAPK/APKM) se otvaraju kao arhive; potpisuju se pojedinačni APK delovi.\n" +
                    "• Signiffia ne menja AndroidManifest, ne optimizuje APK i ne potpisuje obfuscated native kod posebno.\n" +
                    "• APK koji je već potpisan drugim ključem potpisuje se iznova tvojim ključem (stari potpis se uklanja).\n" +
                    "• Zipalign se čuva onakav kakav je u ulaznom APK-u; Signiffia ne menja poravnanje.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun LicenseRow(name: String, license: String, purpose: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            purpose,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
