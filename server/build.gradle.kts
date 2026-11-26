plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "dev.signiffia"
version = "1.3.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-netty:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
}

application {
    mainClass.set("dev.signiffia.server.MainKt")
}

kotlin {
    jvmToolchain(17)
}
