plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}


application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("io.openeden.server.db")
        }
    }
}

dependencies {
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.contentNegotiation)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.websockets)
    implementation(libs.logback.classic)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.jna.platform)
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    implementation(project(":core"))
    implementation(project(":onebot"))

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.client.mock)
    testImplementation(ktorLibs.client.websockets)
    testImplementation(ktorLibs.server.testHost)
}

tasks.withType<Test>().configureEach {
    // Gradle's isolated test classloader is not necessarily a URLClassLoader.
    systemProperty("openeden.test.runtimeClasspath", sourceSets["test"].runtimeClasspath.asPath)
}

// Local OAuth operator command; independent from runtime startup and production state.
tasks.register<JavaExec>("chatgptAuth") {
    group = "application"
    description = "Sign in to ChatGPT or manage OpenEden subscription credentials"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.openeden.server.auth.ChatGptAuthCommandKt")
}

tasks.register<JavaExec>("models") {
    group = "application"
    description = "Fetch available models and select a model for OpenEden"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.openeden.server.llm.ModelCommandKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("subscriptionEvaluation") {
    group = "verification"
    description = "Run an explicit JSON evaluation request using the selected ChatGPT account"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.openeden.server.evaluation.SubscriptionEvaluationCommandKt")
}

tasks.register<JavaExec>("relationshipEvaluationProbe") {
    group = "verification"
    description = "Probe the real subscription relationship evaluator without fallback or runtime writes"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.openeden.server.evaluation.RelationshipEvaluationProbeCommandKt")
}
