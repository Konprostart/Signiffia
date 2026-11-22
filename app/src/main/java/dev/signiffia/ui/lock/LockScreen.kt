package dev.signiffia.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen gate shown while the app is locked. It never collects a PIN/password itself:
 * the buttons delegate to the system keyguard / biometric prompt.
 */
@Composable
fun LockScreen(
    mode: LockMode,
    isDeviceSecure: Boolean,
    biometricAvailable: Boolean,
    error: String?,
    onUnlockBiometric: () -> Unit,
    onUnlockDeviceCredential: () -> Unit,
    onDisableLock: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Signiffia je zaključana",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Potvrdi identitet da nastaviš sa korišćenjem.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (!isDeviceSecure) {
            Spacer(Modifier.height(22.dp))
            Text(
                "Uređaj više nema podešen PIN, šifru ili šaru, pa se zaključavanje " +
                    "Signiffia ne može koristiti. Isključi zaključavanje da nastaviš — " +
                    "tvoje ključeve i dalje štiti šifrovanje aplikacije.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onDisableLock) {
                Text("Isključi zaključavanje")
            }
        } else {
            Spacer(Modifier.height(28.dp))
            if (mode == LockMode.BIOMETRIC_OR_CREDENTIAL && biometricAvailable) {
                Button(
                    onClick = onUnlockBiometric,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Otključaj otiskom prsta / licem")
                }
                Spacer(Modifier.height(12.dp))
            }
            if (mode == LockMode.DEVICE_CREDENTIAL) {
                Button(
                    onClick = onUnlockDeviceCredential,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Otključaj PIN-om / šifrom / šarom")
                }
            } else {
                OutlinedButton(
                    onClick = onUnlockDeviceCredential,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Koristi PIN / šifru / šaru")
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(18.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
