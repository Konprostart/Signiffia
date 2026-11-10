package dev.signiffia.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.core.SignerCertificate
import dev.signiffia.core.VerificationReport
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.SplitVerifyUiResult
import dev.signiffia.ui.formatSize
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.WarningNote

@Composable
fun VerifyScreen(vm: AppViewModel) {
    val state by vm.verifyState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::verifyInput)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "Fajl za proveru",
            subtitle = "Podržani su APK i AAB. Proverava se stvarni potpis, ne samo ime fajla.",
        ) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Izaberi APK ili AAB")
            }
        }

        val result = state.result
        val split = state.split
        if (split != null) {
            Spacer(Modifier.height(14.dp))
            SplitVerifyCard(split)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::clearVerify, modifier = Modifier.fillMaxWidth()) {
                Text("Očisti rezultat")
            }
        }
        if (state.error != null) {
            Spacer(Modifier.height(14.dp))
            ErrorNote(state.error!!)
        }
        if (result != null) {
            Spacer(Modifier.height(14.dp))
            ResultCard(
                report = result.report,
                fileName = result.summary.displayName,
                size = result.summary.sizeBytes,
                durationMs = result.durationMs,
                isBundle = result.summary.isBundle,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::clearVerify, modifier = Modifier.fillMaxWidth()) {
                Text("Očisti rezultat")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SplitVerifyCard(split: SplitVerifyUiResult) {
    val background = if (split.allVerified) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (split.allVerified) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (split.allVerified) "Svi delovi imaju ispravan potpis" else "Neki delovi nemaju ispravan potpis",
                style = MaterialTheme.typography.titleLarge,
                color = foreground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${split.parts.size} APK delova • ${split.durationMs} ms",
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
            )
            Spacer(Modifier.height(10.dp))
            split.parts.forEach { (name, report) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(
                        if (report.verified) "✓" else "✗",
                        if (report.verified) BadgeTone.Success else BadgeTone.Error,
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodySmall)
                        Text(
                            report.signers.firstOrNull()?.let { it.sha256.take(23) + "…" } ?: "bez potpisa",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = foreground,
                        )
                    }
                    Text(
                        listOfNotNull(
                            "v1".takeIf { report.v1 },
                            "v2".takeIf { report.v2 },
                            "v3".takeIf { report.v3 },
                        ).joinToString("+"),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground,
                    )
                }
            }
            if (split.signerFingerprints.size > 1) {
                Spacer(Modifier.height(8.dp))
                WarningNote(
                    "Delovi su potpisani različitim ključevima (${split.signerFingerprints.size}). " +
                        "Svi delovi istog paketa moraju biti potpisani istim ključem."
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    report: VerificationReport,
    fileName: String,
    size: Long,
    durationMs: Long,
    isBundle: Boolean,
) {
    val good = report.verified
    val background = if (good) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val foreground = if (good) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (good) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier
                        .background(foreground.copy(alpha = 0.15f), RoundedCornerShape(50))
                        .padding(6.dp)
                        .size(22.dp),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        if (good) "Potpis je ispravan" else "Potpis NIJE ispravan",
                        style = MaterialTheme.typography.titleLarge,
                        color = foreground,
                    )
                    Text(
                        if (isBundle) "AAB / JAR potpis" else "APK potpis",
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (report.v1) StatusBadge("v1 ✓", BadgeTone.Success)
                if (report.v2) StatusBadge("v2 ✓", BadgeTone.Success)
                if (report.v3) StatusBadge("v3 ✓", BadgeTone.Success)
                if (report.v31) StatusBadge("v3.1 ✓", BadgeTone.Success)
                if (report.v4) StatusBadge("v4 ✓", BadgeTone.Success)
                if (!report.v1 && !report.v2 && !report.v3) StatusBadge("nema potpisa", BadgeTone.Error)
            }
            Spacer(Modifier.height(12.dp))
            InfoRow("Fajl", fileName)
            InfoRow("Veličina", formatSize(size))
            InfoRow("Vreme provere", "$durationMs ms")
            if (report.checkedMinSdk > 0) {
                InfoRow("Provereno za Android", "API ${report.checkedMinSdk}+")
            }
            if (report.lineageLength > 1) {
                InfoRow("Rotacija ključa", "${report.lineageLength} ključa u lineage-u")
            }

            if (report.signers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Potpisnici", style = MaterialTheme.typography.titleMedium, color = foreground)
                report.signers.forEach { cert -> SignerBlock(cert) }
            }

            if (report.errors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Problemi",
                    style = MaterialTheme.typography.titleMedium,
                    color = foreground,
                    fontWeight = FontWeight.SemiBold,
                )
                report.errors.forEach { error ->
                    Text(
                        "• $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground,
                    )
                }
            }
            if (report.warnings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Napomene", style = MaterialTheme.typography.labelLarge, color = foreground)
                report.warnings.forEach { warning ->
                    Text("• $warning", style = MaterialTheme.typography.bodySmall, color = foreground)
                }
            }
            if (report.notes.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                report.notes.forEach { note ->
                    Text("• $note", style = MaterialTheme.typography.bodySmall, color = foreground)
                }
            }
            if (isBundle) {
                Spacer(Modifier.height(10.dp))
                WarningNote(
                    "AAB se ne instalira na telefon. Služi za upload na Google Play, gde mora biti " +
                        "potpisan upravo ovakvim JAR (v1) potpisom."
                )
            }
        }
    }
}

@Composable
private fun SignerBlock(cert: SignerCertificate) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(cert.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "${cert.keyDescription} • ${cert.algorithm}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FingerprintRow("SHA-256", cert.sha256)
        FingerprintRow("SHA-1", cert.sha1)
        InfoRow("Važi od", cert.notBefore)
        InfoRow("Važi do", cert.notAfter + if (cert.expired) " (istekao)" else "")
    }
}
