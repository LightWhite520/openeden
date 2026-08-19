# OpenEden Kernel Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the OpenEden runtime kernel against the approved design while excluding QQ/OneBot and Web UI chains.

**Architecture:** Preserve the existing `DevelopmentMessagePipeline` as the single turn path. Add focused state, filtering, lifecycle, and persistence contracts around it; all runtime math remains behind `InferenceExecutor`, and all session mutations use the existing per-session Mutex. The termination coordinator owns the global incarnation lifecycle and is deliberately not exposed through HTTP or CLI in this plan.

**Tech Stack:** Kotlin Multiplatform/JVM, Ktor runtime contracts, kotlinx.coroutines, SQLDelight/SQLite, DJL, Kotlin Test, Gradle Wrapper.

---

## Execution Order

Tasks are sequential because the durable state model is shared. Every behavior follows red-green-refactor: add one failing test, run it, implement the smallest change, run the focused test, then continue.

## Task 1: Establish Kernel Fixtures And State Contracts

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/session/SessionState.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/session/SessionStateStore.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/RuntimeConfig.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycle.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycleStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/session/SessionStateTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycleTest.kt`

- [ ] **Step 1: Add failing state tests**

Add tests that construct `SessionStateStore.neutral("CLI:test")` and assert `lastRuntimeTickAtMs == null`. Add lifecycle tests asserting `ACTIVE -> CRITICAL -> TERMINATING -> TERMINATED`, rejection of `ACTIVE -> TERMINATED`, and rejection of any transition out of `TERMINATED` except explicit fresh creation.

- [ ] **Step 2: Run focused tests and confirm RED**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.session.SessionStateTest" --tests "io.openeden.runtime.lifecycle.IncarnationLifecycleTest"
```

Expected: compilation/test failure because the timestamp and lifecycle contracts do not exist.

- [ ] **Step 3: Implement minimal contracts**

Add `lastRuntimeTickAtMs: Long? = null` to `SessionState` and preserve it in `SessionStateStore.neutral`. Add `IncarnationLifecycle` with `ACTIVE`, `CRITICAL`, `TERMINATING`, and `TERMINATED`, plus a pure `transitionTo` function that enforces the legal graph. Add `IncarnationLifecycleStore` with suspend `read`, `markCritical`, `beginTermination`, `markTerminated`, and `createFresh(requestId, nowMs)` operations; keep storage implementation for Task 7.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run the same command. Expected: all new tests pass.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/session core/src/commonMain/kotlin/io/openeden/runtime/state/RuntimeConfig.kt core/src/commonMain/kotlin/io/openeden/runtime/lifecycle core/src/commonTest/kotlin/io/openeden/runtime/session core/src/commonTest/kotlin/io/openeden/runtime/lifecycle
git commit -m "feat(core): add kernel lifecycle and tick state contracts"
```

## Task 2: Correct Runtime Tick Delta And Serialization

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/tick/SineWaveFluctuation.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/tick/RuntimeTickSchedulerTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt`

- [ ] **Step 1: Add failing multi-tick and stale-write tests**

Add a test with `startedAtMs = 0`, a constant session, and calls at `1_000L` and `2_000L`. Assert the second call applies only the interval delta and Omega increases by one second per call, not one plus two seconds. Add a test where a runtime-tick transform suspends, a turn writes the state, and the tick reads the latest state before committing; assert neither update is lost.

- [ ] **Step 2: Run the focused tests and confirm RED**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.tick.RuntimeTickSchedulerTest" --tests "io.openeden.runtime.state.RuntimeInvariantTest"
```

Expected: the multi-tick assertion fails because the scheduler currently uses process-start elapsed time and computes outside the write lock.

- [ ] **Step 3: Implement locked interval evaluation**

Change `VectorWriteService.applyRuntimeTick` to accept a suspend transform and execute it under the session Mutex after reading the latest state. In `RuntimeTickScheduler`, read `lastRuntimeTickAtMs` inside that transform, use `deltaTimeMs = (nowMs - previous).coerceAtLeast(0)`, run `fluctuation.deltaBetween(previousElapsed, nowElapsed)`, `ShockStateEngine.decay`, and `OmegaAccumulationEngine.accumulate` inside `inferenceExecutor.run`, then persist `lastRuntimeTickAtMs = nowMs` in the same write.

Change the sine engine to calculate `waveform(t1) - waveform(t0)` per dimension and clamp the resulting interval delta. A first tick with a null timestamp records the baseline and applies no historical delta.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run the focused command. Then run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.tick.*"
```

Expected: all tick tests pass, including existing Shock decay and Omega tests.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt core/src/commonMain/kotlin/io/openeden/runtime/tick core/src/commonTest/kotlin/io/openeden/runtime/tick core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt
git commit -m "fix(core): serialize runtime ticks by persisted time delta"
```

## Task 3: Unify Shock Signals And Activation Lifecycle

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/affect/ShockStateEngine.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/affect/ShockSignal.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`

- [ ] **Step 1: Add failing EMA and activation tests**

Test an existing active ShockState with intensity `0.8` receiving signal `0.2`; assert the merged intensity is `0.56`, the heartbeat latch is preserved, and Omega does not jump again. Test an inactive state receiving `1.0`; assert intensity `0.4`, activation, heartbeat latch reset, and Omega jump `0.06`.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.state.RuntimeInvariantTest" --tests "io.openeden.runtime.pipeline.MessagePipelineTest"
```

Expected: the existing implementation replaces ShockState from a null current state and cannot merge against the persisted state.

- [ ] **Step 3: Implement a pure signal merge and locked write path**

Create `ShockSignal(description, intensity, decayLambda, triggeredAt)`. Make `ShockStateEngine.merge(current, signal)` pure, clamp input values, apply alpha `0.4`, preserve the existing trigger time while active, and return an activation flag. Add `VectorWriteService.applyShockSignalLocked` that reads the latest state, calls the merge, applies `OmegaState.increase(intensity * 0.15f)` only on activation, and writes the result under the existing Mutex.

Update `detectFromLlmOutput` to return `ShockSignal?` after the `0.65` confidence and delta gates. Add the public `applyShock(sessionId, signal)` path to call the locked merge. Keep `description` free text and do not add a source enum.

- [ ] **Step 4: Wire pipeline back-detection through the merge path**

Replace `commitTurnLocked(... shock = detectedShock)` with a locked signal merge before the final state commit, or fold the merge into `commitTurnLocked` so the latest persisted ShockState is used. Ensure a retry with `TurnCommitOutcome.ALREADY_COMMITTED` cannot apply the signal or Omega jump twice.

- [ ] **Step 5: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.state.RuntimeInvariantTest" --tests "io.openeden.runtime.pipeline.MessagePipelineTest" --tests "io.openeden.runtime.heartbeat.HeartbeatSchedulerTest"
```

Expected: EMA, confidence gating, retry idempotency, and one-shot heartbeat behavior pass.

- [ ] **Step 6: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/affect core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt
git commit -m "fix(core): merge shock signals through session state"
```

## Task 4: Enforce Codebook Grounding With One Repair Attempt

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/llm/LlmOutputValidator.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmGroundingValidation.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/llm/LlmOutputValidatorTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`

- [ ] **Step 1: Add failing grounding tests**

Add a validator test with active nodes `NODE_12` and `NODE_45`: `internal_logic = "uses NODE_12"` is grounded, while `internal_logic = "logic"` is not. Add a pipeline test with an LLM client that returns an ungrounded output first and a grounded output second; assert two calls, one state evolution, and a regeneration trace. Add a second test returning ungrounded output twice; assert no vector/evolution/transcript/memory mutation.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.LlmOutputValidatorTest" --tests "io.openeden.runtime.pipeline.MessagePipelineTest"
```

Expected: grounding tests fail because validation currently checks only schema fields.

- [ ] **Step 3: Implement grounding validation and repair prompt**

Add `LlmGroundingValidation.validate(output, quantization)` that returns valid when at least one exact `activeNodes` value appears in `internalLogic`. Treat an empty active-node list as a quantizer failure and require the existing heuristic fallback tag before prompt construction. Add an English logical-core rule requiring the exact active node identifier in `internal_logic`.

In `MessagePipeline`, keep schema-invalid output as immediate rejection. For schema-valid but ungrounded output, call the LLM exactly once more with a repair instruction appended to the logical-core system section. Revalidate schema and grounding. Only pass the final validated output into `commitTurnLocked`, relationship write, memory write, and diary trigger.

- [ ] **Step 4: Run focused tests and confirm GREEN**

Run the focused command and then:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.pipeline.*" --tests "io.openeden.llm.*"
```

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/llm core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/llm/LlmOutputValidatorTest.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt
git commit -m "feat(core): require codebook-grounded llm output"
```

## Task 5: Add Utility Filtering And Strict MIXED Retrieval

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/InMemoryMemoryPalace.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryStore.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/RetrievalResult.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/memory/MemoryUtilityFilter.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/memory/MemoryUtilityFilterConfig.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/InMemoryMemoryPalaceTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/MemoryUtilityFilterTest.kt`

- [ ] **Step 1: Add failing retrieval tests**

Use ten distinct memories to assert MIXED returns six congruent and four positive-skew entries when all candidates exist. Add shortage tests asserting no duplicates. Add filter tests for NaN embeddings, below-minimum similarity, no baseline, and entropy above baseline; assert the result includes accepted/rejected counts and a degraded tag only when baseline computation fails.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.memory.InMemoryMemoryPalaceTest" --tests "io.openeden.memory.MemoryUtilityFilterTest"
```

Expected: MIXED count and filter tests fail because the palace uses six results and has no utility boundary.

- [ ] **Step 3: Implement the pure filter**

Create `MemoryUtilityFilterConfig(minSemanticSimilarity, minEmotionalSimilarity, entropyTolerance, baselineWindow)` with validated finite ranges. Implement `MemoryUtilityFilter.filter(candidates, querySemantic, queryEmotion, baselineEntropy)` as a pure function that rejects non-finite embedding values, rejects candidates below both similarity minimums, and applies the entropy gate only when a finite baseline exists. Return candidates plus counts and a `degraded` flag.

- [ ] **Step 4: Integrate filter and MIXED composition**

Run filter and entropy calculations inside `inferenceExecutor.run`. Compute baseline from stable/daily memory embeddings. Set default `maxResults = 10`; select exactly six congruent and four positive-skew candidates when possible, fill shortages from the remaining pool without duplicate IDs, and preserve CONTRAST center-symmetric mapping and momentum scoring. Carry counts and filter trace tags in `RetrievalResult`.

- [ ] **Step 5: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.memory.*" --tests "io.openeden.runtime.pipeline.MessagePipelineTest"
```

- [ ] **Step 6: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/memory core/src/commonTest/kotlin/io/openeden/memory core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt
git commit -m "feat(core): filter memory utility and enforce mixed retrieval ratio"
```

## Task 6: Add SQLDelight Schema For Tick And Lifecycle State

**Files:**
- Modify: `server/src/main/sqldelight/io/openeden/server/db/SessionState.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/6.sqm`
- Create: `server/src/main/sqldelight/io/openeden/server/db/Incarnation.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Transcript.sq`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightSessionStateStoreTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStoreTest.kt`

- [ ] **Step 1: Add failing migration/persistence tests**

Add tests that write and reopen `last_runtime_tick_at_ms`, lifecycle status, and termination metadata. Assert pre-v6 databases map to `ACTIVE`, preserve persona selection, and do not lose `evolution_index`. Assert runtime tables can be selected by incarnation ID for purge.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightSessionStateStoreTest" --tests "io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStoreTest"
```

- [ ] **Step 3: Add migration and SQL queries**

Add the nullable tick timestamp and lifecycle columns to session/incarnation storage. Migration `6.sqm` must add columns/tables without rewriting existing vector, persona, Omega, or evolution values. Add indexes and purge-select/delete queries scoped by the active incarnation. Runtime rows that currently lack ownership receive an incarnation column populated from the singleton active incarnation during migration.

- [ ] **Step 4: Implement store mappings**

Update `SqlDelightSessionStateStore` to map and upsert the tick timestamp. Add `SqlDelightIncarnationLifecycleStore` using `withContext(Dispatchers.IO)` and transaction guards for legal transitions and idempotent request IDs. Preserve all existing atomic transcript commit behavior.

- [ ] **Step 5: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.persistence.sqldelight.*"
```

- [ ] **Step 6: Commit**

```powershell
git add server/src/main/sqldelight server/src/main/kotlin/io/openeden/server/persistence/sqldelight server/src/test/kotlin/io/openeden/server/persistence/sqldelight
git commit -m "feat(server): persist kernel tick and incarnation lifecycle"
```

## Task 7: Implement Diary-Only Termination Coordinator

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/TerminationCoordinator.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/TerminationResult.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt`
- Create: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTerminationRepository.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/lifecycle/TerminationCoordinatorTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTerminationRepositoryTest.kt`

- [ ] **Step 1: Add failing lifecycle gate tests**

Assert that `CRITICAL` allows ordinary turns but injects the termination rule, `TERMINATING` rejects turns and heartbeat/tick evaluations, and an exact `[OPENEDEN_TERMINATE]` token in `response` or user input is inert. Assert the same token in `internal_logic` terminates only with latest Omega at or above `0.95` and grounded output.

- [ ] **Step 2: Run focused tests and confirm RED**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.lifecycle.TerminationCoordinatorTest"
```

- [ ] **Step 3: Implement the in-memory coordinator**

Define `TerminationCoordinator` dependencies for lifecycle store, transcript store, memory purge/archive boundary, relationship purge, trace purge, diary task/checkpoint store, and clock. Implement `requestTermination` as an idempotent suspend operation: transition to `TERMINATING`, drain final diary work, build immutable archive records with SHA-256, verify source coverage and hashes, execute the purge contract, and mark `TERMINATED`. Return a stable `TerminationResult` without exposing transcript/vector/trace content.

- [ ] **Step 4: Add durable purge/archive transaction**

Implement `SqlDelightTerminationRepository` with one SQLite transaction that inserts archive rows, verifies the expected archive count, deletes all incarnation-owned runtime rows, and marks the lifecycle terminated. On any exception, rollback and leave `TERMINATING`. Archive rows must not implement `MemoryStore` or be returned by memory retrieval queries.

- [ ] **Step 5: Wire startup recovery and runtime gates**

At bootstrap, load lifecycle before scheduling heartbeat/tick. Resume `TERMINATING` before publishing the pipeline. Make pipeline, heartbeat scheduler, tick scheduler, and session creation consult the lifecycle store. Add critical threshold detection after locked Omega writes and persist `CRITICAL` monotonically. Add prompt rules and sentinel detection only for `CRITICAL` turns.

- [ ] **Step 6: Run focused tests and confirm GREEN**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.lifecycle.*" --tests "io.openeden.runtime.pipeline.*" --tests "io.openeden.runtime.heartbeat.*" --tests "io.openeden.runtime.tick.*"
.\gradlew.bat :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightTerminationRepositoryTest" --tests "io.openeden.server.bootstrap.*"
```

- [ ] **Step 7: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/lifecycle core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTerminationRepository.kt server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt core/src/commonTest/kotlin/io/openeden/runtime/lifecycle server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTerminationRepositoryTest.kt
git commit -m "feat(kernel): implement diary-only incarnation termination"
```

## Task 8: End-To-End Kernel Verification

**Files:**
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/runtime/KernelLifecycleE2ETest.kt`
- Modify only production files required by a failing regression test.

- [ ] **Step 1: Add the end-to-end regression test**

Seed a temporary SQLite database with a session, transcript turn, RAW and NARRATIVE memory, embedding rows, relationship state, trace span, diary task/checkpoint, Omega at `0.95`, and ShockState. Execute a grounded critical termination turn. Reopen the database and assert lifecycle `TERMINATED`, no session/transcript/runtime memory/relationship/trace/task rows, exactly the verified diary archive rows, and a new explicit incarnation with no archive retrieval.

- [ ] **Step 2: Run the test and confirm RED if integration is incomplete**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.runtime.KernelLifecycleE2ETest"
```

- [ ] **Step 3: Fix only integration failures with focused tests**

Keep all purge scope keyed by incarnation ID. Do not add archive reads to prompt or retrieval code. Do not expose a new HTTP or CLI operation.

- [ ] **Step 4: Run the kernel verification gate**

```powershell
.\gradlew.bat :core:jvmTest :server:test
git diff --check
git status --short
```

Expected: both kernel modules exit successfully with zero test failures and only intentional source/doc changes remain.

- [ ] **Step 5: Run the root test task for transparent scope reporting**

```powershell
.\gradlew.bat test
```

Report any remaining Windows terminal Unicode E2E failures separately; do not change CLI/Web/adapter code as part of kernel completion.

- [ ] **Step 6: Commit final regression coverage**

```powershell
git add core/src/commonTest server/src/test
git commit -m "test(kernel): verify complete runtime lifecycle"
```

## Plan Self-Review

- Persona-as-Data: no task adds persona behavior to Kotlin; prompt changes add only English logical constraints.
- Non-blocking: database calls use IO context; math/model work uses `InferenceExecutor`; lifecycle coordinator is suspendable.
- VQ-VAE: quantization remains before prompt; fallback tags are preserved; grounding failure is bounded and traceable.
- D: no task adds a ninth vector dimension or D persistence.
- Mutex: tick, Shock merge, turn write, evolution index, and lifecycle state transitions are serialized.
- Scope: no QQ/OneBot code, Web UI, HTTP admin endpoint, or new CLI command is included.
- No unresolved placeholders or unspecified test commands remain.
