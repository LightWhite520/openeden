plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "io.openeden.trainer.MainKt"
}

tasks.register<JavaExec>("trainLocalModel") {
    group = "openeden"
    description = "Train deterministic local OpenEden model artifacts from data/training/codebook.samples.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.openeden.trainer.MainKt"
    args(
        "--samples", rootProject.layout.projectDirectory.file("data/training/codebook.samples.json").asFile.path,
        "--artifact", rootProject.layout.projectDirectory.file("data/models/local-model-artifact.json").asFile.path,
        "--codebook-csv", rootProject.layout.projectDirectory.file("data/codebook/codebook.generated.csv").asFile.path,
        "--report", rootProject.layout.buildDirectory.file("reports/openeden-training-report.txt").get().asFile.path,
    )
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
