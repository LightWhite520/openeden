# Repository Package Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize OpenEden's Kotlin sources into cohesive domain packages, mirror those packages in tests, and codify the organization rules without changing runtime behavior or public protocols.

**Architecture:** Keep every existing Gradle module boundary, split only mixed packages, and point all dependencies inward through the same contracts. Apply the migration in independently compilable stages: core runtime, CLI, server, then documentation and repository-wide verification.

**Tech Stack:** Kotlin 2.x, Kotlin Multiplatform, Ktor 3.5, coroutines, JLine, Mordant, SQLDelight, Gradle, JDK 21.

---

## File Map

### Core runtime

Move production files from `core/src/commonMain/kotlin/io/openeden/runtime/`:

| Target package | Files |
| --- | --- |
| `io.openeden.runtime.pipeline` | `DevelopmentMessageRequest.kt`, `DevelopmentMessageResult.kt`, `MessagePipeline.kt`, `RuntimePipeline.kt`, `TurnSource.kt` |
| `io.openeden.runtime.session` | `MutableSessionStateStore.kt`, `SessionMutexRegistry.kt`, `SessionState.kt`, `SessionStateStore.kt`, `SessionTurnGate.kt` |
| `io.openeden.runtime.state` | `HomeostasisCentroid.kt`, `RuntimeConfig.kt`, `RuntimeInvariantConstants.kt`, `VectorWriteResult.kt`, `VectorWriteService.kt` |
| `io.openeden.runtime.affect` | `EmotionSignal.kt`, `OmegaAccumulation.kt`, `OmegaAccumulationConfig.kt`, `OmegaState.kt`, `PreTickEngine.kt`, `PreTickResult.kt`, `ShockState.kt`, `ShockStateEngine.kt` |
| `io.openeden.runtime.tick` | `RuntimeTick.kt`, `SineWaveFluctuation.kt`, `TickConfig.kt` |
| `io.openeden.runtime.heartbeat` | types currently in `Heartbeat.kt` |
| `io.openeden.runtime.diary` | `DiaryCheckpoint.kt`, `DiaryDataSource.kt`, `DiaryEvent.kt`, `DiaryTask.kt`, `DiaryTaskStatus.kt`, `DiaryTaskStore.kt`, `DiaryTrigger.kt`, `DiaryWorkerScheduler.kt`, `DurableDiaryWorker.kt`, `LlmDiaryNarrativeGenerator.kt`, `SessionDiaryQueue.kt` |
| `io.openeden.runtime.inference` | `InferenceExecutor.kt` |

Move JVM files from `core/src/jvmMain/kotlin/io/openeden/runtime/`:

| Target package | Files |
| --- | --- |
| `io.openeden.runtime.heartbeat` | `SecureRandomHeartbeatInterval.kt` |
| `io.openeden.runtime.tick` | `SecureRandomSineWaveFluctuation.kt` |
| `io.openeden.runtime.inference` | `JvmInferenceExecutor.kt` |

Split `Heartbeat.kt` into focused files under
`core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/`:

```text
HeartbeatDelivery.kt          HeartbeatDelivery, NoopHeartbeatDelivery, LoggingHeartbeatDelivery
HeartbeatTarget.kt            HeartbeatTarget, HeartbeatOwner
HeartbeatRouteResolver.kt     HeartbeatRouteResolver, OwnerHeartbeatRouteResolver
HeartbeatIntervalStrategy.kt  HeartbeatIntervalStrategy, RandomHeartbeatInterval
HeartbeatConfig.kt            HeartbeatConfig
HeartbeatDecision.kt          HeartbeatDecision
HeartbeatScheduler.kt         HeartbeatScheduler
```

Small implementation pairs stay together because each secondary type exists
only to implement or configure the primary contract.

### CLI

Move root CLI files:

| Target package | Files |
| --- | --- |
| `io.openeden.cli` | `Main.kt` |
| `io.openeden.cli.application` | `OpenEdenCli.kt` |
| `io.openeden.cli.input` | `CliInput.kt`, `JLineCliInput.kt`, `StdinCliInput.kt` |

Move files currently under `src/main/kotlin/io/openeden/terminal/`:

| Target package | Files |
| --- | --- |
| `io.openeden.cli.command` | `CliCommand.kt`, `CliCommandCompleter.kt`, `CliCommandParser.kt`, `CommandCandidate.kt` |
| `io.openeden.cli.state` | `CliDiagnostics.kt`, `CliEvent.kt`, `CliMessage.kt`, `CliMode.kt`, `CliReducer.kt`, `CliUiState.kt` |
| `io.openeden.cli.render` | `CliRenderer.kt`, `FrameDiff.kt`, `FullScreenCliRenderer.kt`, `InlineCliRenderer.kt`, `MarkdownTextRenderer.kt` |
| `io.openeden.cli.terminal` | `CliTerminalEvent.kt`, `CliTextStreams.kt`, `JLineTerminalSession.kt`, `TerminalEncodingProfile.kt`, `TerminalFullScreenCapabilities.kt`, `TerminalLifecycleOperations.kt`, `TerminalProviderSelection.kt`, `TerminalRichModePolicy.kt`, `TerminalSession.kt` |

Keep `io.openeden.client` and `io.openeden.config` unchanged. Update the Gradle
application main class from `io.openeden.MainKt` to `io.openeden.cli.MainKt`.

### Server

Move server files from `server/src/main/kotlin/`:

| Target package | Files |
| --- | --- |
| `io.openeden.server.bootstrap` | `main.kt`, `Runtime.kt` |
| `io.openeden.server.api.dto` | `ChatRequestDto.kt`, `ChatResponseDto.kt`, `DevMessageRequestDto.kt`, `DevMessageResponseDto.kt`, `HealthResponseDto.kt`, `PublicStateDto.kt` |
| `io.openeden.server.api.route` | `Routing.kt`, `Websockets.kt` |
| `io.openeden.server.api.plugin` | `Serialization.kt`, `StatusPages.kt` |
| `io.openeden.server.persistence.sqldelight` | `db/IdTimestamp.kt`, `db/SqlDelightDiaryTaskStore.kt`, `db/SqlDelightMemoryRepository.kt`, `db/SqlDelightRelationshipStateStore.kt`, `db/SqlDelightSessionStateStore.kt`, `db/SqlDelightTraceStore.kt` |

Keep SQLDelight generated code in `io.openeden.server.db`; handwritten adapters
import the generated `Database` explicitly. Update `application.yaml` module
references to the new packages and file facades.

---

### Task 1: Establish Baseline And Organization Rule

**Files:**
- Modify: `AGENTS.md`
- Verify: all current Kotlin tests

- [ ] **Step 1: Run the baseline suite**

Run:

```powershell
.\gradlew.bat test :core:jvmTest :server:test -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`. If it fails before the refactor, record the exact
pre-existing failure and do not attribute it to package movement.

- [ ] **Step 2: Add the mandatory package organization section**

Insert after `## 3. Code Generation Constraints` in `AGENTS.md`:

```markdown
### 3.1 Package And Directory Organization (MANDATORY)

* Organize production code by cohesive domain responsibility within its Gradle module.
* MUST NOT mix API DTOs, routes, persistence adapters, runtime services, terminal infrastructure, and rendering types in one package.
* Module root packages MUST contain only deliberate entry points or genuinely module-wide types.
* Test source paths and package declarations MUST mirror the production package of the code under test.
* Reusable or public top-level types MUST live in focused files, subject to the small private/local helper exception above.
* MUST NOT create subpackages merely to equalize file counts or isolate one type without a real ownership boundary.
* Every new package MUST have one describable responsibility and a clear dependency direction.
```

- [ ] **Step 3: Check the rule in context**

Run:

```powershell
rg -n -A 12 "Package And Directory Organization" AGENTS.md
git diff --check -- AGENTS.md
```

Expected: all seven rules appear once and `git diff --check` prints nothing.

### Task 2: Reorganize Core Runtime Packages

**Files:**
- Move: all files listed in the Core runtime file map
- Modify: imports in `core`, root CLI, `server`, `trainer`, and their tests
- Move tests: `core/src/commonTest/kotlin/io/openeden/runtime/*.kt` into matching runtime subpackages

- [ ] **Step 1: Move common and JVM runtime files to the mapped directories**

Create the eight target directories, move each file according to the table, and
change its declaration from `package io.openeden.runtime` to the exact mapped
package. Preserve the current contents of
`LlmDiaryNarrativeGenerator.kt`, including its pre-existing user changes.

- [ ] **Step 2: Split the heartbeat file without behavioral edits**

Create the seven heartbeat files listed in the file map. Copy each declaration,
KDoc, default value, and method body exactly from `Heartbeat.kt`; add only imports
required by the new file boundary. Delete the original `Heartbeat.kt` after all
declarations are accounted for.

- [ ] **Step 3: Update production imports repository-wide**

Use the target packages from the file map. Important dependency directions are:

```text
pipeline -> affect, diary, heartbeat, inference, session, state
heartbeat -> pipeline, session, state
tick -> affect, inference, session, state
diary -> inference and memory/llm/prompt contracts
server -> all required core runtime subpackages
```

Do not add wildcard imports. Do not introduce `server` or CLI imports into core.

- [ ] **Step 4: Mirror core tests**

Move runtime tests by the primary unit under test:

```text
pipeline: MessagePipelineTest, RuntimePipelineTest
session: TurnCoordinatorConcurrencyTest
state: HomeostasisCentroidDriftTest, HomeostasisCentroidProviderTest,
       RuntimeConfigTest, RuntimeInvariantTest
affect: OmegaAccumulationTest
tick: RuntimeTickSchedulerTest, SineWaveFluctuationEngineTest
heartbeat: HeartbeatSchedulerTest
diary: DiaryTriggerCoordinatorTest, DiaryWorkerSchedulerTest,
       DurableDiaryContractTest, DurableDiaryWorkerTest,
       LlmDiaryNarrativeGeneratorTest
inference: InferenceExecutorTest
```

Change each test package declaration to match its destination and import runtime
types from other subpackages explicitly.

- [ ] **Step 5: Run core tests**

Run:

```powershell
.\gradlew.bat :core:jvmTest -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL` with all existing core tests passing.

- [ ] **Step 6: Verify the old mixed package is empty**

Run:

```powershell
rg -n "^package io\.openeden\.runtime$|import io\.openeden\.runtime\.[A-Z]" core src server trainer --glob '*.kt'
```

Expected: no matches. Subpackages such as `io.openeden.runtime.pipeline` are valid.

### Task 3: Reorganize CLI Packages

**Files:**
- Move: files listed in the CLI file map
- Move tests: `src/test/kotlin/io/openeden/OpenEdenCliTest.kt` and `src/test/kotlin/io/openeden/terminal/*.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Move CLI production files and update packages**

Move each file to the exact target in the CLI map. Keep private Clikt command
types in `OpenEdenCli.kt` because they are implementation details of that class.
Update imports between command, input, state, render, and terminal packages.

- [ ] **Step 2: Update the distribution entry point**

Change `build.gradle.kts` to:

```kotlin
application {
    mainClass = "io.openeden.cli.MainKt"
}
```

- [ ] **Step 3: Mirror CLI tests**

Move tests using these mappings:

```text
io.openeden.cli.application: OpenEdenCliTest
io.openeden.cli.command: CliCommandParserTest
io.openeden.cli.state: CliReducerTest
io.openeden.cli.render: FrameDiffTest, FullScreenCliRendererTest,
                        InlineCliRendererTest, MarkdownTextRendererTest
io.openeden.cli.terminal: CliTextStreamsTest, JLineTerminalSessionTest,
                          TerminalEncodingProfileTest,
                          TerminalProviderSelectionTest,
                          WindowsTerminalInputE2ETest
```

Update imports explicitly when a test spans more than one CLI package.

- [ ] **Step 4: Run CLI tests and build the distribution**

Run:

```powershell
.\gradlew.bat test installDist -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL` and
`build/install/openeden/bin/openeden.bat` exists.

- [ ] **Step 5: Verify the old CLI packages are gone**

Run:

```powershell
rg -n "^package io\.openeden$|^package io\.openeden\.terminal|import io\.openeden\.terminal|io\.openeden\.MainKt" src build.gradle.kts --glob '*.kt' --glob '*.kts'
```

Expected: no matches.

### Task 4: Reorganize Server Packages

**Files:**
- Move: files listed in the Server file map
- Move tests: all files under `server/src/test/kotlin/`
- Modify: `server/src/main/resources/application.yaml`
- Modify: imports in server source and tests

- [ ] **Step 1: Move bootstrap, DTO, route, plugin, and persistence files**

Move all handwritten files according to the server map and change their package
declarations. In SQLDelight adapters, import generated types from
`io.openeden.server.db`, including `Database` and generated query row types when
referenced. Do not change `server/build.gradle.kts` SQLDelight `packageName`.

- [ ] **Step 2: Update Ktor module references**

Set `server/src/main/resources/application.yaml` modules to:

```yaml
      - io.openeden.server.api.plugin.SerializationKt.configureSerialization
      - io.openeden.server.api.route.WebsocketsKt.configureWebsockets
      - io.openeden.server.api.plugin.StatusPagesKt.configureStatusPages
      - io.openeden.server.bootstrap.RuntimeKt.configureRuntime
      - io.openeden.server.api.route.RoutingKt.configureRouting
```

- [ ] **Step 3: Mirror server tests**

Move tests using these mappings:

```text
io.openeden.server.api.route: ServerApiTest, ServerTest
io.openeden.server.persistence.sqldelight: IdTimestampTest,
    SqlDelightDiaryTaskStoreTest, SqlDelightMemoryRepositoryTest,
    SqlDelightRelationshipStateStoreTest, SqlDelightSessionStateStoreTest,
    SqlDelightTraceStoreTest
```

Update package declarations and imports. Tests may import bootstrap keys and
configuration functions explicitly; they must not remain in the root package.

- [ ] **Step 4: Run server tests**

Run:

```powershell
.\gradlew.bat :server:test -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`, including Ktor startup from the updated YAML module names.

- [ ] **Step 5: Verify handwritten server root packages are gone**

Run:

```powershell
rg -n "^package io\.openeden\.server$|import io\.openeden\.server\.db\.SqlDelight" server --glob '*.kt'
```

Expected: no matches. Imports of generated `io.openeden.server.db.Database` are valid.

### Task 5: Update Documentation And Verify The Whole Repository

**Files:**
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `docs/terminal-input.md` only if an entry-point path is stale
- Preserve: existing staged README and model-client changes

- [ ] **Step 1: Update README structure and supported CLI launch instructions**

In both README files:

- describe the new CLI, core runtime, and server package boundaries;
- replace interactive `gradlew run` instructions with `installDist` followed by
  the packaged launcher;
- keep non-interactive `chat` and `state` development commands accurate;
- document JLine native terminal ownership, Unicode line editing, inline/full
  modes, hidden-by-default diagnostics, `/mode`, `/inspect`, and `/clear`;
- link `docs/terminal-input.md`;
- preserve the existing Thymos environment-variable additions.

- [ ] **Step 2: Search for stale live-code package references**

Run:

```powershell
rg -n "io\.openeden\.runtime\.[A-Z]|io\.openeden\.terminal|io\.openeden\.server\.db\.SqlDelight|io\.openeden\.MainKt" --glob '*.kt' --glob '*.kts' --glob '*.yaml' --glob '*.yml' .
```

Expected: no stale production or test references. Historical design and plan
documents are excluded from this live-code check.

- [ ] **Step 3: Confirm directory/package alignment**

For every tracked Kotlin source under `src`, `core/src`, `server/src`, and
`trainer/src`, compare the path below its Kotlin source root with its `package`
declaration. Expected: all production and test files align, except generated
SQLDelight sources under Gradle build directories.

- [ ] **Step 4: Run the full verification suite**

Run:

```powershell
.\gradlew.bat test :core:jvmTest :server:test installDist -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the Windows terminal regression test explicitly**

Run:

```powershell
.\gradlew.bat test --tests "io.openeden.cli.terminal.WindowsTerminalInputE2ETest" -Pkotlin.incremental=false --no-daemon --console=plain
```

Expected: two tests pass under the packaged Windows/ConPTY path, covering CP936
Chinese cursor insertion and emoji deletion.

- [ ] **Step 6: Smoke-test the packaged launcher**

With the server running, execute:

```powershell
.\build\install\openeden\bin\openeden.bat state
```

Expected: exit code `0` and one public session-state line. If no server is
running, verify launcher startup and record the expected server-unavailable exit
instead of changing CLI behavior.

- [ ] **Step 7: Review the final diff**

Run:

```powershell
git diff --check
git status --short
git diff --stat
```

Expected: no whitespace errors, no conflict markers, no obsolete empty source
directories, and no unrelated generated/cache files added. Confirm the existing
user edits in `LlmDiaryNarrativeGenerator.kt`, `OpenAiResponsesLlmClient.kt`, and
the README files remain present after their package/document updates.
