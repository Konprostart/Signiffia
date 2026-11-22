package dev.signiffia.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.core.SignatureComparison
import dev.signiffia.core.SigningEngine
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.CompareSide
import dev.signiffia.ui.formatSize
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.WarningNote
import dev.signiffia.ui.lock.AppLockGate

@Composable
fun CompareScreen(vm: AppViewModel) {
    val state by vm.compareState.collectAsStateWithLifecycle()

    val oldPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLockGate.exit()
        uri?.let(vm::loadCompareOld)
    }
    val newPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLockGate.exit()
        uri?.let(vm::loadCompareNew)
    }

    val comparison = if (state.old.report != null && state.new.report != null) {
        SigningEngine.compareSigners(state.old.report!!, state.new.report!!)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        SectionCard(
            title = "1. Stara verzija (već instalirana)",
            subtitle = "APK ili AAB koji je trenutno na uređaju / u prodavnici.",
        ) {
            Button(onClick = { AppLockGate.enter(); oldPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Izaberi staru verziju")
            }
            SideSummary(state.old)
        }

        Spacer(Modifier.height(14.dp))
        SectionCard(
            title = "2. Nova verzija",
            subtitle = "Nova APK/AAB verzija koju planiraš da objaviš.",
        ) {
            Button(onClick = { AppLockGate.enter(); newPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Izaberi novu verziju")
            }
            SideSummary(state.new)
        }

        if (comparison != null) {
            Spacer(Modifier.height(14.dp))
            ComparisonResultCard(comparison)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::clearCompare, modifier = Modifier.fillMaxWidth()) {
                Text("Očisti poređenje")
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SideSummary(side: CompareSide) {
    side.error?.let {
        Spacer(Modifier.height(10.dp))
        ErrorNote(it)
        return
    }
    val summary = side.summary ?: return
    Spacer(Modifier.height(10.dp))
    InfoRow("Fajl", summary.displayName)
    InfoRow("Tip", summary.kindLabel)
    InfoRow("Veličina", formatSize(summary.sizeBytes))
    val packageName = summary.packageName
    if (packageName != null) {
        InfoRow("Paket", packageName)
        InfoRow("Verzija", "${summary.versionName ?: "?"} (${summary.versionCode ?: "?"})")
    }
    val report = side.report
    if (report != null) {
        Spacer(Modifier.height(6.dp))
        VerificationBadges(report)
        if (report.signers.isNotEmpty()) {
            val signer = report.signers.first()
            FingerprintRow("Potpisnik (SHA-256)", signer.sha256)
            InfoRow("Subject", signer.subject)
        } else {
            InfoRow("Potpisnik", "bez potpisa")
        }
    }
}

@Composable
private fun ComparisonResultCard(comparison: SignatureComparison) {
    val good = comparison.upgradeCompatible
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
            Text(
                when {
                    comparison.sameSigner -> "Isti potpisni ključ"
                    comparison.upgradeCompatible -> "Potpisnik se promenio, ali postoji zajednički ključ"
                    else -> "Potpisnik se promenio"
                },
                style = MaterialTheme.typography.titleLarge,
                color = foreground,
            )
            Spacer(Modifier.height(6.dp))
            if (comparison.sameSigner) {
                StatusBadge("nadogradnja kompatibilna", BadgeTone.Success)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nova verzija je potpisana istim ključem kao stara, pa će se instalirati kao " +
                        "ažuriranje postojeće aplikacije.",
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            } else if (comparison.upgradeCompatible) {
                StatusBadge("nadogradnja moguća", BadgeTone.Warning)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Skup potpisnika se razlikuje, ali obe verzije dele bar jedan sertifikat. " +
                        "Na uobičajenom Android uređaju nova verzija može zameniti staru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            } else {
                StatusBadge("nadogradnja NIJE moguća", BadgeTone.Error)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Nova verzija je potpisana drugim ključem. Android je neće dozvoliti kao " +
                        "ažuriranje postojeće aplikacije — korisnik bi morao da deinstalira staru " +
                        "verziju (i izgubi podatke) pre instalacije nove.",
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            }

            if (comparison.oldFingerprints.isNotEmpty() || comparison.newFingerprints.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Stara verzija", style = MaterialTheme.typography.labelLarge, color = foreground)
                comparison.oldFingerprints.forEach { fp ->
                    Text(fp, style = MaterialTheme.typography.bodySmall, color = foreground)
                }
                Spacer(Modifier.height(6.dp))
                Text("Nova verzija", style = MaterialTheme.typography.labelLarge, color = foreground)
                comparison.newFingerprints.forEach { fp ->
                    Text(fp, style = MaterialTheme.typography.bodySmall, color = foreground)
                }
            }
            Spacer(Modifier.height(12.dp))
            WarningNote(
                "Ovo poređenje koristi SHA-256 otisak sertifikata potpisnika. Za pouzdano " +
                    "objavljivanje ažuriranja uvek potpisuj sve verzije iste aplikacije istim ključem."
            )
        }
    }
}
