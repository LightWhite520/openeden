import java.net.URI

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

val localModelArtifactPath = providers
    .environmentVariable("OPENEDEN_LOCAL_MODEL_ARTIFACT")
    .orElse("data/models/local-model-artifact.json")

val localModelArtifactUrl = providers
    .environmentVariable("OPENEDEN_LOCAL_MODEL_ARTIFACT_URL")
    .orElse("https://huggingface.co/0x4C57/openeden-codebook-base-model/resolve/main/local-model-artifact.json")

tasks.register("ensureLocalModelArtifact") {
    group = "openeden"
    description = "Download the OpenEden local model artifact from Hugging Face when it is missing."
    val artifactFile = file(localModelArtifactPath.get())
    outputs.file(artifactFile)
    onlyIf { !artifactFile.exists() }
    doLast {
        artifactFile.parentFile?.mkdirs()
        URI(localModelArtifactUrl.get()).toURL().openStream().use { input ->
            artifactFile.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Downloaded OpenEden local model artifact to ${artifactFile.path}")
    }
}

tasks.named("run") {
    dependsOn("ensureLocalModelArtifact")
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
