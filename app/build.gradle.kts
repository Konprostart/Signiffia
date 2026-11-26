plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystore = rootProject.file("signing/signiffia-release.jks")

// Release signing is opt-in and explicit. The release variant is NEVER signed with the
// local debug key: when credentials are missing it is left unsigned, and the guard below
// fails the release build loudly instead of producing a misleading "release" APK.
val releaseStorePassword = System.getenv("SIGNIFFIA_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("SIGNIFFIA_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNIFFIA_KEY_PASSWORD")
val canSignRelease = releaseKeystore.exists() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

// Fail fast, before any work, when a release build is requested without explicit
// signing credentials. This is the "clear configuration check" that must never let a
// debug key sneak into a production release.
if (!canSignRelease) {
    val requested = gradle.startParameter.taskNames
    val wantsRelease = requested.any {
        it.contains("Release", ignoreCase = true)
    }
    if (wantsRelease) {
        throw GradleException(
            "Release build requested, but no signing credentials are configured.\n" +
                "Create the keystore at signing/signiffia-release.jks and set the environment " +
                "variables SIGNIFFIA_STORE_PASSWORD, SIGNIFFIA_KEY_ALIAS and " +
                "SIGNIFFIA_KEY_PASSWORD (see README.md), or build the debug variant " +
                "(assembleDebug) instead.\n" +
                "Signiffia never signs a release with the debug key."
        )
    }
}

android {
    namespace = "dev.signiffia"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.signiffia"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No fallback to the debug key. Without credentials the release APK is
            // explicitly unsigned (and the configuration guard above rejects an explicit
            // assembleRelease request anyway).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST"
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
    lint {
        abortOnError = false
        // Release builds must work fully offline; lint vital would need extra downloads.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    // Optional app lock: BiometricPrompt backport (Apache-2.0) + FragmentActivity host.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.6.2")
    // The fragment library's transitive chain still pins the long-frozen androidx
    // "legacy" artifacts below. The offline Maven mirror ships those old versions as
    // POM-only stubs (no AAR), so pin the newer, fully-mirrored releases explicitly.
    implementation("androidx.annotation:annotation-experimental:1.3.0")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Passkey / WebAuthn client (Apache-2.0). Requires a credential provider (Google Play
    // Services) at runtime and Android 14+ for the full passkey UX; password login remains
    // the always-available fallback.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")

    // Official Android APK signing library (Apache-2.0)
    implementation("com.android.tools.build:apksig:8.11.0")
    // BouncyCastle: certificate generation, PKCS#7 (CMS) and PKCS#12 support (MIT-style licence)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    implementation("org.bouncycastle:bcutil-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
