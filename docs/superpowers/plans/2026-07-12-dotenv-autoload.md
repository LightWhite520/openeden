# .env Autoload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically load a project-root `.env` for both the CLI and Ktor server while preserving explicit process environment variables.

**Architecture:** Add one JVM-only dotenv loader in `core` that installs `.env` values as low-priority JVM system properties. Ktor's `application.yaml` remains the shared configuration bridge; system properties and process environment variables win over `.env`. The CLI reads the same YAML configuration rather than maintaining a second environment-to-config mapping.

**Tech Stack:** Kotlin/JVM, Kotlin Multiplatform `jvmMain`, Kotlin test, Gradle.

---

### Task 1: Add dotenv parser tests

**Files:**
- Create: `core/src/jvmTest/kotlin/io/openeden/config/DotEnvLoaderTest.kt`

- [ ] **Step 1: Write the failing tests**

Cover parsing comments and quotes, `.env` values filling missing environment entries, process environment precedence, and missing-file fallback.

- [ ] **Step 2: Run the focused test**

Run:

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
.\gradlew.bat :core:jvmTest --tests io.openeden.config.DotEnvLoaderTest --no-daemon
```

Expected: FAIL because `DotEnvLoader` does not exist yet.

### Task 2: Implement the shared JVM dotenv loader

**Files:**
- Create: `core/src/jvmMain/kotlin/io/openeden/config/DotEnvLoader.kt`

- [ ] **Step 1: Implement the minimal parser**

Expose `install(path: Path, processEnvironment: Map<String, String>, systemProperties: MutableMap<String, String>)`. Read UTF-8 lines if the file exists; ignore blank/comment/malformed lines; split only on the first `=`; trim keys and values; remove one matching pair of single or double quotes; write only keys absent from both process environment and system properties.

- [ ] **Step 2: Run the focused tests**

Run the command from Task 1 and expect PASS.

### Task 3: Put shared settings in application.yaml and wire both entry points

**Files:**
- Modify: `server/src/main/resources/application.yaml`
- Create: `server/src/main/kotlin/Main.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `src/main/kotlin/io/openeden/Main.kt`
- Modify: `src/main/kotlin/io/openeden/config/LocalRuntimeConfig.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add startup environment loading**

Add an `openeden` YAML section with `$ENV` references and defaults. The server main wrapper calls `DotEnvLoader.install` before `EngineMain.main(args)`. The CLI installs `.env`, loads `application.yaml` through Ktor's YAML config API, and maps the `openeden` section into `LocalRuntimeConfig`.

- [ ] **Step 2: Preserve explicit environment precedence**

Do not mutate the process environment. Only missing JVM system properties are added from `.env`; explicit system properties and process environment variables remain authoritative.

- [ ] **Step 3: Run existing configuration and server tests**

Run:

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
.\gradlew.bat :core:jvmTest :server:test :test --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 4: Verify real startup behavior

**Files:**
- Modify: `docs/runtime-bootstrap.md`
- Modify: `README.md`

- [ ] **Step 1: Document automatic loading**

State that `.env` is loaded automatically before `application.yaml`, process variables take precedence, and no PowerShell import command is required.

- [ ] **Step 2: Run CLI smoke test with `.env`**

Use a temporary API-compatible configuration in the current `.env` without printing the key, then run `:run --args='state --user local'` and confirm it reaches the database-backed state command.

- [ ] **Step 3: Run final verification**

Run `git diff --check` and the full Gradle test command from Task 3.

- [ ] **Step 4: Commit implementation**

```powershell
git add core/src/jvmMain core/src/jvmTest src/main server/src/main README.md docs/runtime-bootstrap.md
git commit -m "feat: auto load dotenv configuration"
```
