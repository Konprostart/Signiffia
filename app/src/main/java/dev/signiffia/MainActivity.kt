package dev.signiffia

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.signiffia.ui.AppRoot
import dev.signiffia.ui.lock.AppLockGate
import dev.signiffia.ui.lock.AppLockSettings
import dev.signiffia.ui.lock.AutoLockDelay
import dev.signiffia.ui.lock.LockConfig
import dev.signiffia.ui.lock.LockMode
import dev.signiffia.ui.lock.LockScreen
import dev.signiffia.ui.lock.shouldRelockOnForeground
import dev.signiffia.ui.theme.SigniffiaTheme

class MainActivity : FragmentActivity() {

    private lateinit var appLockSettings: AppLockSettings
    private lateinit var biometricPrompt: BiometricPrompt

    private val lockConfig = mutableStateOf(LockConfig())
    private val unlocked = mutableStateOf(true)
    private val lockError = mutableStateOf<String?>(null)

    private var backgroundedAtMillis = 0L

    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        AppLockGate.exit()
        if (result.resultCode == Activity.RESULT_OK) {
            markUnlocked()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appLockSettings = AppLockSettings(this)
        val initialConfig = appLockSettings.load()
        lockConfig.value = initialConfig
        // Cold start: when the lock is enabled the app always starts locked.
        unlocked.value = !initialConfig.enabled
        applySecureFlag(initialConfig)

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            biometricCallback(),
        )

        setContent {
            SigniffiaTheme {
                val config by lockConfig
                val isUnlocked by unlocked
                val error by lockError
                if (isUnlocked) {
                    AppRoot(
                        lockMode = config.mode,
                        autoLockDelay = config.autoLockDelay,
                        isDeviceSecure = appLockSettings.isDeviceSecure(),
                        biometricAvailable = appLockSettings.biometricsEnrolled(),
                        onLockConfigChange = ::updateLockConfig,
                    )
                } else {
                    LockScreen(
                        mode = config.mode,
                        isDeviceSecure = appLockSettings.isDeviceSecure(),
                        biometricAvailable = appLockSettings.biometricsEnrolled(),
                        error = error,
                        onUnlockBiometric = { launchAuthentication(LockMode.BIOMETRIC_OR_CREDENTIAL) },
                        onUnlockDeviceCredential = { launchAuthentication(LockMode.DEVICE_CREDENTIAL) },
                        onDisableLock = { updateLockConfig(LockMode.OFF, config.autoLockDelay) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Do not re-lock while a system flow the app launched itself (SAF picker,
        // keyguard confirm or the biometric prompt) is still in flight.
        if (AppLockGate.isExternalFlowActive) return
        if (shouldRelockOnForeground(
                lockConfig.value,
                backgroundedAtMillis,
                SystemClock.elapsedRealtime(),
            )
        ) {
            unlocked.value = false
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAtMillis = SystemClock.elapsedRealtime()
    }

    private fun biometricCallback() = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            AppLockGate.exit()
            markUnlocked()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            AppLockGate.exit()
            val canceled = errorCode == BiometricPrompt.ERROR_CANCELED ||
                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                errorCode == BiometricPrompt.ERROR_USER_CANCELED
            lockError.value = if (canceled) null else errString.toString()
        }
    }

    private fun launchAuthentication(mode: LockMode) {
        AppLockGate.enter()
        when (mode) {
            LockMode.DEVICE_CREDENTIAL -> {
                val intent = appLockSettings.createConfirmDeviceCredentialIntent()
                if (intent == null) {
                    AppLockGate.exit()
                    lockError.value = "Na uređaju nije podešen PIN, šifra ili šara."
                    return
                }
                credentialLauncher.launch(intent)
            }
            LockMode.BIOMETRIC_OR_CREDENTIAL -> {
                biometricPrompt.authenticate(appLockSettings.buildBiometricPromptInfo())
            }
            LockMode.OFF -> {
                AppLockGate.exit()
                markUnlocked()
            }
        }
    }

    private fun markUnlocked() {
        lockError.value = null
        unlocked.value = true
    }

    private fun updateLockConfig(mode: LockMode, delay: AutoLockDelay) {
        appLockSettings.save(mode, delay)
        lockConfig.value = LockConfig(mode, delay)
        applySecureFlag(lockConfig.value)
        if (!lockConfig.value.enabled) {
            unlocked.value = true
            lockError.value = null
        }
    }

    private fun applySecureFlag(config: LockConfig) {
        if (config.enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
