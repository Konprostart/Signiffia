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
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.WarningNote

@Composable
fun KeyImportScreen(vm: AppViewModel, onDone: (String) -> Unit) {
    var picked by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var separateKeyPassword by remember { mutableStateOf(false) }
    var keyPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = uri
            if (displayName.isBlank()) {
                displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: ""
            }
            error = null
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
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (picked == null) "Izaberi keystore fajl" else "Izaberi drugi fajl")
            }
            if (picked != null) {
                Spacer(Modifier.height(10.dp))
                InfoRow("Izabran fajl", picked!!.lastPathSegment ?: "keystore")
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Lozinke") {
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
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = separateKeyPassword,
                    onCheckedChange = { separateKeyPassword = it },
                )
                Text(
                    "Lozinka privatnog ključa je drugačija",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (separateKeyPassword) {
                OutlinedTextField(
                    value = keyPassword,
                    onValueChange = { keyPassword = it },
                    label = { Text("Lozinka privatnog ključa") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
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
                    vm.importKey(
                        sourceUri = uri,
                        displayName = displayName,
                        storePassword = password.toCharArray(),
                        keyPassword = if (separateKeyPassword && keyPassword.isNotEmpty()) {
                            keyPassword.toCharArray()
                        } else {
                            null
                        },
                    ) { record -> onDone(record.id) }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Uvezi ključ") }
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
