package dev.signiffia.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.components.CardHeading
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.MonoText
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.WarningNote
import dev.signiffia.ui.formatTimestamp

/**
 * Account, encrypted-sync, recovery and device management in one place.
 *
 * Sync is an explicit opt-in: the app stays fully offline until the user configures a server
 * URL and performs an action. Only ciphertext (the vault blob and wrapped Master Vault Key)
 * ever leaves the device.
 */
@Composable
fun AccountScreen(vm: AppViewModel) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val mnemonic by vm.recoveryMnemonic.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(account.serverUrl) }
    var deviceName by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Signiffia nalog",
            subtitle = "Jedan lični nalog za više Android telefona. Sinhronizacija je opciona i potpuno šifrovana.",
        ) {
            if (!account.isLoggedIn) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Korisničko ime (npr. e-pošta)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Lozinka naloga") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Adresa servera (https://…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Naziv ovog uređaja (opciono)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(
                        onClick = { vm.registerAccount(username, password.toCharArray(), serverUrl, deviceName) },
                        enabled = username.isNotBlank() && password.length >= 6 && serverUrl.startsWith("https://"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Kreiraj nalog") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { vm.loginAccount(username, password.toCharArray(), serverUrl, deviceName) },
                        enabled = username.isNotBlank() && password.isNotBlank() && serverUrl.startsWith("https://"),
                        modifier = Modifier.weight(1f),
                    ) { Text("Prijavi se") }
                }
                if (vm.passkeySupported) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.passkeyLogin(username, serverUrl, deviceName) },
                        enabled = username.isNotBlank() && serverUrl.startsWith("https://"),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Prijavi se passkey-jem") }
                }
            } else {
                StatusBadge("Prijavljen", BadgeTone.Success)
                Spacer(Modifier.height(8.dp))
                InfoRow("Korisnik", account.userId)
                InfoRow("Uređaj", account.deviceName.ifBlank { account.deviceId })
                InfoRow("Server", account.serverUrl)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.logoutAccount() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Odjavi se")
                }
                if (vm.passkeySupported) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.registerPasskey() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Registruj passkey na ovom uređaju")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (account.isLoggedIn) {
            // One-time display of the freshly generated recovery key.
            if (mnemonic != null) {
                SectionCard(title = "Tvoj ključ za oporavak") {
                    WarningNote(
                        "Zapiši ove reči i čuvaj ih van uređaja. Ovo je JEDINI način da vratiš " +
                            "ključeve ako izgubiš sve telefone. Niko ti ih ne može vratiti."
                    )
                    Spacer(Modifier.height(12.dp))
                    MonoText(mnemonic ?: "")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.clearRecoveryMnemonic() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Razumem, sačuvao/la sam ga")
                    }
                }
            } else {
                SectionCard(title = "Ključ za oporavak") {
                    if (!account.hasRecoveryWrap) {
                        Text(
                            "Postavi ključ za oporavak pre prve sinhronizacije. Bez njega gubitak " +
                                "svih uređaja znači trajni gubitak pristupa ključevima.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.setupRecovery() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Napravi ključ za oporavak")
                        }
                    } else {
                        Text(
                            "Ključ za oporavak je postavljen. Čuvaj ga na sigurnom, offline mestu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    CardHeading("Vrati trezor na novi uređaj")
                    Text(
                        "Ako su ti svi uređaji nedostupni, prijavi se na nalog pa unesi ključ za oporavak.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recoveryInput,
                        onValueChange = { recoveryInput = it },
                        label = { Text("24 reči ključa za oporavak") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.recoverWithRecoveryKey(recoveryInput) },
                        enabled = recoveryInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Vrati trezor") }
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "Šifrovana sinhronizacija",
                subtitle = "Šalje samo šifrovani trezor i zamotani glavni ključ. Server nikada ne vidi tvoje ključeve ni lozinke.",
            ) {
                if (!account.hasMasterKey) {
                    WarningNote("Ovaj uređaj još nema ključ trezora. Vrati ga ključem za oporavak.")
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { vm.syncNow() },
                        enabled = account.hasMasterKey,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sinhronizuj sada") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { vm.downloadAndRestore() },
                        enabled = account.hasMasterKey,
                        modifier = Modifier.weight(1f),
                    ) { Text("Preuzmi i vrati") }
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(title = "Uređaji") {
                LaunchedEffect(account.sessionToken) {
                    if (account.isLoggedIn) vm.listDevices()
                }
                if (devices.isEmpty()) {
                    Text(
                        "Nema prijavljenih uređaja ili lista još nije učitana.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    devices.forEach { device ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${device.platform} • aktivan ${formatTimestamp(device.lastSeenAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (device.revoked) {
                                StatusBadge("Povučen", BadgeTone.Error)
                            } else if (device.deviceId == account.deviceId) {
                                StatusBadge("Ovaj uređaj", BadgeTone.Neutral)
                            } else {
                                OutlinedButton(onClick = { vm.revokeDevice(device.deviceId) }) {
                                    Text("Povuci")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.listDevices() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Osveži listu")
                }
                Spacer(Modifier.height(8.dp))
                WarningNote(
                    "Povlačenje uređaja sprečava buduću sinhronizaciju, ali NE može da obriše " +
                        "podatke koji su već preuzeti na taj uređaj."
                )
            }
        } else {
            SectionCard(title = "Kako radi") {
                Text(
                    "1. Kreiraj nalog i postavi ključ za oporavak.\n" +
                        "2. Uključi sinhronizaciju — ključevi se šifruju glavnim ključem trezora (MVK) " +
                        "i šalju na server kao šifrovani podaci.\n" +
                        "3. Na novom telefonu se prijavi, a zatim vrati trezor ključem za oporavak.\n\n" +
                        "Server nikada ne dobija tvoje privatne ključeve, lozinke ni glavni ključ — " +
                        "samo šifrovane podatke.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(22.dp))
    }
}
