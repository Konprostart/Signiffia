// Signiffia — see README.md
//
// The optional local Maven repository is used by the offline PocketDev toolchain when it
// exists; normal builds resolve everything from Google / Maven Central.
pluginManagement {
    repositories {
        val localMaven = java.io.File("/root/maven/localMvnRepository")
        if (localMaven.exists()) {
            maven { url = uri(localMaven) }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val localMaven = java.io.File("/root/maven/localMvnRepository")
        if (localMaven.exists()) {
            maven { url = uri(localMaven) }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Signiffia"
include(":app")
