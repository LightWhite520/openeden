# Production Local Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the local OpenEden runtime with serialized stateful turns, durable SQLite/SQLDelight Memory Palace storage, rebuildable vector retrieval, production model boundaries, dynamic centroid, durable Diary work, and structured traces.

**Architecture:** Keep `core` platform-independent and expose ports for session state, memories, embeddings, quantization, Diary, and traces. The JVM `server` module owns SQLDelight and production assembly. A per-session coroutine gate surrounds the full state-dependent turn, while model and vector work runs through `InferenceExecutor`; no global store, effect bus, or event-sourced persistence is introduced.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines/Flow, Ktor, SQLDelight SQLite, kotlinx.serialization, existing local model artifact and codebook pipeline, JVM test fixtures.

---

### Task 1: Make the turn coordinator truly session-serialized

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/RuntimeContracts.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/TurnCoordinatorConcurrencyTest.kt`

- [ ] **Step 1: Add a per-session gate port and concurrency test**

Add a `SessionTurnGate` that returns a stable `Mutex` per session and test 100 concurrent turns against a deliberately delayed fake LLM. Assert every turn observes a unique, monotonically increasing evolution index and the final persisted vector contains every delta.

- [ ] **Step 2: Run the focused test and verify the current implementation fails**

Run `./gradlew :core:jvmTest --tests io.openeden.runtime.TurnCoordinatorConcurrencyTest` with `JAVA_HOME=F:\SDK\JDK21`.
Expected: the test exposes lost ordering or duplicate state reads before the coordinator is locked.

- [ ] **Step 3: Move the complete state-dependent pipeline under the session gate**

Use `gate.withSession(sessionId) { ... }` around latest-state read, centroid/origin update, pre-tick, quantization, retrieval, prompt construction, LLM call, validation, and the state/memory commit. Keep the existing `VectorWriteService` lock-aware and do not acquire the same mutex recursively. Apply `vector_delta` to the captured pre-ticked snapshot, and reject invalid output without changing state.

- [ ] **Step 4: Run invariant and concurrency tests**

Run `./gradlew :core:jvmTest --tests io.openeden.runtime.TurnCoordinatorConcurrencyTest --tests io.openeden.runtime.RuntimeInvariantTest --tests io.openeden.runtime.MessagePipelineTest`.
Expected: all focused tests pass and cross-session turns remain concurrent.

- [ ] **Step 5: Commit the state-flow milestone**

Run `git add core/src/main/kotlin core/src/commonTest/kotlin` and `git commit -m "feat: serialize stateful turns per session"`.

### Task 2: Add durable Memory Palace SQLDelight schema and repository

**Files:**
- Modify: `server/src/main/sqldelight/io/openeden/server/db/SessionState.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`
- Create: `server/src/main/kotlin/db/SqlDelightMemoryRepository.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryContracts.kt`
- Create: `server/src/test/kotlin/SqlDelightMemoryRepositoryTest.kt`

- [ ] **Step 1: Define the durable memory, embedding, Diary, and trace tables**

Create SQLDelight queries for `memory_entries`, `memory_embeddings`, `diary_tasks`, and `trace_spans`. Store the eight coordinates explicitly as scalar columns; store `delta_vec` and `snapshot_origin` as eight scalar columns; keep embeddings in a separate model-versioned row. Do not add a D column.

- [ ] **Step 2: Write restart and migration tests**

Insert a RAW memory with user/platform metadata, close the SQLite driver, reopen it, and assert content, room/kind, all eight coordinates, momentum metadata, and embedding model ID survive. Assert the missing-embedding case remains readable and queryable.

- [ ] **Step 3: Implement the repository port**

Implement `MemoryRepository` with transactional raw writes, bounded reads by session/room/kind/time, stable-vector reads for centroid calculation, embedding updates, and linked NARRATIVE writes. Keep SQLDelight calls behind suspend methods and leave the database as source of truth.

- [ ] **Step 4: Run SQLDelight tests and the existing server tests**

Run `./gradlew :server:test --tests io.openeden.server.SqlDelightMemoryRepositoryTest --tests io.openeden.server.SqlDelightSessionStateStoreTest`.
Expected: restart continuity and existing session persistence pass.

- [ ] **Step 5: Commit the persistence milestone**

Run `git add server/src core/src/main/kotlin/io/openeden/memory` and `git commit -m "feat: persist Memory Palace records in SQLite"`.

### Task 3: Replace retrieval scans with a rebuildable vector index

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryContracts.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/memory/RebuildableVectorIndex.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/memory/RebuildableVectorIndexTest.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/memory/RetrievalGoldenTest.kt`

- [ ] **Step 1: Define `VectorIndex` and golden retrieval cases**

Define incremental insert/remove, dirty marking, bounded batch rebuild, and search APIs. Add golden fixtures for CONGRUENT, MIXED 6:4, and CONTRAST, including semantic/emotional/momentum/recency scores and dynamic beta under high P/S.

- [ ] **Step 2: Verify the new tests fail against the current in-memory implementation**

Run `./gradlew :core:jvmTest --tests io.openeden.memory.RebuildableVectorIndexTest --tests io.openeden.memory.RetrievalGoldenTest`.
Expected: compilation fails for the missing index contract or golden assertions fail because the current palace has no durable/rebuildable index.

- [ ] **Step 3: Implement the rebuildable index and score calculation**

Index normalized semantic and emotional embeddings by memory ID. Narrow by session, room, kind, and time before scoring. Calculate `alpha * semantic + beta * emotional + bounded momentum + recency`, record component scores and trace ID, and mark the index dirty if an update fails.

- [ ] **Step 4: Wire retrieval modes through `RetrievalResult`**

Select the mode once, implement center-symmetric mapping for CONTRAST, combine congruent and positive-skew candidate lists for MIXED, and ensure Prompt Builder consumes the carried mode without re-evaluating state.

- [ ] **Step 5: Run retrieval and existing memory tests**

Run `./gradlew :core:jvmTest --tests io.openeden.memory.RebuildableVectorIndexTest --tests io.openeden.memory.RetrievalGoldenTest --tests io.openeden.memory.InMemoryMemoryPalaceTest --tests io.openeden.memory.LocalEmbeddingModelsTest`.

- [ ] **Step 6: Commit the retrieval milestone**

Run `git add core/src/main/kotlin/io/openeden/memory core/src/commonTest/kotlin/io/openeden/memory` and `git commit -m "feat: add rebuildable vector retrieval"`.

### Task 4: Harden the local model and Codebook production boundary

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/codebook/Codebook.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/model/LocalModelArtifact.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/model/LocalModelArtifactLoader.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/InferenceExecutor.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/codebook/CodebookValidationTest.kt`
- Modify: `src/main/kotlin/io/openeden/Main.kt`

- [ ] **Step 1: Add model validation tests**

Cover missing artifact, malformed codebook rows, duplicate node IDs, dimension mismatch, NaN/Infinity, empty output, low confidence, and predictor failure. Assert every approved fallback carries `codebook=HEURISTIC_FALLBACK` plus a machine-readable reason.

- [ ] **Step 2: Implement validation and bounded inference execution**

Validate all model dimensions and finite values at load time. Ensure quantization, embeddings, coordinate mapping, symmetry transforms, and centroid math execute inside the dedicated inference executor. Keep predictor instances isolated or pooled and close them on shutdown.

- [ ] **Step 3: Make production assembly prefer local model paths**

Load and warm the configured artifact/codebook at startup. Keep deterministic fallback available only for explicit development configuration; production configuration must fail fast on missing required artifacts. Remove development stub defaults from production entry points while retaining test-only injection.

- [ ] **Step 4: Run model and CLI tests**

Run `./gradlew :core:jvmTest --tests io.openeden.codebook.* --tests io.openeden.model.* :test --tests io.openeden.OpenEdenCliTest`.

- [ ] **Step 5: Commit the model milestone**

Run `git add core/src/main core/src/jvmMain src/main` and `git commit -m "feat: enforce production local model boundaries"`.

### Task 5: Add bounded dynamic centroid and durable Diary worker

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/HomeostasisCentroid.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/DiaryQueue.kt`
- Create: `server/src/main/kotlin/db/SqlDelightDiaryTaskStore.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/DiaryQueueRecoveryTest.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/HomeostasisCentroidDriftTest.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`

- [ ] **Step 1: Add centroid drift tests**

Assert stable/daily vectors use the configured default window of 32, fallback to persisted origin when insufficient, and never move more than the configured per-update dimension cap.

- [ ] **Step 2: Implement bounded centroid updates through `InferenceExecutor`**

Record source window and accepted movement in trace metadata; update the persisted origin under the same session gate, with no D persistence.

- [ ] **Step 3: Add durable Diary lease/retry tests**

Test max eight pending/running tasks per session, overflow tag, per-session serialization, lease expiry recovery, bounded exponential backoff, DEAD transition, and NARRATIVE creation without changing vector/Omega/evolution state.

- [ ] **Step 4: Implement the SQLDelight Diary store and worker**

Lease tasks atomically, requeue expired leases on startup, process one task at a time per session, persist NARRATIVE memories separately, and keep Diary work outside the user turn lock.

- [ ] **Step 5: Run Diary/centroid/server runtime tests**

Run `./gradlew :core:jvmTest --tests io.openeden.runtime.DiaryQueueRecoveryTest --tests io.openeden.runtime.HomeostasisCentroidDriftTest :server:test`.

- [ ] **Step 6: Commit the background-work milestone**

Run `git add core/src server/src` and `git commit -m "feat: add durable diary processing and centroid drift"`.

### Task 6: Add structured traces and production assembly verification

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/trace/TraceContracts.kt`
- Create: `server/src/main/kotlin/db/SqlDelightTraceStore.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Create: `server/src/test/kotlin/ProductionRuntimeContinuityTest.kt`

- [ ] **Step 1: Write trace sanitization and restart continuity tests**

Assert structured spans contain trace/turn/session IDs, bounded attributes, stage/status/error code, no credentials, no complete prompts, and no complete `internal_logic`. Restart the production assembly and verify session state, memories, Diary tasks, and traces remain available.

- [ ] **Step 2: Implement structured trace context and SQLDelight persistence**

Emit spans for state load, pre-tick, centroid, quantization/fallback, retrieval, prompt, LLM, validation, commit, memory, Shock/Omega, and Diary events. Classify failures as TURN_REJECTED, DEGRADED, AUXILIARY_FAILED, FATAL_CONFIG, or CRITICAL_DEGRADATION.

- [ ] **Step 3: Wire SQLite Memory Palace, rebuildable index, local embeddings, Diary, and traces into server and CLI**

Ensure production defaults are durable SQLite plus local model-backed embeddings, while adapters continue to call the shared core pipeline. Heartbeat delivery remains owner-only and disconnected output is dropped.

- [ ] **Step 4: Run the full verification suite**

Run `./gradlew jvmTest test` with `JAVA_HOME=F:\SDK\JDK21`; then run a CLI message/state continuity smoke test against a temporary SQLite database. Expected: all tests pass and the second process reads the first process's evolution index, vector, and memory records.

- [ ] **Step 5: Scan the final implementation against AGENTS.md**

Run `rg -n "D|internal_logic|codebook=HEURISTIC_FALLBACK|withLock|Dispatchers|persona|DevelopmentLlmStub|DeterministicMemory" core server src`. Confirm D is derived only, persona prose remains YAML, all model/heavy math is on `InferenceExecutor`, and production entry points do not silently select development stubs.

- [ ] **Step 6: Commit and invoke finishing workflow**

Run `git add core/src server/src src/main docs/superpowers/plans/2026-07-12-production-local-core.md` and `git commit -m "feat: complete production local core assembly"`. Then use `superpowers:finishing-a-development-branch` for final verification and integration options.
