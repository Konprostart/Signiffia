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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.WarningNote
import dev.signiffia.ui.lock.AutoLockDelay
import dev.signiffia.ui.lock.LockMode

@Composable
fun SecurityScreen(
    mode: LockMode,
    delay: AutoLockDelay,
    isDeviceSecure: Boolean,
    biometricAvailable: Boolean,
    onChange: (LockMode, AutoLockDelay) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Način zaključavanja",
            subtitle = "Šta Signiffia traži pri otvaranju aplikacije.",
        ) {
            RadioOption(
                title = "Isključeno",
                description = "Aplikacija se otvara odmah, bez potvrde identiteta.",
                selected = mode == LockMode.OFF,
                enabled = true,
                onClick = { onChange(LockMode.OFF, delay) },
            )
            RadioOption(
                title = "PIN / šifra / šara uređaja",
                description = "Koristi postojeći ekran zaključavanja Android uređaja.",
                selected = mode == LockMode.DEVICE_CREDENTIAL,
                enabled = isDeviceSecure,
                onClick = { onChange(LockMode.DEVICE_CREDENTIAL, delay) },
            )
            RadioOption(
                title = "Otisak prsta / lice + PIN kao rezerva",
                description = "Brzo otključavanje biometrijom, uz PIN uređaja kao rezervu.",
                selected = mode == LockMode.BIOMETRIC_OR_CREDENTIAL,
                enabled = isDeviceSecure,
                onClick = { onChange(LockMode.BIOMETRIC_OR_CREDENTIAL, delay) },
            )

            if (!isDeviceSecure) {
                Spacer(Modifier.height(10.dp))
                WarningNote(
                    "Na uređaju nije podešen PIN, šifra ili šara. Idi u Android podešavanja → " +
                        "Bezbednost → Zaključani ekran i podesi ga pre nego što uključiš zaštitu."
                )
            }
            if (mode == LockMode.BIOMETRIC_OR_CREDENTIAL && !biometricAvailable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Na uređaju nije upisan otisak prsta ili lice — koristiće se PIN / šifra / šara uređaja.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = "Automatsko zaključavanje",
            subtitle = "Kada aplikaciju vratiš iz pozadine nakon ovog vremena, ponovo se zaključava.",
        ) {
            val lockEnabled = mode != LockMode.OFF
            RadioOption(
                title = "Odmah",
                description = "Zaključava čim napustiš aplikaciju.",
                selected = delay == AutoLockDelay.IMMEDIATE,
                enabled = lockEnabled,
                onClick = { onChange(mode, AutoLockDelay.IMMEDIATE) },
            )
            RadioOption(
                title = "30 sekundi",
                description = "Podrazumevano.",
                selected = delay == AutoLockDelay.THIRTY_SECONDS,
                enabled = lockEnabled,
                onClick = { onChange(mode, AutoLockDelay.THIRTY_SECONDS) },
            )
            RadioOption(
                title = "1 minut",
                description = "Kratka pauza pre zaključavanja.",
                selected = delay == AutoLockDelay.ONE_MINUTE,
                enabled = lockEnabled,
                onClick = { onChange(mode, AutoLockDelay.ONE_MINUTE) },
            )
            RadioOption(
                title = "5 minuta",
                description = "Duga pauza pre zaključavanja.",
                selected = delay == AutoLockDelay.FIVE_MINUTES,
                enabled = lockEnabled,
                onClick = { onChange(mode, AutoLockDelay.FIVE_MINUTES) },
            )
            if (!lockEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Uključi zaključavanje da podesiš odgodu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Napomena") {
            Text(
                "Zaključavanje je samo zaštita pristupa aplikaciji i ne menja šifrovanje " +
                    "ključeva. Signiffia nikada ne čuva tvoj PIN, šifru ni biometrijske podatke — " +
                    "samo traži od Android sistema da potvrdi identitet. Biometrijski podaci se " +
                    "obrađuju isključivo na uređaju.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun RadioOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).alpha(alpha)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
