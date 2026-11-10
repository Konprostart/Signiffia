package dev.signiffia.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.signiffia.core.VerificationReport
import dev.signiffia.ui.AppViewModel
import dev.signiffia.ui.ApkSummary
import dev.signiffia.ui.SignUiResult
import dev.signiffia.ui.SplitUiResult
import dev.signiffia.ui.formatSize
import dev.signiffia.ui.components.BadgeTone
import dev.signiffia.ui.components.FingerprintRow
import dev.signiffia.ui.components.InfoRow
import dev.signiffia.ui.components.KeySelector
import dev.signiffia.ui.components.ErrorNote
import dev.signiffia.ui.components.SchemeToggleRow
import dev.signiffia.ui.components.SectionCard
import dev.signiffia.ui.components.StatusBadge
import dev.signiffia.ui.components.WarningNote

@Composable
fun SignScreen(vm: AppViewModel, onOpenKeys: () -> Unit) {
    val state by vm.signState.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::loadSignInput)
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(vm::signTo) }
    val idsigSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(vm::savePendingIdsig) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        val apk = state.apk
        SectionCard(
            title = "1. Fajl za potpis",
            subtitle = "Original se nikada ne menja. Potpisuje se kopija, a rezultat čuvaš gde želiš.",
        ) {
            if (apk == null) {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Izaberi APK ili AAB") }
            } else {
                ApkHeader(apk)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Izaberi drugi fajl") }
            }
            if (state.error != null && apk == null) {
                Spacer(Modifier.height(10.dp))
                ErrorNote(state.error!!)
            }
        }

        if (apk != null) {
            Spacer(Modifier.height(14.dp))
            if (apk.isSplitArchive) {
                SectionCard(
                    title = "Split arhiva — ${apk.splitApkNames.size} APK delova",
                    subtitle = "Svi delovi se potpisuju istim ključem, a ostali fajlovi (manifest.json, obb) ostaju nepromenjeni.",
                ) {
                    apk.splitApkNames.take(12).forEach { name ->
                        Text("• $name", style = MaterialTheme.typography.bodySmall)
                    }
                    if (apk.splitApkNames.size > 12) {
                        Text(
                            "… još ${apk.splitApkNames.size - 12} delova",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    WarningNote(
                        "Izlaz će biti nova arhiva sa istim imenom i ekstenzijom (APKS/XAPK/APKM). " +
                            "Original se ne menja."
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Column {
                SectionCard(
                    title = "2. Ključ za potpis",
                    subtitle = if (apk.isBundle) {
                        "AAB se potpisuje JAR (v1) potpisom, kako Google Play zahteva."
                    } else {
                        "Ključ ostaje na uređaju i zaštićen je lozinkom i Android Keystore-om."
                    },
                ) {
                    KeySelector(
                        keys = keys,
                        selectedKeyId = state.selectedKeyId,
                        selectedAlias = state.selectedAlias,
                        onSelectKey = vm::selectKey,
                        onSelectAlias = vm::selectAlias,
                    )
                    if (keys.isEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenKeys, modifier = Modifier.fillMaxWidth()) {
                            Text("Napravi ili uvezi ključ")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SectionCard(
                    title = "3. Potpisne šeme",
                    subtitle = "v2 i v3 su preporučeni za Android 7 i novije. v1 je potreban samo za starije uređaje.",
                ) {
                    if (apk.isBundle) {
                        InfoRow("Šema", "v1 (JAR) — obavezno za AAB")
                        InfoRow("v2 / v3", "Ne koriste se za AAB")
                    } else {
                        SchemeToggleRow(
                            title = "v1 (JAR)",
                            description = "Android 6 i stariji, plus AAB",
                            checked = state.v1,
                            onCheckedChange = { vm.setSchemes(it, state.v2, state.v3, state.v4) },
                        )
                        SchemeToggleRow(
                            title = "v2 (APK Signature Scheme v2)",
                            description = "Android 7+ — brža i sigurnija provera",
                            checked = state.v2,
                            enabled = !state.v3 || state.v2,
                            onCheckedChange = { vm.setSchemes(state.v1, it, state.v3, state.v4) },
                        )
                        SchemeToggleRow(
                            title = "v3 (v3 + v3.1 rotacija)",
                            description = "Android 9+ — podrška za rotaciju ključa",
                            checked = state.v3,
                            onCheckedChange = { vm.setSchemes(state.v1, it || state.v2, it, state.v4) },
                        )
                        SchemeToggleRow(
                            title = "v4 (inkrementalna instalacija)",
                            description = "Pravi dodatni .idsig fajl; nije potreban za normalnu instalaciju",
                            checked = state.v4,
                            enabled = (state.v2 || state.v3) && !apk.isSplitArchive,
                            onCheckedChange = { vm.setSchemes(state.v1, state.v2, state.v3, it) },
                        )
                        Spacer(Modifier.height(8.dp))
                        WarningNote(
                            "Ako isključiš v2 i v3, APK neće moći da se instalira na Android 7 i noviji. " +
                                "Zadrži bar v2."
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                val canSign = state.selectedKeyId != null && busy == null
                Button(
                    onClick = {
                        saver.launch(vm.suggestedOutputName())
                    },
                    enabled = canSign,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (busy != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    Text(
                        when {
                            apk.isBundle -> "Potpiši AAB i sačuvaj"
                            apk.isSplitArchive -> "Potpiši sve delove i sačuvaj"
                            else -> "Potpiši APK i sačuvaj"
                        }
                    )
                }
                if (keys.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Prvo napravi ili uvezi ključ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    ErrorNote(state.error!!)
                }

                state.splitResult?.let { split ->
                    Spacer(Modifier.height(14.dp))
                    SplitResultCard(result = split, onNewFile = vm::clearSignInput)
                }

                state.result?.let { result ->
                    Spacer(Modifier.height(14.dp))
                    SignResultCard(
                        result = result,
                        onSaveIdsig = {
                            idsigSaver.launch(
                                result.fileName.removeSuffix(".apk") + ".apk.idsig"
                            )
                        },
                        onNewFile = vm::clearSignInput,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ApkHeader(apk: ApkSummary) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (apk.icon != null) {
            Image(
                bitmap = apk.icon,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    apk.kindLabel.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                apk.appLabel ?: apk.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${apk.kindLabel} • ${formatSize(apk.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    if (apk.packageName != null) {
        InfoRow("Paket", apk.packageName)
        InfoRow("Verzija", "${apk.versionName ?: "?"} (${apk.versionCode ?: "?"})")
    }
    InfoRow("Min. Android", apk.minSdk?.let { "API $it" } ?: "?")
    InfoRow("Target Android", apk.targetSdk?.let { "API $it" } ?: "?")
    if (apk.splitNames.isNotEmpty()) {
        InfoRow("Splitovi", apk.splitNames.joinToString(", "))
    }
    if (apk.signerCertificates.isNotEmpty()) {
        InfoRow("Trenutno potpisano", "${apk.signerCertificates.size} sertifikat(a)")
        FingerprintRow(
            "Postojeći SHA-256",
            apk.signerCertificates.first().sha256,
        )
    } else if (apk.isApk) {
        InfoRow("Trenutno potpisano", "nije potpisan")
    }
}

@Composable
private fun SplitResultCard(result: SplitUiResult, onNewFile: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Split arhiva je potpisana",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow("Sačuvano kao", result.savedName ?: result.fileName)
            InfoRow("Veličina", formatSize(result.sizeBytes))
            InfoRow("Vreme", "${result.durationMs} ms")
            InfoRow("SHA-256 arhive", result.sha256)
            Spacer(Modifier.height(8.dp))
            result.parts.forEach { part ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(if (part.signed) "✓" else "✗", if (part.signed) BadgeTone.Success else BadgeTone.Error)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        part.name,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        part.schemes?.let { s ->
                            listOfNotNull(
                                "v1".takeIf { s.v1 }, "v2".takeIf { s.v2 }, "v3".takeIf { s.v3 }
                            ).joinToString("+")
                        } ?: (part.error ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onNewFile, modifier = Modifier.fillMaxWidth()) {
                Text("Potpiši još jedan fajl")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignResultCard(
    result: SignUiResult,
    onSaveIdsig: () -> Unit,
    onNewFile: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (result.isBundle) "AAB je potpisan" else "APK je potpisan",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Potpis je odmah proveren pre nego što je fajl sačuvan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            VerificationBadges(result.report)
            Spacer(Modifier.height(10.dp))
            InfoRow("Sačuvano kao", result.savedName ?: result.fileName)
            InfoRow("Veličina", formatSize(result.sizeBytes))
            InfoRow("Vreme", "${result.durationMs} ms")
            InfoRow("SHA-256 fajla", result.sha256)
            result.signedCertificate?.let { cert ->
                Spacer(Modifier.height(4.dp))
                FingerprintRow("Sertifikat (SHA-256)", cert.sha256)
                InfoRow("Subject", cert.subject)
            }
            if (result.idsigAvailable) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onSaveIdsig, modifier = Modifier.fillMaxWidth()) {
                    Text("Sačuvaj v4 .idsig fajl")
                }
                Text(
                    ".idsig je potreban samo za inkrementalnu instalaciju preko adb-a.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onNewFile, modifier = Modifier.fillMaxWidth()) {
                Text("Potpiši još jedan fajl")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerificationBadges(report: VerificationReport) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (report.v1) StatusBadge("v1 ✓", BadgeTone.Success) else StatusBadge("v1 —", BadgeTone.Neutral)
        if (report.v2) StatusBadge("v2 ✓", BadgeTone.Success) else StatusBadge("v2 —", BadgeTone.Neutral)
        if (report.v3) StatusBadge("v3 ✓", BadgeTone.Success) else StatusBadge("v3 —", BadgeTone.Neutral)
        if (report.v31) StatusBadge("v3.1 ✓", BadgeTone.Success)
        if (report.v4) StatusBadge("v4 ✓", BadgeTone.Success)
        if (report.verified) {
            StatusBadge("potpis proveren", BadgeTone.Success)
        } else {
            StatusBadge("potpis NIJE ispravan", BadgeTone.Error)
        }
    }
}

