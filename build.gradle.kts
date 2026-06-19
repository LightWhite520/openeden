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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

subprojects {
    group = rootProject.group
    version = "1.0.0-SNAPSHOT"
}
