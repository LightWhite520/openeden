plugins {
    application
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.openeden"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.openeden.MainKt"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core"))
    implementation(project(":server"))

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

subprojects {
    group = rootProject.group
    version = "1.0.0-SNAPSHOT"
}
