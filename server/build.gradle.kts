plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "dev.signiffia"
version = "1.3.1"

repositories {
    mavenCentral()
}

dependencies {
    // HTTP server (Apache-2.0)
    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-netty:2.3.11")

    // Persistent storage: PostgreSQL JDBC (BSD-2-Clause) + HikariCP pool (Apache-2.0)
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // WebAuthn / passkey relying party (Apache-2.0). The actual cryptographic verification is
    // delegated to this FIDO-conformance-tested library (it brings Jackson 3 transitively).
    implementation("com.webauthn4j:webauthn4j-core:0.31.9.RELEASE")

    // Test (Apache-2.0)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.22")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("dev.signiffia.server.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
