package dev.signiffia

import android.app.Application
import dev.signiffia.core.KeystoreTools

class SigniffiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Replace Android's limited built-in BouncyCastle with the bundled full version so
        // that JKS keystores and certificate generation work on every supported device.
        KeystoreTools.ensureProviders()
    }
}
