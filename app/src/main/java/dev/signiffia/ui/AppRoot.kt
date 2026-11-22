package dev.signiffia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.signiffia.ui.screens.AboutScreen
import dev.signiffia.ui.screens.BackupScreen
import dev.signiffia.ui.screens.CompareScreen
import dev.signiffia.ui.screens.HomeScreen
import dev.signiffia.ui.screens.KeyCreateScreen
import dev.signiffia.ui.screens.KeyDetailScreen
import dev.signiffia.ui.screens.KeyImportScreen
import dev.signiffia.ui.screens.KeysScreen
import dev.signiffia.ui.screens.SecurityScreen
import dev.signiffia.ui.screens.SignScreen
import dev.signiffia.ui.screens.VerifyScreen
import dev.signiffia.ui.lock.AutoLockDelay
import dev.signiffia.ui.lock.LockMode

private enum class Route(val title: String) {
    Home("Signiffia"),
    Sign("Potpisivanje"),
    Verify("Provera potpisa"),
    Compare("Poređenje potpisa"),
    Keys("Ključevi"),
    KeyCreate("Novi ključ"),
    KeyImport("Uvoz keystora"),
    KeyDetail("Detalji ključa"),
    Backup("Backup i vraćanje"),
    Security("Zaključavanje"),
    About("O aplikaciji"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    lockMode: LockMode,
    autoLockDelay: AutoLockDelay,
    isDeviceSecure: Boolean,
    biometricAvailable: Boolean,
    onLockConfigChange: (LockMode, AutoLockDelay) -> Unit,
    vm: AppViewModel = viewModel(),
) {
    var routeName by rememberSaveable { mutableStateOf(Route.Home.name) }
    var detailKeyId by rememberSaveable { mutableStateOf<String?>(null) }
    val route = Route.valueOf(routeName)
    val keys by vm.keys.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val vaultLockedKeyId by vm.vaultLockedKeyId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            vm.consumeMessage()
        }
    }

    fun navigate(target: Route) {
        routeName = target.name
        if (target == Route.Keys) detailKeyId = null
    }

    fun goBack() {
        routeName = when (route) {
            Route.KeyDetail, Route.KeyCreate, Route.KeyImport -> Route.Keys.name
            Route.Home -> Route.Home.name
            else -> Route.Home.name
        }
    }

    BackHandler(enabled = route != Route.Home) { goBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(route.title) },
                navigationIcon = {
                    if (route != Route.Home) {
                        IconButton(onClick = { goBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Nazad")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (route) {
                Route.Home -> HomeScreen(
                    keyCount = keys.size,
                    onSign = { navigate(Route.Sign) },
                    onVerify = { navigate(Route.Verify) },
                    onCompare = { navigate(Route.Compare) },
                    onKeys = { navigate(Route.Keys) },
                    onBackup = { navigate(Route.Backup) },
                    onSecurity = { navigate(Route.Security) },
                    onAbout = { navigate(Route.About) },
                )
                Route.Sign -> SignScreen(vm = vm, onOpenKeys = { navigate(Route.Keys) })
                Route.Verify -> VerifyScreen(vm = vm)
                Route.Compare -> CompareScreen(vm = vm)
                Route.Keys -> KeysScreen(
                    vm = vm,
                    onOpenKey = { id ->
                        detailKeyId = id
                        routeName = Route.KeyDetail.name
                    },
                    onCreate = { navigate(Route.KeyCreate) },
                    onImport = { navigate(Route.KeyImport) },
                )
                Route.KeyCreate -> KeyCreateScreen(
                    vm = vm,
                    onDone = { id ->
                        detailKeyId = id
                        routeName = Route.KeyDetail.name
                    },
                )
                Route.KeyImport -> KeyImportScreen(
                    vm = vm,
                    onDone = { id ->
                        detailKeyId = id
                        routeName = Route.KeyDetail.name
                    },
                )
                Route.KeyDetail -> {
                    val id = detailKeyId
                    if (id == null) {
                        routeName = Route.Keys.name
                    } else {
                        KeyDetailScreen(
                            vm = vm,
                            keyId = id,
                            onBack = { navigate(Route.Keys) },
                            onOpenBackup = { navigate(Route.Backup) },
                        )
                    }
                }
                Route.Backup -> BackupScreen(vm = vm)
                Route.Security -> SecurityScreen(
                    mode = lockMode,
                    delay = autoLockDelay,
                    isDeviceSecure = isDeviceSecure,
                    biometricAvailable = biometricAvailable,
                    onChange = onLockConfigChange,
                )
                Route.About -> AboutScreen()
            }
        }
    }

    if (busy != null) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Sačekaj…", style = MaterialTheme.typography.titleMedium)
                        Text(
                            busy!!.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val lockedId = vaultLockedKeyId
    if (lockedId != null) {
        val lockedRecord = keys.firstOrNull { it.id == lockedId }
        var storePassword by remember(lockedId) { mutableStateOf("") }
        var aliasPasswords by remember(lockedId) { mutableStateOf<Map<String, String>>(emptyMap()) }
        AlertDialog(
            onDismissRequest = { vm.dismissVaultLock() },
            title = { Text("Potrebna lozinka") },
            text = {
                Column {
                    Text(
                        "Lozinka ovog ključa nije dostupna na uređaju. Unesi lozinku keystore-a " +
                            "da je Signiffia ponovo bezbedno sačuva. Ključ se ne menja.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = storePassword,
                        onValueChange = { storePassword = it },
                        label = { Text("Lozinka keystore-a") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (lockedRecord != null && lockedRecord.isMultiAlias) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Lozinke pojedinačnih alijasa (ostavi prazno ako su iste kao lozinka keystore-a):",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        lockedRecord.aliases.forEach { alias ->
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = aliasPasswords[alias] ?: "",
                                onValueChange = { aliasPasswords = aliasPasswords + (alias to it) },
                                label = { Text("Lozinka za \"$alias\"") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (storePassword.isNotEmpty()) {
                        val aliases = lockedRecord?.aliases.orEmpty()
                        val keyPasswords = aliases.associate { alias ->
                            val entered = aliasPasswords[alias]?.trim().orEmpty()
                            alias to (if (entered.isEmpty()) storePassword.toCharArray() else entered.toCharArray())
                        }
                        vm.unlockKey(lockedId, storePassword.toCharArray(), keyPasswords)
                        storePassword = ""
                        aliasPasswords = emptyMap()
                    }
                }) { Text("Otključaj") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissVaultLock() }) { Text("Otkaži") }
            },
        )
    }
}
