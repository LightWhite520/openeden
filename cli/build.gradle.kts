plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass = "io.openeden.cli.MainKt"
    applicationName = "openeden"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}

tasks.withType<JavaExec>().configureEach {
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    dependsOn(tasks.named("installDist"))
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation(project(":client"))
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
    implementation(libs.jline.terminal)
    implementation(libs.jline.terminal.jni)
    implementation(libs.jline.reader)
    implementation(libs.mordant)
    implementation(libs.mordant.markdown)

    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
    testImplementation(project(":server"))
    testImplementation(ktorLibs.client.mock)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.pty4j)
}
