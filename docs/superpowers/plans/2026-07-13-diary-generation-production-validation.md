# Diary Generation and Production Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate durable Narrative Diary memories from real LLM output using delta, elapsed-time, and compaction triggers, then verify real DJL, SQLite, endpoint, and restart recovery end to end.

**Architecture:** A narrow `DiaryTriggerCoordinator` writes deterministic tasks into the existing durable queue. A SQLite-backed Diary data source exposes checkpointed raw-memory slices, and a dedicated LLM generator builds a bilingual VQ-VAE-aware prompt from persona data. Ktor owns the schedulers and all closeable model/client resources; a bounded PowerShell smoke test proves production behavior across restart.

**Tech Stack:** Kotlin 2.x, coroutines, Ktor, SQLDelight/SQLite, DJL/PyTorch, kotlinx.serialization, Gradle, PowerShell.

---

## File Map

- Create `core/src/commonMain/kotlin/io/openeden/runtime/DiaryTrigger.kt`: trigger reasons, configuration, coordinator, and scheduler.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/DiaryCheckpoint.kt`: checkpoint and raw-slice contracts.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/LlmDiaryNarrativeGenerator.kt`: VQ-VAE-aware prompt construction, zero-delta validation, and narrative memory creation.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/DiaryTriggerCoordinatorTest.kt`: trigger boundary, elapsed, compaction, and dedup tests.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/LlmDiaryNarrativeGeneratorTest.kt`: prompt boundary and output validation tests.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/DurableDiaryWorker.kt`: atomically complete a task with its checkpoint.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/DiaryTaskStore.kt`: idempotent enqueue and completion contract.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`: enqueue only after RAW memory persistence.
- Modify `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`: require `diary.narrative`.
- Modify `core/src/commonMain/kotlin/io/openeden/prompt/PromptSectionKeys.kt`: define the Diary section key.
- Modify `core/src/jvmMain/kotlin/io/openeden/persona/PersonaFileLoader.kt`: accept `diary.*` persona sections.
- Modify `persona/atri.yaml` and `persona/default.yaml`: store Chinese Diary narrative rules as data.
- Modify `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`: checkpoint table, idempotent task insert, raw-range queries, and atomic completion transaction inputs.
- Modify `server/src/main/kotlin/db/SqlDelightDiaryTaskStore.kt`: implement checkpoint and raw-slice persistence.
- Modify `server/src/main/kotlin/db/SqlDelightMemoryRepository.kt`: expose ordered RAW slices and latest RAW metadata.
- Modify `server/src/main/kotlin/Runtime.kt`: configure and wire coordinator, generator, elapsed scheduler, client/model ownership, and orderly shutdown.
- Modify `server/src/main/resources/application.yaml` and `.env.example`: expose non-persona Diary runtime settings.
- Modify `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`: implement `AutoCloseable`.
- Modify `server/src/test/kotlin/SqlDelightDiaryTaskStoreTest.kt`: persistence, dedup, checkpoint, and restart tests.
- Create `server/src/test/kotlin/RuntimeResourceLifecycleTest.kt`: closeable resource ownership test.
- Create `scripts/verify-production-runtime.ps1`: bounded real-production restart smoke test.
- Modify `docs/runtime-bootstrap.md`: document the production verification command and result checks.

### Task 1: Durable Trigger Coordinator

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/DiaryTrigger.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/DiaryCheckpoint.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/DiaryTriggerCoordinatorTest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/DiaryTaskStore.kt`

- [ ] **Step 1: Write failing trigger tests**

Add tests using an in-memory fake `DiaryTaskStore` and `DiaryDataSource`:

```kotlin
@Test
fun `delta trigger uses max absolute dimension and deterministic task id`() = runTest {
    val tasks = RecordingDiaryTaskStore()
    val coordinator = DiaryTriggerCoordinator(
        taskStore = tasks,
        dataSource = EmptyDiaryDataSource,
        config = DiaryTriggerConfig(deltaThreshold = 0.25f),
        nowMs = { 1_000L },
    )

    coordinator.onVectorDelta("QQ:g", "QQ:g:1000:raw", VectorDelta(p = -0.25f))
    coordinator.onVectorDelta("QQ:g", "QQ:g:1000:raw", VectorDelta(p = -0.25f))

    assertEquals(1, tasks.tasks.size)
    assertEquals(DiaryTriggerReason.DELTA_THRESHOLD, tasks.tasks.single().reason)
}
```

Also test `0.249f` does not trigger, `onContextCompacted` uses `CONTEXT_COMPACTION`, five hours triggers only with uncovered RAW memory, and a session without new RAW memory does not enqueue.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.DiaryTriggerCoordinatorTest"`

Expected: compilation fails because `DiaryTriggerCoordinator`, `DiaryTriggerConfig`, and `DiaryDataSource` do not exist.

- [ ] **Step 3: Implement the contracts and coordinator**

Use focused types:

```kotlin
enum class DiaryTriggerReason { DELTA_THRESHOLD, ELAPSED_TIME, CONTEXT_COMPACTION }

data class DiaryTriggerConfig(
    val deltaThreshold: Float = 0.25f,
    val elapsedMs: Long = 5 * 60 * 60 * 1_000L,
    val scanIntervalMs: Long = 60_000L,
    val maxRawMemories: Int = 32,
)

data class DiaryCheckpoint(
    val sessionId: String,
    val lastCoveredMemoryId: String?,
    val lastDiaryAtMs: Long?,
    val lastNarrativeMemoryId: String?,
)

data class DiaryRawSlice(
    val memories: List<MemorySnippet>,
    val upperBoundMemoryId: String,
)

interface DiaryDataSource {
    suspend fun checkpoint(sessionId: String): DiaryCheckpoint?
    suspend fun uncoveredRawSlice(sessionId: String, throughMemoryId: String?, limit: Int): DiaryRawSlice?
    suspend fun sessionsWithUncoveredRaw(): Set<String>
    suspend fun firstUncoveredRawAtMs(sessionId: String): Long?
}
```

`DiaryTriggerCoordinator` calculates `delta.toList().maxOf(abs)` and enqueues `DiaryTask` with ID `diary:<sessionId>:<reason>:<upperBoundMemoryId>`. `DiaryTask.reason` changes from free-form text to `DiaryTriggerReason`, preventing string drift without encoding persona semantics. `flushElapsedSessions` compares `nowMs` with the checkpoint timestamp or first uncovered RAW timestamp.

- [ ] **Step 4: Make enqueue idempotent in the contract fake**

Document that duplicate task IDs are accepted as a successful no-op. Keep overflow as the only non-empty trace result.

- [ ] **Step 5: Run trigger tests and the core suite**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.DiaryTriggerCoordinatorTest" :core:jvmTest`

Expected: all tests pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/DiaryTrigger.kt core/src/commonMain/kotlin/io/openeden/runtime/DiaryCheckpoint.kt core/src/commonMain/kotlin/io/openeden/runtime/DiaryTaskStore.kt core/src/commonTest/kotlin/io/openeden/runtime/DiaryTriggerCoordinatorTest.kt
git commit -m "feat: add durable diary trigger coordinator"
```

### Task 2: SQLite Checkpoints, Raw Ranges, and Atomic Completion

**Files:**
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`
- Modify: `server/src/main/kotlin/db/SqlDelightDiaryTaskStore.kt`
- Modify: `server/src/main/kotlin/db/SqlDelightMemoryRepository.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/DurableDiaryWorker.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/DurableDiaryWorkerTest.kt`
- Modify: `server/src/test/kotlin/SqlDelightDiaryTaskStoreTest.kt`

- [ ] **Step 1: Write failing SQLite persistence tests**

Add tests that open a file database, insert RAW memories, enqueue the same task twice, lease it, complete it with a checkpoint, close all handles, reopen, and assert:

```kotlin
assertEquals(DiaryTaskStatus.DONE, reopened.readById(task.id)?.status)
assertEquals(raw2.id, reopened.checkpoint("S")?.lastCoveredMemoryId)
assertEquals(narrative.id, reopened.checkpoint("S")?.lastNarrativeMemoryId)
assertEquals(listOf(raw3.id), reopened.uncoveredRawSlice("S", null, 32)?.memories?.map { it.id })
```

Add a test that an expired `RUNNING` lease returns to `PENDING` after reopen and recovery.

- [ ] **Step 2: Run storage tests and verify RED**

Run: `./gradlew.bat :server:test --tests "io.openeden.server.SqlDelightDiaryTaskStoreTest"`

Expected: compilation fails on the missing checkpoint and raw-slice APIs.

- [ ] **Step 3: Add SQLDelight schema and queries**

Add:

```sql
CREATE TABLE diary_checkpoints (
    session_id TEXT NOT NULL PRIMARY KEY,
    last_covered_memory_id TEXT,
    last_diary_at_ms INTEGER,
    last_narrative_memory_id TEXT
);

INSERT OR IGNORE INTO diary_tasks(...);

upsertDiaryCheckpoint:
INSERT INTO diary_checkpoints(session_id, last_covered_memory_id, last_diary_at_ms, last_narrative_memory_id)
VALUES (?, ?, ?, ?)
ON CONFLICT(session_id) DO UPDATE SET
  last_covered_memory_id = excluded.last_covered_memory_id,
  last_diary_at_ms = excluded.last_diary_at_ms,
  last_narrative_memory_id = excluded.last_narrative_memory_id;
```

Add ordered queries over `memory_entries` restricted to `kind = 'RAW'`, using `(created_at_ms, id)` as a stable sequence and excluding rows at or before the checkpoint. Add session and first-uncovered timestamp queries.

- [ ] **Step 4: Implement storage adapters and atomic completion**

Extend `DiaryTaskStore` with:

```kotlin
suspend fun complete(taskId: String, checkpoint: DiaryCheckpoint)
```

In `SqlDelightDiaryTaskStore`, execute task completion and checkpoint upsert in one `database.transaction`. In `DurableDiaryWorker`, generate and write the deterministic narrative, then call `complete(task.id, checkpoint)` inside the existing `SessionTurnGate`. Cancellation still propagates; other failures call `fail` and never advance the checkpoint.

- [ ] **Step 5: Run storage and worker tests**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.DurableDiaryWorkerTest" :server:test --tests "io.openeden.server.SqlDelightDiaryTaskStoreTest" --tests "io.openeden.server.SqlDelightMemoryRepositoryTest"`

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 2**

```powershell
git add server/src/main/sqldelight/io/openeden/server/db/Memory.sq server/src/main/kotlin/db/SqlDelightDiaryTaskStore.kt server/src/main/kotlin/db/SqlDelightMemoryRepository.kt core/src/commonMain/kotlin/io/openeden/runtime/DurableDiaryWorker.kt core/src/commonTest/kotlin/io/openeden/runtime/DurableDiaryWorkerTest.kt server/src/test/kotlin/SqlDelightDiaryTaskStoreTest.kt
git commit -m "feat: persist diary checkpoints and raw ranges"
```

### Task 3: VQ-VAE-Aware LLM Narrative Generation

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/LlmDiaryNarrativeGenerator.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/LlmDiaryNarrativeGeneratorTest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptSectionKeys.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/persona/PersonaFileLoader.kt`
- Modify: `core/src/jvmTest/kotlin/io/openeden/persona/PersonaFileLoaderTest.kt`
- Modify: `persona/atri.yaml`
- Modify: `persona/default.yaml`

- [ ] **Step 1: Write failing generator and persona tests**

Test that the generated prompt includes codebook node definitions, derived D, task reason, covered RAW facts, and the exact `diary.narrative` persona section, but does not include an 8D float array. Test that the generated `MemoryEntry` is `NARRATIVE`, uses a deterministic ID, snapshots state, and has `VectorDelta.Zero`.

Test rejection of output with any non-zero delta:

```kotlin
val error = assertFailsWith<IllegalArgumentException> { generator.generate(task) }
assertContains(error.message.orEmpty(), "Diary vector_delta must be zero")
```

Test heuristic quantizer trace propagation and require both persona YAML files to load `diary.narrative`.

- [ ] **Step 2: Run generator tests and verify RED**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.LlmDiaryNarrativeGeneratorTest" --tests "io.openeden.persona.PersonaFileLoaderTest"`

Expected: compilation or persona loading fails because the generator and required section do not exist.

- [ ] **Step 3: Add Diary persona data**

Add `PromptSectionKeys.DiaryNarrative = "diary.narrative"`, allow `diary.*` in `PersonaFileLoader`, and require it in `MapPersonaLoader`. Add Chinese data to each YAML, for example:

```yaml
  diary.narrative: |
    【叙事日记】
    将提供的真实经历片段整理成第一人称中文日记。
    保留事件、关系变化和感受的因果顺序，不虚构未提供的事实。
    不解释系统、向量、节点、触发原因或写作过程。
```

- [ ] **Step 4: Implement the generator**

Construct `BuiltPrompt` with English system constraints and the YAML section as `personaText`. Obtain current state from `SessionStateStore`; compute D at runtime; call `CodebookQuantizer.quantize` through `InferenceExecutor`; use `DiaryDataSource.uncoveredRawSlice`; call `LlmClient.complete`; validate standard schema plus exact zero delta and nonblank response; create embeddings through the configured model in the inference executor.

Do not mutate the session store. Attach quantization trace tags to the generated entry tags so `codebook=HEURISTIC_FALLBACK` remains observable when degraded.

- [ ] **Step 5: Run generator, persona, codebook, and prompt tests**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.LlmDiaryNarrativeGeneratorTest" --tests "io.openeden.persona.*" --tests "io.openeden.codebook.*" --tests "io.openeden.prompt.*"`

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 3**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/LlmDiaryNarrativeGenerator.kt core/src/commonTest/kotlin/io/openeden/runtime/LlmDiaryNarrativeGeneratorTest.kt core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt core/src/commonMain/kotlin/io/openeden/prompt/PromptSectionKeys.kt core/src/jvmMain/kotlin/io/openeden/persona/PersonaFileLoader.kt core/src/jvmTest/kotlin/io/openeden/persona/PersonaFileLoaderTest.kt persona/atri.yaml persona/default.yaml
git commit -m "feat: generate narrative diary with llm"
```

### Task 4: Pipeline Wiring, Scheduling, and Resource Lifecycle

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `server/src/main/resources/application.yaml`
- Modify: `.env.example`
- Create: `server/src/test/kotlin/RuntimeResourceLifecycleTest.kt`

- [ ] **Step 1: Write failing pipeline and lifecycle tests**

Test that a qualifying delta enqueues only after the RAW memory ID exists, a sub-threshold delta does not enqueue, and the request returns without running the narrative generator. Test a runtime resource owner that closes the LLM client, quantizer runner, embedding model, and affect analyzer exactly once after scheduler jobs are cancelled.

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.MessagePipelineTest" :server:test --tests "io.openeden.server.RuntimeResourceLifecycleTest"`

Expected: tests fail because the pipeline still enqueues before RAW persistence and runtime models do not own closeable resources.

- [ ] **Step 3: Move trigger publication after RAW persistence**

Change `writeMemories` to return the persisted RAW memory ID and trace tags. Call `DiaryTriggerCoordinator.onVectorDelta(sessionId, rawMemoryId, delta)` only after `MemoryStore.write` succeeds. Remove the current `any(abs(delta) > 0)` trigger and free-form timestamp task ID.

- [ ] **Step 4: Wire one shared LLM client and runtime-owned resources**

Instantiate one `OpenAiResponsesLlmClient` for both dialogue and Diary. Make it `AutoCloseable`:

```kotlin
class OpenAiResponsesLlmClient(...) : LlmClient, AutoCloseable {
    override fun close() = httpClient.close()
}
```

Make `RuntimeModels` close its DJL runner, embedding model, and affect analyzer. Add a small runtime resource owner so tests can assert deterministic close ordering without booting Ktor.

- [ ] **Step 5: Wire elapsed scheduling and configuration**

Load the four Diary environment-backed settings, build the coordinator and generator, start a dedicated elapsed trigger job, and on `ApplicationStopping` cancel and join all jobs before closing stores, LLM, and models. Keep inference work behind `JvmInferenceExecutor`.

- [ ] **Step 6: Run pipeline, server, and resource tests**

Run: `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.MessagePipelineTest" :server:test`

Expected: all selected tests pass without leaked coroutine warnings.

- [ ] **Step 7: Commit Task 4**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt server/src/main/kotlin/Runtime.kt server/src/main/resources/application.yaml .env.example server/src/test/kotlin/RuntimeResourceLifecycleTest.kt
git commit -m "feat: wire diary generation into server runtime"
```

### Task 5: Real Production Restart Verification

**Files:**
- Create: `scripts/verify-production-runtime.ps1`
- Modify: `docs/runtime-bootstrap.md`

- [ ] **Step 1: Write the verification script assertions before launching**

The script must validate required files and environment keys, allocate an unused localhost port and temporary SQLite path, and define assertion helpers for HTTP status, process exit, and SQLite queries. It must never print `OPENEDEN_OPENAI_API_KEY`.

- [ ] **Step 2: Implement bounded launch and cleanup**

Launch `./gradlew.bat :server:run` with `OPENEDEN_MODEL_BACKEND=djl`, the temporary DB, and a low smoke-only Diary threshold. Redirect server output to temporary log files. Poll `/health` with a finite startup timeout. Register `finally` cleanup that stops the child process tree and removes only the verified temporary directory.

- [ ] **Step 3: Implement continuous-run and restart assertions**

Send three sequential `/api/v1/chat` requests for one smoke user, poll SQLite until a `DONE` Diary task and `NARRATIVE` memory exist, record state, stop gracefully, restart against the same DB, assert the stored `evolution_index` is unchanged before the next request, send a fourth request, and assert it increments. Query that no task remains `RUNNING`, the checkpoint references a completed narrative, and RAW/NARRATIVE rows survive.

- [ ] **Step 4: Document the command**

Document:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-production-runtime.ps1
```

State that it performs real billable endpoint calls and uses `.env` without echoing secrets.

- [ ] **Step 5: Run static verification first**

Run: `./gradlew.bat clean test`

Expected: build succeeds and all tests pass.

- [ ] **Step 6: Run the real production verification**

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-production-runtime.ps1`

Expected: exit code `0` with a concise summary containing four completed turns, at least one narrative memory, a completed Diary checkpoint, real DJL backend, and successful restart recovery. No key or bearer token appears in output.

- [ ] **Step 7: Update CodeGraph and inspect the final diff**

Run: `graphify update .`

Run: `git diff --check && git status --short`

Expected: CodeGraph update succeeds, `git diff --check` is clean, and only intended source, test, config, documentation, and generated graph files are changed.

- [ ] **Step 8: Commit Task 5**

```powershell
git add scripts/verify-production-runtime.ps1 docs/runtime-bootstrap.md graphify-out
git commit -m "test: verify production runtime restart recovery"
```

## Plan Self-Review

- Spec coverage: delta, elapsed, compaction entry, LLM narrative generation, Persona-as-Data, VQ-VAE semantics, durable checkpoints, retry/restart, resource closure, and real production verification each map to a task.
- Scope: Omega critical degradation and termination remain excluded.
- Type consistency: trigger reasons, checkpoint fields, raw-slice boundaries, and completion signatures use the same names throughout.
- Placeholder scan: all steps name concrete files, APIs, assertions, commands, and expected outcomes.
