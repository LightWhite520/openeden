# Module Boundary Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the repository root into an aggregation project by moving the reusable API client into `:client`, the terminal application into a new `:cli` module, and core-owned JVM tests into `:core`.

**Architecture:** The production dependency path becomes `cli -> client`, while `server -> core` remains unchanged. The root retains only shared Gradle configuration and operator utility tasks; no production or test source set remains at the root.

**Tech Stack:** Kotlin 2.3, Kotlin Multiplatform, Kotlin/JVM 21, Ktor Client 3.5, kotlinx.serialization, Gradle Kotlin DSL, kotlin.test

---

## File Map

- Create `cli/build.gradle.kts`: JVM application and terminal-specific dependencies.
- Modify `settings.gradle.kts`: include `:cli`.
- Modify `build.gradle.kts`: remove root application/JVM source ownership while retaining shared artifact utility tasks.
- Modify `client/build.gradle.kts`: configure the reusable KMP HTTP/SSE client and tests.
- Delete `client/src/commonMain/kotlin/HttpClient.kt`: remove the temporary global client.
- Move `src/main/kotlin/io/openeden/client/**` to `client/src/commonMain/kotlin/io/openeden/client/**`.
- Move `src/test/kotlin/io/openeden/client/**` to `client/src/commonTest/kotlin/io/openeden/client/**`.
- Modify `client/src/commonMain/kotlin/io/openeden/client/SseEventParser.kt`: replace the JVM-only byte stream buffer.
- Move `src/main/kotlin/io/openeden/cli/**` to `cli/src/main/kotlin/io/openeden/cli/**`.
- Move `src/main/kotlin/io/openeden/config/**` to `cli/src/main/kotlin/io/openeden/config/**`.
- Move CLI and config tests to matching paths under `cli/src/test/kotlin`.
- Move `src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt` to `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`.
- Move `src/test/kotlin/io/openeden/compatibility/PublicConstructorBinaryCompatibilityTest.kt` to `core/src/jvmTest/kotlin/io/openeden/compatibility/PublicConstructorBinaryCompatibilityTest.kt`.
- Modify `core/build.gradle.kts`: add the Ktor mock engine to `jvmTest`.

### Task 1: Record the pre-migration verification baseline

**Files:**
- Test: existing root, client, core, and server test suites

- [ ] **Step 1: Verify the current root CLI and shared modules before moving files**

Run:

```powershell
.\gradlew.bat test :client:allTests :core:allTests :server:test
```

Expected: BUILD SUCCESSFUL. If a pre-existing failure occurs, record it before moving files and do not hide it with module changes.

- [ ] **Step 2: Confirm the worktree contains no unrelated paths that overlap the migration**

Run:

```powershell
git status --short
```

Expected: only already-known user changes, or an empty worktree. Preserve unrelated changes.

### Task 2: Move the reusable OpenEden client into `:client`

**Files:**
- Modify: `client/build.gradle.kts`
- Delete: `client/src/commonMain/kotlin/HttpClient.kt`
- Move: `src/main/kotlin/io/openeden/client/**`
- Move: `src/test/kotlin/io/openeden/client/**`
- Modify: `client/src/commonMain/kotlin/io/openeden/client/SseEventParser.kt`
- Test: `client/src/commonTest/kotlin/io/openeden/client/OpenEdenServerClientTest.kt`
- Test: `client/src/commonTest/kotlin/io/openeden/client/SseEventParserTest.kt`

- [ ] **Step 1: Move the existing client tests first**

Run:

```powershell
New-Item -ItemType Directory -Force 'client/src/commonTest/kotlin/io/openeden' | Out-Null
git mv 'src/test/kotlin/io/openeden/client' 'client/src/commonTest/kotlin/io/openeden/client'
```

- [ ] **Step 2: Run the client tests to verify the module does not yet provide the moved API**

Run:

```powershell
.\gradlew.bat :client:allTests
```

Expected: FAIL because `OpenEdenServerClient`, its DTOs, and serialization test dependencies are not available from `:client` yet.

- [ ] **Step 3: Move the production client and remove the temporary global HTTP client**

Run:

```powershell
New-Item -ItemType Directory -Force 'client/src/commonMain/kotlin/io/openeden' | Out-Null
git mv 'src/main/kotlin/io/openeden/client' 'client/src/commonMain/kotlin/io/openeden/client'
```

Delete `client/src/commonMain/kotlin/HttpClient.kt` with `apply_patch`; it is the temporary global client and has no consumers.

- [ ] **Step 4: Make the SSE buffer multiplatform-safe**

In `client/src/commonMain/kotlin/io/openeden/client/SseEventParser.kt`, remove `java.io.ByteArrayOutputStream` and implement `parse(chunks)` with a common Kotlin buffer:

```kotlin
fun parse(chunks: Flow<ByteArray>): Flow<ChatStreamEvent> = flow {
    var pending = ByteArray(0)
    chunks.collect { chunk ->
        val bytes = pending + chunk
        var frameStart = 0
        while (true) {
            val boundary = findFrameBoundary(bytes, frameStart) ?: break
            decodeFrame(bytes.copyOfRange(frameStart, boundary.frameEnd).decodeToString())
                ?.let { emit(it) }
            frameStart = boundary.nextFrameStart
        }
        pending = bytes.copyOfRange(frameStart, bytes.size)
    }
    val trailing = pending.decodeToString()
    if (trailing.isNotBlank()) decodeFrame(trailing)?.let { emit(it) }
}
```

- [ ] **Step 5: Configure the KMP client module**

Replace `client/build.gradle.kts` with:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(ktorLibs.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.client.mock)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

Do not retain `implementation(project(":core"))`; public HTTP wire contracts must not depend on runtime internals.

- [ ] **Step 6: Run the extracted client tests**

Run:

```powershell
.\gradlew.bat :client:allTests
```

Expected: BUILD SUCCESSFUL and both `OpenEdenServerClientTest` and `SseEventParserTest` pass on configured targets.

- [ ] **Step 7: Commit the client extraction**

Run:

```powershell
git add client src/main/kotlin/io/openeden/client src/test/kotlin/io/openeden/client
git commit -m "refactor(client): move server client into module"
```

Expected commit title: `refactor(client): move server client into module`.

### Task 3: Create `:cli` and move terminal production code

**Files:**
- Create: `cli/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Move: `src/main/kotlin/io/openeden/cli/**`
- Move: `src/main/kotlin/io/openeden/config/**`
- Move: `src/test/kotlin/io/openeden/cli/**`
- Move: `src/test/kotlin/io/openeden/config/**`

- [ ] **Step 1: Move CLI tests before production sources**

Run:

```powershell
New-Item -ItemType Directory -Force 'cli/src/test/kotlin/io/openeden' | Out-Null
git mv 'src/test/kotlin/io/openeden/cli' 'cli/src/test/kotlin/io/openeden/cli'
git mv 'src/test/kotlin/io/openeden/config' 'cli/src/test/kotlin/io/openeden/config'
```

- [ ] **Step 2: Add `:cli` to the build and verify it fails without implementation**

Add this line to `settings.gradle.kts` beside the other module includes:

```kotlin
include(":cli")
```

Create an initial `cli/build.gradle.kts`:

```kotlin
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass = "io.openeden.cli.MainKt"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client"))
    testImplementation(kotlin("test"))
}
```

Run:

```powershell
.\gradlew.bat :cli:test
```

Expected: FAIL because terminal production classes and their dependencies have not moved yet.

- [ ] **Step 3: Move CLI and CLI configuration production sources**

Run:

```powershell
New-Item -ItemType Directory -Force 'cli/src/main/kotlin/io/openeden' | Out-Null
git mv 'src/main/kotlin/io/openeden/cli' 'cli/src/main/kotlin/io/openeden/cli'
git mv 'src/main/kotlin/io/openeden/config' 'cli/src/main/kotlin/io/openeden/config'
```

- [ ] **Step 4: Configure the complete CLI application module**

Replace `cli/build.gradle.kts` with:

```kotlin
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass = "io.openeden.cli.MainKt"
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
```

Production code intentionally has no `project(":core")` dependency. The test-only dependency supports the existing transcript restart integration fixture.

- [ ] **Step 5: Run the CLI tests and distribution smoke task**

Run:

```powershell
.\gradlew.bat :cli:test :cli:installDist
```

Expected: BUILD SUCCESSFUL. Existing terminal, parser, renderer, configuration, and server integration tests pass from `:cli`.

- [ ] **Step 6: Commit the CLI extraction**

Run:

```powershell
git add settings.gradle.kts cli src/main/kotlin/io/openeden/cli src/main/kotlin/io/openeden/config src/test/kotlin/io/openeden/cli src/test/kotlin/io/openeden/config
git commit -m "refactor(cli): extract terminal application module"
```

Expected commit title: `refactor(cli): extract terminal application module`.

### Task 4: Return JVM runtime tests to `:core`

**Files:**
- Modify: `core/build.gradle.kts`
- Move: `src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`
- Move: `src/test/kotlin/io/openeden/compatibility/PublicConstructorBinaryCompatibilityTest.kt`

- [ ] **Step 1: Move tests to the JVM source set that owns their production types**

Run:

```powershell
New-Item -ItemType Directory -Force 'core/src/jvmTest/kotlin/io/openeden/llm' | Out-Null
New-Item -ItemType Directory -Force 'core/src/jvmTest/kotlin/io/openeden/compatibility' | Out-Null
git mv 'src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt' 'core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt'
git mv 'src/test/kotlin/io/openeden/compatibility/PublicConstructorBinaryCompatibilityTest.kt' 'core/src/jvmTest/kotlin/io/openeden/compatibility/PublicConstructorBinaryCompatibilityTest.kt'
```

- [ ] **Step 2: Run JVM core tests to expose the missing mock engine dependency**

Run:

```powershell
.\gradlew.bat :core:jvmTest
```

Expected: FAIL compiling `OpenAiResponsesLlmClientTest` because `io.ktor.client.engine.mock` is unavailable.

- [ ] **Step 3: Add the focused JVM test dependency**

Inside `core/build.gradle.kts` `sourceSets`, add:

```kotlin
jvmTest.dependencies {
    implementation(ktorLibs.client.mock)
}
```

- [ ] **Step 4: Run the core JVM and common tests**

Run:

```powershell
.\gradlew.bat :core:jvmTest :core:allTests
```

Expected: BUILD SUCCESSFUL, including the Responses API and binary constructor compatibility tests.

- [ ] **Step 5: Commit test ownership cleanup**

Run:

```powershell
git add core src/test/kotlin/io/openeden/llm src/test/kotlin/io/openeden/compatibility
git commit -m "test(core): move runtime tests to owning module"
```

Expected commit title: `test(core): move runtime tests to owning module`.

### Task 5: Make the root project aggregation-only

**Files:**
- Modify: `build.gradle.kts`
- Verify: repository-root `src/` is empty and removed

- [ ] **Step 1: Verify every root source file has an owner**

Run:

```powershell
rg --files src
```

Expected: no output. If files remain, classify them by production ownership before continuing; do not delete unclassified code.

- [ ] **Step 2: Remove root application and JVM source ownership**

Keep the existing artifact-download tasks, group/version, and `subprojects` block. Replace the root plugin and application-specific sections with:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

group = "io.openeden"
version = "1.0.0-SNAPSHOT"
```

Remove the root `application` block, the root `run` and `test` task configuration, the root Kotlin JVM toolchain block, and the root dependencies block because their complete equivalents now live in `cli/build.gradle.kts` from Task 3.

- [ ] **Step 3: Verify task ownership and dependency direction**

Run:

```powershell
.\gradlew.bat projects :cli:dependencies --configuration runtimeClasspath :client:allTests :cli:test :core:allTests :server:test
```

Expected: BUILD SUCCESSFUL; `:cli` appears as a subproject; CLI runtime dependencies contain `:client` but not `:core`; all focused suites pass.

- [ ] **Step 4: Verify the root has no source tree and no stale package references**

Run:

```powershell
Test-Path 'src'
rg -n "src/main/kotlin/io/openeden/(cli|client)|src/test/kotlin/io/openeden/(cli|client)" README.md README.zh-CN.md docs .github -g '*.md' -g '*.yml' -g '*.yaml'
```

Expected: `False` for the root `src` path and no stale documentation paths, except historical plans/specs that intentionally describe earlier repository state.

- [ ] **Step 5: Commit the aggregation root**

Run:

```powershell
git add build.gradle.kts settings.gradle.kts cli client core src
git commit -m "refactor(build): make root project aggregation only"
git log -1 --format=%s
```

Expected title: `refactor(build): make root project aggregation only`.

### Task 6: Run the migration verification gate

**Files:**
- Verify: all moved modules and documentation references

- [ ] **Step 1: Run all relevant Gradle suites from their new owners**

Run:

```powershell
.\gradlew.bat :client:allTests :cli:test :core:allTests :server:test :trainer:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Check formatting, repository state, and commit titles**

Run:

```powershell
git diff --check
git status --short
git log -4 --format=%s
```

Expected: no whitespace errors; no unintended files; all new commits use Conventional Commits.

- [ ] **Step 3: Confirm architectural constraints manually**

Confirm:

```text
root has no src directory
cli production depends on client, not core or server
client contains no JVM-only java.* imports in commonMain
server remains the only production runtime host
no persona, VQ-VAE, vector, or heartbeat behavior changed
```
