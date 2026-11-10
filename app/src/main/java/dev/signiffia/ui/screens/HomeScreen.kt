package dev.signiffia.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.BadgeTone

@Composable
fun HomeScreen(
    keyCount: Int,
    onSign: () -> Unit,
    onVerify: () -> Unit,
    onKeys: () -> Unit,
    onBackup: () -> Unit,
    onAbout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        HeroCard()
        Spacer(Modifier.height(18.dp))
        Text(
            "Šta želite da uradite?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        ActionCard(
            icon = Icons.Filled.Check,
            title = "Potpiši APK ili AAB",
            description = "Izaberi fajl, ključ i šeme potpisa. Potpis se odmah proverava.",
            onClick = onSign,
        )
        Spacer(Modifier.height(10.dp))
        ActionCard(
            icon = Icons.Filled.Search,
            title = "Proveri potpis",
            description = "Vidi ko je potpisao APK, koje šeme koristi i da li je izmenjen.",
            onClick = onVerify,
        )
        Spacer(Modifier.height(10.dp))
        ActionCard(
            icon = Icons.Filled.Lock,
            title = "Ključevi",
            description = if (keyCount == 0) {
                "Napravi novi ključ ili uvezi postojeći JKS/PKCS12 keystore."
            } else {
                "$keyCount ${if (keyCount == 1) "sačuvan ključ" else "sačuvanih ključeva"} na ovom uređaju."
            },
            onClick = onKeys,
        )
        Spacer(Modifier.height(10.dp))
        ActionCard(
            icon = Icons.Filled.Share,
            title = "Backup i vraćanje",
            description = "Šifrovana kopija ključeva koju možeš da preneseš na drugi uređaj.",
            onClick = onBackup,
        )
        Spacer(Modifier.height(10.dp))
        ActionCard(
            icon = Icons.Filled.Info,
            title = "O aplikaciji i licence",
            description = "Kako radi, šta se čuva i koje biblioteke se koriste.",
            onClick = onAbout,
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "Signiffia radi potpuno offline. Nema INTERNET dozvolu, nalog, reklame ni praćenje.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HeroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(
                "Signiffia",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Potpisivanje i provera Android aplikacija direktno na telefonu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge("Offline", BadgeTone.Success)
                StatusBadge("apksig", BadgeTone.Neutral)
                StatusBadge("v1 · v2 · v3", BadgeTone.Neutral)
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(10.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("unused")
private val PreviewColor = Color.Transparent
