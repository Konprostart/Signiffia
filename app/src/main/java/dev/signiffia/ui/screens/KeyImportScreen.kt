package dev.signiffia.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.WarningNote
import dev.signiffia.ui.lock.AppLockGate

@Composable
fun KeyImportScreen(vm: AppViewModel, onDone: (String) -> Unit) {
    var picked by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var aliasPasswords by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }

    val preview by vm.importPreview.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLockGate.exit()
        if (uri != null) {
            picked = uri
            if (displayName.isBlank()) {
                displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: ""
            }
            error = null
            aliasPasswords = emptyMap()
            vm.clearImportPreview()
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
            title = "Uvezi keystore",
            subtitle = "Podržani su PKCS12 (.p12/.pfx) i JKS (.jks). Fajl se kopira u aplikaciju; " +
                "original ostaje netaknut.",
        ) {
            OutlinedButton(onClick = { AppLockGate.enter(); picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (picked == null) "Izaberi keystore fajl" else "Izaberi drugi fajl")
            }
            if (picked != null) {
                Spacer(Modifier.height(10.dp))
                InfoRow("Izabran fajl", picked!!.lastPathSegment ?: "keystore")
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Lozinka keystore-a") {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Naziv ključa (za tebe)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Lozinka keystore-a") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val uri = picked
                    if (uri == null) {
                        error = "Prvo izaberi keystore fajl."
                        return@Button
                    }
                    if (password.isEmpty()) {
                        error = "Unesi lozinku keystore-a."
                        return@Button
                    }
                    error = null
                    aliasPasswords = emptyMap()
                    vm.previewImport(uri, password.toCharArray())
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Učitaj keystore i prikaži alijase") }
        }

        if (preview != null) {
            Spacer(Modifier.height(14.dp))
            SectionCard(
                title = "Alijasi (${preview!!.aliases.size})",
                subtitle = "Svaki alijas može imati sopstvenu lozinku. Ostavi prazno ako je " +
                    "lozinka alijasa ista kao lozinka keystore-a.",
            ) {
                preview!!.aliases.forEach { alias ->
                    Spacer(Modifier.height(4.dp))
                    InfoRow(alias.alias, alias.keyDescription)
                    if (alias.fingerprintSha256.isNotBlank()) {
                        FingerprintRow("SHA-256", alias.fingerprintSha256)
                    }
                    OutlinedTextField(
                        value = aliasPasswords[alias.alias] ?: "",
                        onValueChange = { aliasPasswords = aliasPasswords + (alias.alias to it) },
                        label = { Text("Lozinka za \"${alias.alias}\" (prazno = keystore lozinka)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val uri = picked
                        val store = password
                        if (uri == null) {
                            error = "Keystore fajl više nije izabran."
                            return@Button
                        }
                        if (store.isEmpty()) {
                            error = "Unesi lozinku keystore-a."
                            return@Button
                        }
                        error = null
                        val keyPasswords = preview!!.aliases.associate { info ->
                            val entered = aliasPasswords[info.alias]?.trim().orEmpty()
                            info.alias to (if (entered.isEmpty()) store.toCharArray() else entered.toCharArray())
                        }
                        vm.importKey(
                            sourceUri = uri,
                            displayName = displayName,
                            storePassword = store.toCharArray(),
                            keyPasswords = keyPasswords,
                        ) { record -> onDone(record.id) }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Uvezi ključ") }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            ErrorNote(it)
        }

        Spacer(Modifier.height(12.dp))
        WarningNote(
            "Ako uvoz ne uspe zbog pogrešne lozinke, ništa se ne menja: postojeći ključevi ostaju " +
                "sačuvani. Signiffia nikada ne generiše zamenski ključ bez tvoje odluke."
        )
        Spacer(Modifier.height(28.dp))
    }
}
