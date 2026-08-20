# Qdrant Vector Database Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a recoverable Qdrant candidate index while keeping SQLite authoritative and preserving automatic in-memory fallback for one OpenEden server instance.

**Architecture:** SQLite commits memory content, local embeddings, and durable `PENDING` projection work in one transaction. A suspendable Ktor Qdrant client projects vectors asynchronously; a resilient index uses Qdrant for candidate IDs and the existing in-memory index when Qdrant is unavailable. SQLite hydrates candidates before the unchanged Memory Palace final ranking.

**Tech Stack:** Kotlin 2.3/JVM 21, Ktor 3.5 HTTP client and `MockEngine`, SQLDelight 2.0.2/SQLite, kotlinx.serialization, coroutines, existing `InferenceExecutor`, Qdrant REST API, Docker Compose.

---

## File Map

Core:

- Modify `core/src/commonMain/kotlin/io/openeden/memory/VectorSearchHit.kt`: stable `memoryId` plus nullable hydrated entry.
- Modify `core/src/commonMain/kotlin/io/openeden/memory/RebuildableVectorIndex.kt`: emit the new hit shape without changing cosine behavior.
- Modify `core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt`: Qdrant healthy/fallback/recovered tags.
- Test `core/src/commonTest/kotlin/io/openeden/memory/RebuildableVectorIndexTest.kt`.

SQLite:

- Modify `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`.
- Create `server/src/main/sqldelight/io/openeden/server/db/8.sqm`.
- Create `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/MemoryVectorProjectionStore.kt`.
- Modify `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt`.
- Test `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/MemoryVectorProjectionStoreTest.kt` and the existing repository test.

Qdrant and resilience:

- Modify `server/build.gradle.kts`.
- Create `server/src/main/kotlin/io/openeden/server/vector/qdrant/QdrantModels.kt`, `QdrantClient.kt`, `QdrantCollectionNaming.kt`, and `QdrantVectorIndex.kt`.
- Create `server/src/main/kotlin/io/openeden/server/vector/QdrantCircuitBreaker.kt`, `ResilientVectorIndex.kt`, `QdrantProjectionSynchronizer.kt`, and `VectorDatabaseStatus.kt`.
- Add focused tests under `server/src/test/kotlin/io/openeden/server/vector`.

Runtime and operations:

- Create `server/src/main/kotlin/io/openeden/server/bootstrap/VectorDatabaseConfig.kt`.
- Modify `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`, `server/src/main/resources/application.yaml`, diagnostics DTO/route/tests.
- Create `compose.yaml`; update `README.md` and `README.zh-CN.md`.
- Add opt-in `QdrantIntegrationTest.kt`.

## Task 1: Candidate Hit Contract

**Files:** `VectorSearchHit.kt`, `RebuildableVectorIndex.kt`, `RebuildableVectorIndexTest.kt`.

- [ ] Write a failing test that inserts entries and asserts stable IDs, non-null in-memory entries, similarity values, and existing session/room/kind filters.
- [ ] Run `.\\gradlew.bat :core:jvmTest --tests io.openeden.memory.RebuildableVectorIndexTest`; expect compilation failure.
- [ ] Change the hit to:

```kotlin
data class VectorSearchHit(
    val memoryId: String,
    val entry: MemoryEntry?,
    val semanticSimilarity: Float,
    val emotionalSimilarity: Float,
)
```

Update only the in-memory constructor call with `memoryId = entry.id`. Do not change cosine or sorting.
- [ ] Run the focused index test and `InMemoryMemoryPalaceTest`; expect PASS.
- [ ] Commit:

```powershell
git add core/src/commonMain/kotlin/io/openeden/memory core/src/commonTest/kotlin/io/openeden/memory/RebuildableVectorIndexTest.kt
git commit -m "refactor: expose stable memory ids from vector hits"
```

## Task 2: Durable SQLite Projection Work

**Files:** `Memory.sq`, `8.sqm`, `MemoryVectorProjectionStore.kt`, `MemoryVectorProjectionStoreTest.kt`.

- [ ] Write failing tests for enqueue, ordered due claiming, READY acknowledgement, retry/backoff metadata, RUNNING recovery, and close/reopen durability.
- [ ] Run `.\\gradlew.bat :server:test --tests io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStoreTest`; expect missing-schema compilation failure.
- [ ] Add `memory_vector_sync`:

```sql
CREATE TABLE memory_vector_sync (
    memory_id TEXT NOT NULL PRIMARY KEY REFERENCES memory_entries(id) ON DELETE CASCADE,
    model_id TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INTEGER NOT NULL,
    available_at_ms INTEGER NOT NULL,
    last_error TEXT,
    updated_at_ms INTEGER NOT NULL
);
CREATE INDEX memory_vector_sync_due_index
ON memory_vector_sync(status, available_at_ms, memory_id);
```

Add migration 8 with the same table/index, plus SQLDelight queries for upsert/enqueue, due batch selection, claim, READY, reschedule, RUNNING recovery, pending counts, and model-refresh selection.
- [ ] Implement the focused store on the persistence IO dispatcher. Validate `PENDING/RUNNING/READY`, positive batch sizes, timestamps, and capped sanitized errors.
- [ ] Rerun the focused test; expect PASS.
- [ ] Commit the SQLDelight and store files.

## Task 3: Projection-Aware Memory Repository

**Files:** `SqlDelightMemoryRepository.kt`, `SqlDelightMemoryRepositoryTest.kt`.

- [ ] Add failing tests proving one SQLite transaction writes entry, local embeddings, and PENDING projection; the normal overload uses configured `local-v1` instead of `unknown`; writes do not call a remote client; candidate IDs hydrate in one bounded query; wrong session/model rows are rejected; fallback rebuilds after reopen.
- [ ] Run the focused repository test; expect failure because model/projection dependencies and hydration do not exist.
- [ ] Add `activeModelId`, projection store, fallback index, and wake signal dependencies while keeping existing test constructors source-compatible.
- [ ] Change write to transactionally call `writeEntry`, `upsertEmbedding(..., activeModelId, ..., "READY")`, and projection enqueue; only after commit call fallback insert and `trySend`. Never perform HTTP in `write`.
- [ ] Add one `IN ?` SQLDelight hydration query, cap IDs, filter session/model, and restore remote result order. Keep Memory Palace final mode scoring unchanged.
- [ ] Run repository and core memory tests; expect PASS.
- [ ] Commit with `git commit -m "feat: make memory repository projection-aware"`.

## Task 4: Qdrant REST Client

**Files:** `server/build.gradle.kts`, `QdrantModels.kt`, `QdrantClient.kt`, `QdrantClientTest.kt`.

- [ ] Add Ktor client core/content-negotiation/CIO implementations and `ktorLibs.client.mock` for tests.
- [ ] Write MockEngine tests for collection GET/PUT, payload index PUT, batch points PUT, semantic search POST with exact filters, API-key header, non-2xx, malformed JSON, timeout, and cancellation.
- [ ] Run the focused client test; expect missing DTO/client failure.
- [ ] Add private serializable DTOs for named vectors, points, payload, collection config, and search hits; ignore unknown fields.
- [ ] Implement suspendable operations with request timeout, JSON content type, optional `api-key`, sanitized/truncated error categories, and unconditional CancellationException propagation.
- [ ] Run the client tests; expect PASS.
- [ ] Commit with `git commit -m "feat: add suspendable qdrant rest client"`.

## Task 5: Collection Naming and Qdrant Index

**Files:** `QdrantCollectionNaming.kt`, `QdrantVectorIndex.kt`, naming/index tests.

- [ ] Write failing tests for deterministic model-aware collection names, collision-resistant sanitized names, deterministic UUID point IDs, both named vectors/payload, exact filters, null remote entries, and dimension validation.
- [ ] Implement ASCII prefix plus short SHA-256 suffix of the original model ID. Derive UUID bytes from SHA-256(memory ID), set UUID version/variant bits, and retain the original ID in payload.
- [ ] Implement lazy compatible collection creation with Cosine semantic/emotional named vectors and payload indexes. Reject empty, non-finite, zero, and incompatible vectors before network calls.
- [ ] Implement search as a semantic Qdrant query returning `VectorSearchHit(memoryId, entry = null, ...)`. Do not fabricate full entries.
- [ ] Run `.\\gradlew.bat :server:test --tests io.openeden.server.vector.qdrant.QdrantCollectionNamingTest --tests io.openeden.server.vector.qdrant.QdrantVectorIndexTest`; expect PASS.
- [ ] Commit with `git commit -m "feat: add qdrant named-vector index"`.

## Task 6: Resilience and Synchronization

**Files:** `QdrantCircuitBreaker.kt`, `ResilientVectorIndex.kt`, `QdrantProjectionSynchronizer.kt`, `VectorDatabaseStatus.kt`, focused tests.

- [ ] Write fake-clock tests proving three failures open the circuit, open bypasses primary, half-open success closes it, failed probe reopens it, and cancellation does not count as failure.
- [ ] Implement Mutex-protected closed/open/half-open state, configurable threshold/probe interval, fallback search, and safe status/trace metadata. Inserts/rebuilds keep fallback current.
- [ ] Write virtual-time synchronizer tests for conflated wake draining, periodic recovery, exact READY acknowledgement, bounded five-minute backoff, RUNNING recovery, collection recreation/reindex, and shutdown durability.
- [ ] Implement one child coroutine: recover RUNNING rows, claim bounded batches, load embeddings, ensure collection, upsert, mark READY; on non-cancellation failure reschedule with jitter and update status.
- [ ] Run the three focused test classes; expect PASS.
- [ ] Commit with `git commit -m "feat: add qdrant fallback and projection recovery"`.

## Task 7: Configuration, Model Refresh, and Runtime Wiring

**Files:** `VectorDatabaseConfig.kt`, `Runtime.kt`, `application.yaml`, config/bootstrap tests.

- [ ] Write tests for defaults (enabled, localhost URL, prefix, model ID, timeout, interval, batch, threshold), overrides, invalid URL/blank model/invalid numbers.
- [ ] Implement validated config using the existing `optional` parser style. Never log the API key.
- [ ] Wire enabled Qdrant client, collection naming, resilient index, repository model ID/projection store, synchronizer scope, and reverse-order shutdown closers in `Runtime.kt`. Preserve the unrelated existing Runtime.kt user edit.
- [ ] Add bounded startup model refresh: for rows with a different model ID, regenerate semantic text and emotional snapshot embeddings through `InferenceExecutor`, transactionally replace local embeddings, then enqueue active-model projection. Yield between batches and do not delay readiness.
- [ ] Verify startup succeeds with unreachable Qdrant; run config/bootstrap tests.
- [ ] Commit with `git commit -m "feat: wire qdrant into server runtime"`.

## Task 8: Diagnostics, Trace Tags, Compose, and Docs

**Files:** `TraceTag.kt`, diagnostics DTO/route/tests, `compose.yaml`, both README files.

- [ ] Write tests that protected diagnostics reports backend, collection, circuit, pending/total counts, last success, and sanitized error; disabled diagnostics remains 404; health remains ready during fallback.
- [ ] Add Qdrant success/fallback/recovery/projection/retry/collection/rebuild tags and safe status projection. Never expose credentials, memory text, or embeddings in diagnostics.
- [ ] Add pinned Qdrant Compose service, named volume, localhost 6333 port, and health check. Document startup, remote URL/API key, model changes, SQLite backup authority, and collection deletion rebuild.
- [ ] Run diagnostics tests and `git diff --check`; expect PASS/no whitespace errors.
- [ ] Commit with `git commit -m "docs: document qdrant operations and diagnostics"`.

## Task 9: Optional Real-Qdrant Integration

**File:** `server/src/test/kotlin/io/openeden/server/vector/qdrant/QdrantIntegrationTest.kt`.

- [ ] Skip unless `OPENEDEN_QDRANT_TEST_URL` is set. Use a unique collection, create named vectors/indexes, upsert two sessions, verify filtering and IDs, exercise fallback, and delete only the test collection in `finally`.
- [ ] Run without the variable; expect SKIPPED:
```powershell
.\\gradlew.bat :server:test --tests io.openeden.server.vector.qdrant.QdrantIntegrationTest
```
- [ ] When Qdrant is available, set `$env:OPENEDEN_QDRANT_TEST_URL = "http://localhost:6333"` and rerun; expect PASS.
- [ ] Commit with `git commit -m "test: add opt-in qdrant integration coverage"`.

## Task 10: Full Verification and Handoff

- [ ] Run `.\\gradlew.bat :core:jvmTest :server:test`; expect PASS with integration skipped unless explicitly enabled.
- [ ] Run `.\\gradlew.bat build`; expect all SQLDelight-generated and platform sources compile.
- [ ] Run `git diff --check`, `git status --short`, and inspect the aggregate diff. Confirm no credentials, memory bodies, embeddings, or unrelated user changes were committed.
- [ ] With Qdrant stopped, verify server startup, `/health`, memory write, retrieval, and fallback trace. Start Qdrant and verify pending drain; stop it and verify fallback again.
- [ ] Check every acceptance criterion in `docs/superpowers/specs/2026-08-20-qdrant-vector-database-design.md`, especially SQLite authority, non-blocking writes, durable retries, collection rebuild, model isolation, cancellation, and unchanged Memory Palace modes.

## Plan Self-Review

- Spec coverage: collection schema, named vectors, payload filters, SQLite authority, atomic writes, durable projection states, retry/backoff, circuit breaker, fallback, model refresh, diagnostics, Compose deployment, integration, non-blocking execution, cancellation, and tests are covered by Tasks 1-10.
- Placeholder scan: every task names files, test behavior, commands, and expected outcomes; no incomplete implementation instruction remains.
- Type consistency: Task 1 introduces `VectorSearchHit.memoryId` and nullable `entry`; Task 3 hydrates null entries; Task 5 emits them; Task 7 wires `modelId` and the projection store.
- Scope: PostgreSQL, multi-instance coordination, administration endpoints, and persona changes remain excluded.


