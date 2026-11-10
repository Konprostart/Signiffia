package dev.signiffia.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.signiffia.core.KeystoreTools
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.WarningNote

private data class KeyTypeOption(val label: String, val algorithm: String, val size: Int)

private val KEY_TYPES = listOf(
    KeyTypeOption("RSA 2048 — najkompatibilnije", "RSA", 2048),
    KeyTypeOption("RSA 3072 — jači, malo veći potpis", "RSA", 3072),
    KeyTypeOption("RSA 4096 — najjači, sporije", "RSA", 4096),
    KeyTypeOption("EC P-256 — manji potpis, brže", "EC", 256),
    KeyTypeOption("EC P-384", "EC", 384),
)

@Composable
fun KeyCreateScreen(vm: AppViewModel, onDone: (String) -> Unit) {
    var displayName by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("release") }
    var typeIndex by remember { mutableStateOf(0) }
    var validityYears by remember { mutableStateOf("30") }
    var commonName by remember { mutableStateOf("Signiffia") }
    var organization by remember { mutableStateOf("") }
    var organizationalUnit by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun validate(): String? {
        if (!alias.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            return "Alijas može sadržati samo slova, brojeve, tačku, crtu i donju crtu (1–64)."
        }
        if (commonName.isBlank()) return "Common Name (CN) ne sme biti prazan."
        val years = validityYears.toIntOrNull()
        if (years == null || years !in 1..100) return "Period važenja mora biti između 1 i 100 godina."
        if (password.length < 8) return "Lozinka mora imati najmanje 8 karaktera."
        if (password != passwordConfirm) return "Lozinke se ne poklapaju."
        return null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Novi potpisni ključ",
            subtitle = "Ključ se pravi na uređaju. Privatni ključ nikada ne napušta telefon.",
        ) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Naziv ključa (za tebe)") },
                placeholder = { Text("npr. Moj release ključ") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Alijas u keystore-u") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Tip ključa") {
            KEY_TYPES.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { typeIndex = index }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = typeIndex == index, onClick = { typeIndex = index })
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Podaci sertifikata", subtitle = "Prikazuju se u potpisu APK-a i na Google Play-u.") {
            OutlinedTextField(
                value = commonName,
                onValueChange = { commonName = it },
                label = { Text("Common Name (CN)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text("Organizacija (O) — opciono") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = organizationalUnit,
                onValueChange = { organizationalUnit = it },
                label = { Text("Odeljenje (OU) — opciono") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it.take(2).uppercase() },
                    label = { Text("Država") },
                    placeholder = { Text("RS") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = validityYears,
                    onValueChange = { validityYears = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Važi (godina)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = "Lozinka keystore-a",
            subtitle = "Njome se otvara ključ pri potpisivanju. Čuva se šifrovano, ali je zapamti.",
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Lozinka") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                label = { Text("Ponovi lozinku") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            WarningNote(
                "Bez ove lozinke ključ se ne može koristiti. Signiffia je ne može vratiti, ali je " +
                    "možeš sigurno sačuvati u backup-u. Napravi backup odmah nakon pravljenja ključa."
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            ErrorNote(it)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val validation = validate()
                if (validation != null) {
                    error = validation
                    return@Button
                }
                error = null
                val option = KEY_TYPES[typeIndex]
                vm.createKey(
                    displayName = displayName,
                    spec = KeystoreTools.KeyGenSpec(
                        alias = alias.trim(),
                        password = password.toCharArray(),
                        keyAlgorithm = option.algorithm,
                        keySize = option.size,
                        validityDays = (validityYears.toIntOrNull() ?: 30) * 365,
                        commonName = commonName.trim(),
                        organization = organization.trim(),
                        organizationalUnit = organizationalUnit.trim(),
                        countryCode = country.trim(),
                    ),
                ) { record -> onDone(record.id) }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Napravi ključ")
        }
        Text(
            "Ključ se čuva u PKCS12 formatu u privatnom skladištu aplikacije.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))
    }
}
