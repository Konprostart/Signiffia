package dev.signiffia.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

/**
 * Persists the app-lock configuration in [android.content.SharedPreferences] and provides
 * the small Android platform helpers (device-secure check, keyguard confirm intent and
 * the BiometricPrompt info). Signiffia never stores the PIN/password itself: it only asks
 * Android to confirm the existing device credential, so the OS keeps the secret.
 */
class AppLockSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LockConfig {
        val mode = runCatching {
            LockMode.valueOf(prefs.getString(KEY_MODE, LockMode.OFF.name) ?: LockMode.OFF.name)
        }.getOrDefault(LockMode.OFF)
        val delay = runCatching {
            AutoLockDelay.valueOf(
                prefs.getString(KEY_DELAY, AutoLockDelay.THIRTY_SECONDS.name)
                    ?: AutoLockDelay.THIRTY_SECONDS.name
            )
        }.getOrDefault(AutoLockDelay.THIRTY_SECONDS)
        return LockConfig(mode, delay)
    }

    fun save(mode: LockMode, delay: AutoLockDelay) {
        prefs.edit()
            .putString(KEY_MODE, mode.name)
            .putString(KEY_DELAY, delay.name)
            .apply()
    }

    /** True when the device has a PIN / pattern / password set up. */
    fun isDeviceSecure(): Boolean {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    /** Builds the system keyguard confirm intent, or null when the device is not secure. */
    fun createConfirmDeviceCredentialIntent(): Intent? {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return null
        if (!km.isDeviceSecure) return null
        return km.createConfirmDeviceCredentialIntent(
            "Otključaj Signiffia",
            "Potvrdi identitet da nastaviš.",
        )
    }

    /** True when at least one fingerprint / face is enrolled on the device. */
    fun biometricsEnrolled(): Boolean {
        val bm = BiometricManager.from(appContext)
        val success = BiometricManager.BIOMETRIC_SUCCESS
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == success ||
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == success
    }

    /**
     * Builds the [BiometricPrompt.PromptInfo] for "biometric + device PIN fallback".
     *
     * On Android 11+ (API 30) the strong biometric class and the device credential are
     * combined into a single clean dialog. On older versions we ask for weak biometrics
     * only and always offer the device PIN/pattern/password as a separate explicit button,
     * which avoids the framework crash that strong-biometric + device-credential fallback
     * triggers on Android 9/10.
     */
    fun buildBiometricPromptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Otključaj Signiffia")
            .setSubtitle("Potvrdi identitet")
            .setNegativeButtonText("Otkaži")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        return builder.build()
    }

    private companion object {
        const val PREFS_NAME = "signiffia_app_lock"
        const val KEY_MODE = "lock_mode"
        const val KEY_DELAY = "auto_lock_delay"
    }
}
