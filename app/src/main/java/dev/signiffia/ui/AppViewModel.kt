package dev.signiffia.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.signiffia.core.BackupException
import dev.signiffia.core.BackupManager
import dev.signiffia.core.CertUtils
import dev.signiffia.core.KeystoreException
import dev.signiffia.core.KeystoreTools
import dev.signiffia.core.SchemeOptions
import dev.signiffia.core.SignerCertificate
import dev.signiffia.core.SigningEngine
import dev.signiffia.core.SigningException
import dev.signiffia.core.SplitArchive
import dev.signiffia.core.SplitPart
import dev.signiffia.core.VerificationReport
import dev.signiffia.data.AndroidSecretCipher
import dev.signiffia.data.KeyImportRequest
import dev.signiffia.data.KeyRecord
import dev.signiffia.data.KeyRepository
import dev.signiffia.data.VaultLockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

data class BusyState(val message: String)

data class UiMessage(val text: String, val isError: Boolean = false, val id: Long = System.nanoTime())

/** What the user picked and what Signiffia learned about it. */
data class ApkSummary(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val isBundle: Boolean,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val appLabel: String? = null,
    val icon: ImageBitmap? = null,
    val splitNames: List<String> = emptyList(),
    val isSplitArchive: Boolean = false,
    val splitApkNames: List<String> = emptyList(),
    val signerCertificates: List<SignerCertificate> = emptyList(),
    val sha256: String = "",
) {
    val isApk: Boolean get() = !isBundle && !isSplitArchive
    val kindLabel: String get() = when {
        isBundle -> "AAB (App Bundle)"
        isSplitArchive -> "Split arhiva (APKS/XAPK/APKM)"
        else -> "APK"
    }
}

data class SignUiResult(
    val fileName: String,
    val savedName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val isBundle: Boolean,
    val report: VerificationReport,
    val schemes: SchemeOptions,
    val signedCertificate: SignerCertificate?,
    val durationMs: Long,
    val idsigAvailable: Boolean,
)

data class SignUiState(
    val apk: ApkSummary? = null,
    val selectedKeyId: String? = null,
    val selectedAlias: String? = null,
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
    val v4: Boolean = false,
    val result: SignUiResult? = null,
    val splitResult: SplitUiResult? = null,
    val error: String? = null,
    val pendingIdsigName: String? = null,
)

data class SplitUiResult(
    val fileName: String,
    val savedName: String?,
    val sizeBytes: Long,
    val sha256: String,
    val parts: List<SplitPart>,
    val durationMs: Long,
)

data class SplitVerifyUiResult(
    val archiveName: String,
    val parts: List<Pair<String, VerificationReport>>,
    val durationMs: Long,
) {
    val allVerified: Boolean get() = parts.isNotEmpty() && parts.all { it.second.verified }
    val signerFingerprints: Set<String>
        get() = parts.flatMap { pair -> pair.second.signers.map { it.sha256 } }.toSet()
}

data class VerifyUiResult(
    val summary: ApkSummary,
    val report: VerificationReport,
    val durationMs: Long,
)

data class VerifyUiState(
    val result: VerifyUiResult? = null,
    val split: SplitVerifyUiResult? = null,
    val error: String? = null,
)

data class RestorePreview(
    val file: File,
    val summary: dev.signiffia.core.BackupSummary,
)

data class BackupUiState(
    val restorePreview: RestorePreview? = null,
    val restoreError: String? = null,
    val backupDoneName: String? = null,
    val restoreDoneCount: Int = 0,
)

/**
 * Single source of truth for the UI. Long operations run on [Dispatchers.IO] and always
 * report failures instead of crashing. The original picked file is copied into the app
 * cache and never modified in place.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val cipher = AndroidSecretCipher.create(application)
    private val repository = KeyRepository(File(application.filesDir, "keys"), cipher)
    private val workDir = File(application.cacheDir, "work").apply { mkdirs() }

    private val _keys = MutableStateFlow<List<KeyRecord>>(emptyList())
    val keys: StateFlow<List<KeyRecord>> = _keys.asStateFlow()

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _signState = MutableStateFlow(SignUiState())
    val signState: StateFlow<SignUiState> = _signState.asStateFlow()

    private val _verifyState = MutableStateFlow(VerifyUiState())
    val verifyState: StateFlow<VerifyUiState> = _verifyState.asStateFlow()

    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _vaultLockedKeyId = MutableStateFlow<String?>(null)
    val vaultLockedKeyId: StateFlow<String?> = _vaultLockedKeyId.asStateFlow()

    private var pendingIdsig: File? = null

    init {
        refreshKeys()
        viewModelScope.launch(Dispatchers.IO) { cleanWorkDir() }
    }

    // ------------------------------------------------------------------ keys

    fun refreshKeys() {
        _keys.value = runCatching { repository.list() }.getOrElse {
            _message.value = UiMessage("Ključevi nisu dostupni: ${it.message}", isError = true)
            emptyList()
        }
        _signState.update { state ->
            val current = state.selectedKeyId
            val available = _keys.value
            when {
                available.isEmpty() -> state.copy(selectedKeyId = null, selectedAlias = null)
                current != null && available.any { it.id == current } -> {
                    val record = available.first { it.id == current }
                    val alias = state.selectedAlias?.takeIf { record.aliases.contains(it) }
                        ?: record.primaryAlias
                    state.copy(selectedAlias = alias)
                }
                else -> {
                    val record = available.first()
                    state.copy(selectedKeyId = record.id, selectedAlias = record.primaryAlias)
                }
            }
        }
    }

    fun keyById(id: String?): KeyRecord? = _keys.value.firstOrNull { it.id == id }

    fun createKey(
        displayName: String,
        spec: KeystoreTools.KeyGenSpec,
        onDone: (KeyRecord) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = BusyState("Pravim ključ…")
            try {
                val record = withContext(Dispatchers.IO) {
                    repository.createGenerated(spec, displayName)
                }
                refreshKeys()
                _message.value = UiMessage("Ključ \"${record.displayName}\" je napravljen.")
                onDone(record)
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    fun importKey(
        sourceUri: Uri,
        displayName: String,
        storePassword: CharArray,
        keyPassword: CharArray?,
        onDone: (KeyRecord) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = BusyState("Uvozim keystore…")
            try {
                val record = withContext(Dispatchers.IO) {
                    val local = copyToWork(sourceUri, "import")
                    val type = KeystoreTools.detectType(local)
                    val keyStore = KeystoreTools.load(local, type, storePassword)
                    val keyAliases = KeystoreTools.aliases(keyStore).filter { it.hasPrivateKey }
                    val passwords = keyAliases.associate { it.alias to (keyPassword ?: storePassword) }
                    repository.importKeystore(
                        KeyImportRequest(
                            sourceFile = local,
                            displayName = displayName.ifBlank { queryDisplayName(sourceUri) ?: local.name },
                            storePassword = storePassword,
                            keyPasswords = passwords,
                        )
                    )
                }
                refreshKeys()
                _message.value = UiMessage("Keystore \"${record.displayName}\" je uvezen.")
                onDone(record)
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    data class AliasDetail(
        val alias: String,
        val certificate: SignerCertificate,
        val chainLength: Int,
    )

    /** Reads certificate details lazily for the key detail screen. */
    suspend fun aliasDetails(id: String): Result<List<AliasDetail>> = withContext(Dispatchers.IO) {
        runCatching {
            val record = repository.get(id) ?: throw KeystoreException("Ključ nije pronađen.")
            val (storePassword, _) = repository.passwords(record)
            val keyStore = KeystoreTools.load(
                repository.keystoreFile(record), record.storeType, storePassword
            )
            KeystoreTools.aliases(keyStore)
                .filter { it.hasPrivateKey }
                .mapNotNull { info ->
                    info.certificate?.let { cert ->
                        AliasDetail(info.alias, SignerCertificate.from(cert), info.chainLength)
                    }
                }
        }
    }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { repository.delete(id) }
                refreshKeys()
                _message.value = if (removed) {
                    UiMessage("Ključ je obrisan sa uređaja.")
                } else {
                    UiMessage("Ključ nije pronađen.", isError = true)
                }
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            }
        }
    }

    fun renameKey(id: String, name: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.rename(id, name) }
                refreshKeys()
                _message.value = UiMessage("Naziv je promenjen.")
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            }
        }
    }

    fun exportKey(id: String, destination: Uri, keyFileName: String) {
        viewModelScope.launch {
            _busy.value = BusyState("Izvozim keystore…")
            try {
                withContext(Dispatchers.IO) {
                    val record = repository.get(id) ?: throw KeystoreException("Ključ nije pronađen.")
                    app.contentResolver.openOutputStream(destination)?.use { output ->
                        repository.exportKeystore(record, output)
                    } ?: throw KeystoreException("Nije moguće otvoriti izabranu lokaciju.")
                }
                _message.value = UiMessage("Keystore je izvezen kao \"$keyFileName\".")
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Re-enters keystore passwords after the Android Keystore key was lost. */
    fun unlockKey(id: String, storePassword: CharArray) {
        viewModelScope.launch {
            _busy.value = BusyState("Otključavam ključ…")
            try {
                withContext(Dispatchers.IO) {
                    val record = repository.get(id) ?: throw KeystoreException("Ključ nije pronađen.")
                    repository.setPasswords(
                        id, storePassword,
                        record.aliases.associateWith { storePassword },
                    )
                }
                _vaultLockedKeyId.value = null
                refreshKeys()
                _message.value = UiMessage("Lozinka je sačuvana i ključ je ponovo dostupan.")
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    fun dismissVaultLock() {
        _vaultLockedKeyId.value = null
    }

    // ------------------------------------------------------------------ sign

    fun selectKey(keyId: String, alias: String? = null) {
        val record = keyById(keyId) ?: return
        _signState.update {
            it.copy(
                selectedKeyId = record.id,
                selectedAlias = alias?.takeIf { a -> record.aliases.contains(a) } ?: record.primaryAlias,
                result = null,
                error = null,
            )
        }
    }

    fun selectAlias(alias: String) {
        _signState.update { it.copy(selectedAlias = alias, result = null, error = null) }
    }

    fun setSchemes(v1: Boolean, v2: Boolean, v3: Boolean, v4: Boolean) {
        _signState.update {
            it.copy(v1 = v1, v2 = if (v3) true else v2, v3 = v3, v4 = v4, result = null, error = null)
        }
    }

    fun loadSignInput(uri: Uri) {
        viewModelScope.launch {
            _busy.value = BusyState("Učitavam fajl…")
            _signState.update { it.copy(result = null, splitResult = null, error = null) }
            try {
                val summary = withContext(Dispatchers.IO) {
                    val local = copyToWork(uri, "input")
                    readSummary(local, queryDisplayName(uri) ?: local.name)
                }
                _signState.update { state ->
                    state.copy(
                        apk = summary,
                        v1 = true,
                        v2 = !summary.isBundle,
                        v3 = !summary.isBundle,
                        v4 = false,
                    )
                }
            } catch (t: Throwable) {
                _signState.update { it.copy(error = t.friendlyMessage(), apk = null) }
            } finally {
                _busy.value = null
            }
        }
    }

    fun clearSignInput() {
        _signState.value = SignUiState()
        pendingIdsig = null
        refreshKeys()
    }

    /**
     * Signs the loaded file and writes it to the SAF destination chosen by the user.
     * The signed file is verified before it is copied out.
     */
    fun signTo(destination: Uri) {
        val state = _signState.value
        val summary = state.apk ?: return
        if (summary.isSplitArchive) {
            signSplitArchiveTo(destination)
            return
        }
        val record = keyById(state.selectedKeyId)
        if (record == null) {
            _signState.update { it.copy(error = "Izaberite ključ za potpisivanje.") }
            return
        }
        val alias = state.selectedAlias ?: record.primaryAlias
        viewModelScope.launch {
            _busy.value = BusyState(if (summary.isBundle) "Potpisujem AAB…" else "Potpisujem APK…")
            val started = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.IO) {
                    val identity = repository.resolveIdentity(record, alias)
                    val extension = if (summary.isBundle) "aab" else "apk"
                    val output = File(workDir, "signed-${System.currentTimeMillis()}.$extension")
                    val idsig = if (state.v4 && !summary.isBundle) File(workDir, output.name + ".idsig") else null
                    val minSdk = summary.minSdk ?: SigningEngine.DEFAULT_MIN_SDK
                    val outcome = if (summary.isBundle) {
                        SigningEngine.signBundle(summary.file, output, identity)
                    } else {
                        SigningEngine.signApk(
                            input = summary.file,
                            output = output,
                            identity = identity,
                            schemes = SchemeOptions(state.v1, state.v2, state.v3, state.v4),
                            minSdk = minSdk,
                            idsigFile = idsig,
                        )
                    }
                    val sha = sha256Of(outcome.output)
                    val savedName = writeToSaf(destination, outcome.output)
                    pendingIdsig = outcome.idsigFile
                    SignUiResult(
                        fileName = outcome.output.name,
                        savedName = savedName,
                        sizeBytes = outcome.output.length(),
                        sha256 = sha,
                        isBundle = summary.isBundle,
                        report = outcome.report,
                        schemes = outcome.schemes,
                        signedCertificate = outcome.report.signers.firstOrNull(),
                        durationMs = System.currentTimeMillis() - started,
                        idsigAvailable = outcome.idsigFile != null,
                    )
                }
                _signState.update {
                    it.copy(
                        result = result,
                        splitResult = null,
                        error = null,
                        pendingIdsigName = result.takeIf { r -> r.idsigAvailable }?.let { "idsig" },
                    )
                }
                _message.value = UiMessage("Potpisivanje je uspelo i potpis je proveren.")
            } catch (t: Throwable) {
                if (t is VaultLockedException) {
                    _vaultLockedKeyId.value = record.id
                }
                _signState.update { it.copy(error = t.friendlyMessage()) }
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Signs every APK part of a split archive with the same identity. */
    private fun signSplitArchiveTo(destination: Uri) {
        val state = _signState.value
        val summary = state.apk ?: return
        val record = keyById(state.selectedKeyId)
        if (record == null) {
            _signState.update { it.copy(error = "Izaberite ključ za potpisivanje.") }
            return
        }
        val alias = state.selectedAlias ?: record.primaryAlias
        viewModelScope.launch {
            _busy.value = BusyState("Potpisujem ${summary.splitApkNames.size} APK delova…")
            val started = System.currentTimeMillis()
            try {
                val (result, sha) = withContext(Dispatchers.IO) {
                    val identity = repository.resolveIdentity(record, alias)
                    val output = File(workDir, "signed-${System.currentTimeMillis()}.zip")
                    val outcome = SplitArchive.sign(
                        input = summary.file,
                        output = output,
                        identity = identity,
                        schemes = SchemeOptions(state.v1, state.v2, state.v3, false),
                        minSdk = summary.minSdk ?: SigningEngine.DEFAULT_MIN_SDK,
                        workDir = workDir,
                    )
                    val digest = sha256Of(outcome.output)
                    val saved = writeToSaf(destination, outcome.output)
                    SplitUiResult(
                        fileName = outcome.output.name,
                        savedName = saved,
                        sizeBytes = outcome.output.length(),
                        sha256 = digest,
                        parts = outcome.parts,
                        durationMs = System.currentTimeMillis() - started,
                    ) to digest
                }
                _signState.update { it.copy(splitResult = result, result = null, error = null) }
                _message.value = UiMessage("Svi delovi su potpisani i provereni.")
            } catch (t: Throwable) {
                if (t is VaultLockedException) {
                    _vaultLockedKeyId.value = record.id
                }
                _signState.update { it.copy(error = t.friendlyMessage()) }
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    fun savePendingIdsig(destination: Uri) {
        val idsig = pendingIdsig ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(destination)?.use { output ->
                        FileInputStream(idsig).use { it.copyTo(output) }
                    } ?: throw KeystoreException("Nije moguće otvoriti izabranu lokaciju.")
                }
                _message.value = UiMessage("v4 .idsig fajl je sačuvan.")
                _signState.update { it.copy(pendingIdsigName = null) }
                pendingIdsig = null
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            }
        }
    }

    fun clearSignResult() {
        _signState.update { it.copy(result = null, splitResult = null, error = null) }
    }

    fun suggestedOutputName(suffix: String = "-signed"): String {
        val summary = _signState.value.apk ?: return "signed"
        val base = summary.displayName.substringBeforeLast('.').ifBlank { "app" }
        val extension = when {
            summary.isBundle -> "aab"
            summary.isSplitArchive -> summary.displayName.substringAfterLast('.', "zip").ifBlank { "zip" }
            else -> "apk"
        }
        return "$base$suffix.$extension"
    }

    // ---------------------------------------------------------------- verify

    fun verifyInput(uri: Uri) {
        viewModelScope.launch {
            _busy.value = BusyState("Proveravam potpis…")
            _verifyState.value = VerifyUiState()
            val started = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.IO) {
                    val local = copyToWork(uri, "verify")
                    val summary = readSummary(local, queryDisplayName(uri) ?: local.name)
                    if (summary.isSplitArchive) {
                        val split = SplitArchive.verify(
                            local,
                            minSdk = summary.minSdk ?: SigningEngine.DEFAULT_MIN_SDK,
                            workDir = workDir,
                        )
                        _verifyState.value = VerifyUiState(
                            split = SplitVerifyUiResult(
                                archiveName = summary.displayName,
                                parts = split.parts,
                                durationMs = System.currentTimeMillis() - started,
                            )
                        )
                        return@withContext null
                    }
                    val report = if (summary.isBundle) {
                        SigningEngine.verifyJar(local)
                    } else {
                        SigningEngine.verify(local, minSdk = summary.minSdk ?: SigningEngine.DEFAULT_MIN_SDK)
                    }
                    VerifyUiResult(summary, report, System.currentTimeMillis() - started)
                }
                if (result != null) _verifyState.value = VerifyUiState(result = result)
            } catch (t: Throwable) {
                _verifyState.value = VerifyUiState(error = t.friendlyMessage())
            } finally {
                _busy.value = null
            }
        }
    }

    fun clearVerify() {
        _verifyState.value = VerifyUiState()
    }

    // ---------------------------------------------------------------- backup

    fun backupTo(destination: Uri, passphrase: CharArray, ids: List<String>) {
        viewModelScope.launch {
            _busy.value = BusyState("Pravim backup…")
            try {
                val count = withContext(Dispatchers.IO) {
                    val records = repository.list().filter { ids.isEmpty() || ids.contains(it.id) }
                    if (records.isEmpty()) throw KeystoreException("Izaberite najmanje jedan ključ.")
                    val entries = repository.backupEntries(records)
                    val temp = File(workDir, "backup-${System.currentTimeMillis()}.sgnbak")
                    try {
                        BackupManager.write(temp, passphrase, entries)
                        app.contentResolver.openOutputStream(destination)?.use { output ->
                            FileInputStream(temp).use { it.copyTo(output) }
                        } ?: throw KeystoreException("Nije moguće otvoriti izabranu lokaciju.")
                    } finally {
                        temp.delete()
                    }
                    records.size
                }
                _backupState.update { it.copy(backupDoneName = "$count ključeva") }
                _message.value = UiMessage("Backup je napravljen i proveren.")
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Opens the backup with the passphrase and shows what it contains before restoring. */
    fun previewRestore(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _busy.value = BusyState("Otvaram backup…")
            try {
                val preview = withContext(Dispatchers.IO) {
                    val local = copyToWork(uri, "backup")
                    val summary = BackupManager.read(local, passphrase)
                    RestorePreview(local, summary)
                }
                _backupState.update { it.copy(restorePreview = preview, restoreError = null) }
                _message.value = UiMessage("Backup je ispravan. Proverite sadržaj i vratite ključeve.")
            } catch (t: Throwable) {
                _backupState.update { it.copy(restorePreview = null, restoreError = t.friendlyMessage()) }
            } finally {
                _busy.value = null
            }
        }
    }

    fun confirmRestore(onDone: () -> Unit) {
        val preview = _backupState.value.restorePreview ?: return
        viewModelScope.launch {
            _busy.value = BusyState("Vraćam ključeve…")
            try {
                val restored = withContext(Dispatchers.IO) {
                    repository.restore(preview.summary.keys)
                }
                refreshKeys()
                _backupState.update {
                    it.copy(restorePreview = null, restoreDoneCount = restored.size)
                }
                _message.value = UiMessage("Vraćeno ${restored.size} ključeva.")
                onDone()
            } catch (t: Throwable) {
                _message.value = UiMessage(t.friendlyMessage(), isError = true)
            } finally {
                _busy.value = null
            }
        }
    }

    fun clearRestorePreview() {
        _backupState.update { it.copy(restorePreview = null, restoreError = null) }
    }

    // -------------------------------------------------------------- internals

    private fun readSummary(local: File, displayName: String): ApkSummary {
        val isBundle = local.extension.equals("aab", ignoreCase = true) || isBundleArchive(local)
        val splitNames = if (isBundle) emptyList() else SplitArchive.apkEntries(local)
        val isSplitArchive = !isBundle && splitNames.isNotEmpty()
        val base = ApkSummary(
            file = local,
            displayName = displayName,
            sizeBytes = local.length(),
            isBundle = isBundle,
            isSplitArchive = isSplitArchive,
            splitApkNames = splitNames,
            sha256 = sha256Of(local),
        )
        if (isBundle || isSplitArchive) return base
        return runCatching { enrichWithPackageInfo(base) }.getOrElse { base }
    }

    private fun enrichWithPackageInfo(base: ApkSummary): ApkSummary {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES or
            android.content.pm.PackageManager.GET_META_DATA
        val info = pm.getPackageArchiveInfo(base.file.absolutePath, flags) ?: return base
        val appInfo = info.applicationInfo
        var label: String? = null
        var icon: ImageBitmap? = null
        if (appInfo != null) {
            appInfo.sourceDir = base.file.absolutePath
            appInfo.publicSourceDir = base.file.absolutePath
            label = runCatching { appInfo.loadLabel(pm).toString() }.getOrNull()
            icon = runCatching { appInfo.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        val signatures = apkSignatures(info)
        val signers = runCatching {
            signatures.mapNotNull { signature ->
                runCatching {
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(signature.toByteArray().inputStream()) as X509Certificate
                    SignerCertificate.from(cert)
                }.getOrNull()
            }.distinctBy { it.sha256 }
        }.getOrDefault(emptyList())
        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo?.minSdkVersion else null
        return base.copy(
            packageName = info.packageName,
            versionName = info.versionName,
            versionCode = runCatching { PackageInfoCompat.getLongVersionCode(info) }.getOrDefault(0L),
            minSdk = minSdk?.takeIf { it > 0 },
            targetSdk = appInfo?.targetSdkVersion,
            appLabel = label,
            icon = icon,
            splitNames = info.splitNames?.toList().orEmpty(),
            signerCertificates = signers,
        )
    }

    /** Extracts the signing certificates of an APK file (not an installed package). */
    private fun apkSignatures(info: android.content.pm.PackageInfo): List<android.content.pm.Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            val current = signingInfo.apkContentsSigners?.toList().orEmpty()
            if (current.isNotEmpty()) return current
            return signingInfo.signingCertificateHistory?.toList().orEmpty()
        }
        @Suppress("DEPRECATION")
        return info.signatures?.toList().orEmpty()
    }

    private fun isBundleArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("BundleConfig.pb") != null || zip.getEntry("base/manifest/AndroidManifest.xml") != null
        }
    }.getOrDefault(false)

    /** Copies the picked SAF document into the app cache; the original is never touched. */
    private fun copyToWork(uri: Uri, prefix: String): File {
        val name = queryDisplayName(uri) ?: "input"
        val extension = name.substringAfterLast('.', "").ifBlank { "bin" }.take(12)
        val target = File(
            workDir,
            "$prefix-${System.nanoTime()}.${extension.replace(Regex("[^A-Za-z0-9]"), "")}"
        )
        val input: InputStream = app.contentResolver.openInputStream(uri)
            ?: throw KeystoreException("Nije moguće otvoriti izabrani fajl.")
        input.use { source ->
            FileOutputStream(target).use { sink -> source.copyTo(sink, 256 * 1024) }
        }
        if (target.length() == 0L) throw KeystoreException("Izabrani fajl je prazan.")
        return target
    }

    private fun writeToSaf(destination: Uri, source: File): String {
        val output = app.contentResolver.openOutputStream(destination)
            ?: throw KeystoreException("Nije moguće otvoriti izabranu lokaciju za upis.")
        output.use { sink -> FileInputStream(source).use { it.copyTo(sink, 256 * 1024) } }
        return queryDisplayName(destination) ?: destination.lastPathSegment ?: "izlaz"
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanWorkDir() {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        workDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    companion object {
        fun fingerprintShort(fingerprint: String): String =
            if (fingerprint.length <= 23) fingerprint else fingerprint.take(23) + "…"
    }
}

/** Turns technical exceptions into short, actionable Serbian messages. */
fun Throwable.friendlyMessage(): String = when (this) {
    is VaultLockedException -> message ?: "Lozinka ključa nije dostupna."
    is BackupException -> message ?: "Backup nije moguće obraditi."
    is KeystoreException -> message ?: "Greška u keystore-u."
    is SigningException -> message ?: "Greška pri potpisivanju."
    is java.io.FileNotFoundException -> "Fajl nije pronađen ili mu nije moguće pristupiti."
    is SecurityException -> "Android je odbio pristup fajlu."
    is OutOfMemoryError -> "Nema dovoljno memorije za ovaj fajl."
    else -> message ?: "Došlo je do neočekivane greške (${javaClass.simpleName})."
}

fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return "—"
    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(millis))
}
