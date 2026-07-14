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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

val localModelArtifactPath = providers
    .environmentVariable("OPENEDEN_LOCAL_MODEL_ARTIFACT")
    .orElse("data/models/local-model-artifact.json")

val localModelArtifactUrl = providers
    .environmentVariable("OPENEDEN_LOCAL_MODEL_ARTIFACT_URL")
    .orElse("https://huggingface.co/0x4C57/openeden-codebook-base-model/resolve/main/local-model-artifact.json")

val thymosAffectModelPath = providers
    .environmentVariable("OPENEDEN_DJL_AFFECT_MODEL_PATH")
    .orElse("data/models/user-affect-qwen")

val thymosAffectModelRepo = providers
    .environmentVariable("OPENEDEN_DJL_AFFECT_MODEL_URL")
    .orElse("https://huggingface.co/0x4C57/Thymos-6D/resolve/main")

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

tasks.register("ensureThymosAffectModel") {
    group = "openeden"
    description = "Download the Thymos-6D affect model bundle from Hugging Face when it is missing."
    val modelDir = file(thymosAffectModelPath.get())
    val requiredFiles = listOf("model.pt", "tokenizer.json", "metadata.json").map(modelDir::resolve)
    outputs.files(requiredFiles)
    onlyIf { requiredFiles.any { !it.exists() } }
    doLast {
        modelDir.mkdirs()
        requiredFiles.forEach { target ->
            val temporary = target.resolveSibling("${target.name}.download")
            URI("${thymosAffectModelRepo.get()}/${target.name}").toURL().openStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
        logger.lifecycle("Downloaded Thymos-6D affect model to ${modelDir.path}")
    }
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
    implementation(libs.logback.classic)
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

subprojects {
    group = rootProject.group
    version = "1.0.0-SNAPSHOT"
}
