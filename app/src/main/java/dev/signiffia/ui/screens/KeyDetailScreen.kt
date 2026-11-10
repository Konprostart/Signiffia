package dev.signiffia.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.data.KeyRecord
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.formatDate
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.WarningNote

@Composable
fun KeyDetailScreen(
    vm: AppViewModel,
    keyId: String,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    val record = keys.firstOrNull { it.id == keyId }

    var details by remember { mutableStateOf<List<AppViewModel.AliasDetail>?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var unlockPassword by remember { mutableStateOf("") }

    LaunchedEffect(keyId, record?.encStorePassword) {
        vm.aliasDetails(keyId).fold(
            onSuccess = { details = it },
            onFailure = { detailError = it.message; details = emptyList() }
        )
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && record != null) {
            val name = safeFileName(record.displayName) +
                "." + if (record.storeType.equals("JKS", true)) "jks" else "p12"
            vm.exportKey(record.id, uri, name)
        }
    }

    if (record == null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            ErrorNote("Ključ više nije dostupan. Verovatno je obrisan.")
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onBack) { Text("Nazad na listu") }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(title = record.displayName, subtitle = "Keystore: ${record.storeType}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    when (record.source) {
                        "generated" -> "napravljen u aplikaciji"
                        "imported" -> "uvezen"
                        "restored" -> "vraćen iz backup-a"
                        else -> record.source
                    },
                    BadgeTone.Neutral,
                )
                StatusBadge("${record.aliasCount} alijas(a)", BadgeTone.Neutral)
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("Alijasi", record.aliases.joinToString(", "))
            InfoRow("Primarni alijas", record.primaryAlias)
            InfoRow("Napravljen", formatDate(record.createdAt))
            FingerprintRow("SHA-256 sertifikata", record.fingerprintSha256)
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Sertifikati") {
            when {
                detailError != null -> ErrorNote(detailError!!)
                details == null -> Text(
                    "Učitavam detalje…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details!!.isEmpty() -> ErrorNote("Sertifikati nisu dostupni.")
                else -> details!!.forEachIndexed { index, detail ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    Text(
                        "Alijas: ${detail.alias}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    InfoRow("Subject", detail.certificate.subject)
                    InfoRow("Izdavalac", if (detail.certificate.selfSigned) "samopotpisan" else detail.certificate.issuer)
                    InfoRow("Ključ", detail.certificate.keyDescription)
                    InfoRow("Algoritam", detail.certificate.algorithm)
                    InfoRow("Važi do", detail.certificate.notAfter + if (detail.certificate.expired) " (istekao)" else "")
                    InfoRow("Lanac", "${detail.chainLength} sertifikata")
                    FingerprintRow("SHA-256", detail.certificate.sha256)
                    FingerprintRow("SHA-1", detail.certificate.sha1)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = "Izvoz i zaštita",
            subtitle = "Izvoz daje standardni keystore fajl zaštićen tvojom lozinkom.",
        ) {
            Button(
                onClick = { exporter.launch(safeFileName(record.displayName) + ".p12") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Izvezi keystore fajl") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenBackup, modifier = Modifier.fillMaxWidth()) {
                Text("Napravi šifrovani backup")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showUnlock = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Ponovo unesi lozinku (ako je izgubljena)")
            }
            Spacer(Modifier.height(12.dp))
            WarningNote(
                "Privatni ključ je jedini način da kasnije objavljuješ ažuriranja iste aplikacije. " +
                    "Sačuvaj backup na sigurno mesto. Ako izgubiš i ključ i backup, ažuriranja više nisu moguća."
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Upravljanje") {
            OutlinedButton(onClick = { showRename = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Promeni naziv")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Obriši ključ sa uređaja")
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showRename) {
        var name by remember { mutableStateOf(record.displayName) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Promeni naziv") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Naziv ključa") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameKey(record.id, name)
                    showRename = false
                }) { Text("Sačuvaj") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Otkaži") }
            },
        )
    }

    if (showUnlock) {
        AlertDialog(
            onDismissRequest = { showUnlock = false; unlockPassword = "" },
            title = { Text("Ponovno otključavanje") },
            text = {
                Column {
                    Text(
                        "Android Keystore ključ koji je čuvao lozinku više nije dostupan " +
                            "(npr. nakon reinstalacije). Unesi lozinku ovog keystore-a da ga ponovo otključaš. " +
                            "Ključ se neće promeniti.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = unlockPassword,
                        onValueChange = { unlockPassword = it },
                        label = { Text("Lozinka keystore-a") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.unlockKey(record.id, unlockPassword.toCharArray())
                    showUnlock = false
                    unlockPassword = ""
                }) { Text("Otključaj") }
            },
            dismissButton = { TextButton(onClick = { showUnlock = false }) { Text("Otkaži") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Obrisati ključ?") },
            text = {
                Text(
                    "Ključ \"${record.displayName}\" biće trajno uklonjen sa ovog uređaja. " +
                        "Ako nemaš backup ili izvezeni fajl, više ga nećeš moći vratiti i nećeš " +
                        "moći da potpišeš ažuriranja te aplikacije."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.deleteKey(record.id)
                    onBack()
                }) { Text("Obriši") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Otkaži") }
            },
        )
    }
}

fun safeFileName(name: String): String {
    val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").replace(" ", "-")
    return cleaned.ifBlank { "signiffia-key" }.take(60)
}
