package dev.signiffia.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.WarningNote
import dev.signiffia.ui.formatDate

@Composable
fun BackupScreen(vm: AppViewModel) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    val state by vm.backupState.collectAsStateWithLifecycle()

    var selectedIds by remember(keys) { mutableStateOf(keys.map { it.id }.toSet()) }
    var backupPassphrase by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var backupError by remember { mutableStateOf<String?>(null) }

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassphrase by remember { mutableStateOf("") }

    val backupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            vm.backupTo(uri, backupPassphrase.toCharArray(), selectedIds.toList())
            backupPassphrase = ""
            backupConfirm = ""
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreUri = uri
            vm.clearRestorePreview()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Napravi backup",
            subtitle = "Jedan šifrovan fajl sa ključevima i njihovim lozinkama. Otvara se samo " +
                "lozinkom koju ovde izabereš (AES-256-GCM + PBKDF2).",
        ) {
            if (keys.isEmpty()) {
                Text(
                    "Nema ključeva za backup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                keys.forEach { record ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedIds.contains(record.id),
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + record.id
                                else selectedIds - record.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(record.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${record.aliases.joinToString(", ")} • ${record.storeType}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = backupPassphrase,
                    onValueChange = { backupPassphrase = it },
                    label = { Text("Backup lozinka (min. 8 karaktera)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = backupConfirm,
                    onValueChange = { backupConfirm = it },
                    label = { Text("Ponovi backup lozinku") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                backupError?.let {
                    Spacer(Modifier.height(8.dp))
                    ErrorNote(it)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        backupError = when {
                            selectedIds.isEmpty() -> "Izaberi najmanje jedan ključ."
                            backupPassphrase.length < 8 -> "Backup lozinka mora imati najmanje 8 karaktera."
                            backupPassphrase != backupConfirm -> "Lozinke se ne poklapaju."
                            else -> null
                        }
                        if (backupError == null) {
                            backupSaver.launch("signiffia-backup-${System.currentTimeMillis() / 1000}.sgnbak")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Sačuvaj backup") }
            }
            state.backupDoneName?.let {
                Spacer(Modifier.height(10.dp))
                StatusBadge("Backup napravljen i proveren ($it)", BadgeTone.Success)
            }
            Spacer(Modifier.height(10.dp))
            WarningNote(
                "Backup lozinku zapamti odvojeno od backup fajla. Bez nje backup se ne može otvoriti, " +
                    "a Signiffia je ne može vratiti."
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = "Vrati iz backup-a",
            subtitle = "Backup se prvo proveri (potpis i fingerprint svakog ključa), pa se ključevi vraćaju.",
        ) {
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (restoreUri == null) "Izaberi backup fajl" else "Izaberi drugi backup")
            }
            if (restoreUri != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = restorePassphrase,
                    onValueChange = { restorePassphrase = it },
                    label = { Text("Backup lozinka") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { vm.previewRestore(restoreUri!!, restorePassphrase.toCharArray()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Proveri backup") }
            }

            state.restoreError?.let {
                Spacer(Modifier.height(10.dp))
                ErrorNote(it)
            }

            val preview = state.restorePreview
            if (preview != null) {
                Spacer(Modifier.height(12.dp))
                StatusBadge("Backup je ispravan", BadgeTone.Success)
                Spacer(Modifier.height(8.dp))
                InfoRow("Napravljen", formatDate(preview.summary.createdAtMillis))
                InfoRow("Broj ključeva", "${preview.summary.keys.size}")
                preview.summary.keys.forEach { entry ->
                    Spacer(Modifier.height(8.dp))
                    Text(entry.displayName, style = MaterialTheme.typography.bodyMedium)
                    InfoRow("Alijasi", entry.aliases.joinToString(", "))
                    FingerprintRow("SHA-256", entry.fingerprintSha256)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.confirmRestore { restorePassphrase = ""; restoreUri = null } },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Vrati sve ključeve (${preview.summary.keys.size})") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = vm::clearRestorePreview, modifier = Modifier.fillMaxWidth()) {
                    Text("Otkaži")
                }
            }
            state.restoreDoneCount.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(10.dp))
                StatusBadge("Vraćeno $it ključeva", BadgeTone.Success)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}
