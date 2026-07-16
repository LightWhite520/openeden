# Incarnation Termination and Diary Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Terminate the active ATRI incarnation by preserving an immutable, readable Narrative Diary archive and physically deleting every other piece of incarnation data before a new incarnation may start.

**Architecture:** Introduce a global lifecycle gate above existing per-session mutexes, a SQLDelight lifecycle repository that archives and purges in one SQLite transaction, and separately authenticated owner/developer diary reads. The runtime stops proactive jobs before acquiring the termination gate; archived diary content is stored outside Memory Palace and is never exposed to prompt or retrieval interfaces.

**Tech Stack:** Kotlin 2.x, coroutines, Ktor, SQLDelight/SQLite transactions, kotlinx.serialization, SHA-256, kotlin.test.

---

## File Map

**Lifecycle domain**

- Create `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationStatus.kt`: ACTIVE/TERMINATING/TERMINATED states.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycleGate.kt`: global admission/drain gate.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/TerminationReason.kt`: system/developer reason data without persona semantics.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`: enter lifecycle gate before per-session gate.

**Archive domain and persistence**

- Create `core/src/commonMain/kotlin/io/openeden/archive/ArchivedDiaryEntry.kt`: immutable archive contract.
- Create `core/src/commonMain/kotlin/io/openeden/archive/DiaryArchivePage.kt`: owner/developer paging contract.
- Create `core/src/commonMain/kotlin/io/openeden/archive/DiaryArchiveReader.kt`: read-only interface; never implements `MemoryRetriever`.
- Create `server/src/main/sqldelight/io/openeden/server/db/DiaryArchive.sq`: immutable archive and lifecycle queries.
- Create `server/src/main/sqldelight/io/openeden/server/db/6.sqm`: termination/archive migration after transcript migration 5.
- Modify `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`: narrative selection and purge queries.
- Modify `server/src/main/sqldelight/io/openeden/server/db/SessionState.sq`: purge queries.
- Modify `server/src/main/sqldelight/io/openeden/server/db/Relationship.sq`: purge queries.
- Create `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationLifecycleRepository.kt`: atomic seal/archive/purge.
- Create `server/src/main/kotlin/io/openeden/server/runtime/IncarnationTerminationCoordinator.kt`: stop jobs, gate turns, call repository, halt runtime.

**Authenticated archive API**

- Create `server/src/main/kotlin/io/openeden/server/api/route/ArchiveAccess.kt`: owner and developer bearer gates.
- Create `server/src/main/kotlin/io/openeden/server/api/dto/ArchivedDiaryEntryDto.kt`: owner-safe diary row.
- Create `server/src/main/kotlin/io/openeden/server/api/dto/DeveloperArchivedDiaryEntryDto.kt`: approved technical metadata.
- Modify `server/src/main/kotlin/io/openeden/server/api/route/Routing.kt`: archive reads and developer termination command.
- Modify `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`: lifecycle service wiring and configuration.
- Modify `server/src/main/resources/application.yaml`: disabled-by-default archive/developer access configuration.

### Task 1: Add the Global Incarnation Lifecycle Gate

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationStatus.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycleGate.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/lifecycle/TerminationReason.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/lifecycle/IncarnationLifecycleGateTest.kt`

- [ ] **Step 1: Write failing gate tests**

```kotlin
@Test fun `termination waits for admitted turns and rejects new turns`() = runTest {
    val gate = IncarnationLifecycleGate()
    val release = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val turn = launch {
        gate.withActiveTurn { entered.complete(Unit); release.await() }
    }
    entered.await()

    val terminating = async { gate.beginTermination() }
    yield()
    assertFalse(terminating.isCompleted)
    assertFailsWith<IncarnationUnavailableException> { gate.withActiveTurn { } }
    release.complete(Unit)
    terminating.await()
    turn.join()
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.lifecycle.IncarnationLifecycleGateTest"`

Expected: FAIL because lifecycle types do not exist.

- [ ] **Step 3: Implement the gate without blocking threads**

Use a coroutine `Mutex`, an active-turn count, and a `CompletableDeferred<Unit>` drain signal. `withActiveTurn` increments only while status is ACTIVE and decrements in `finally`. `beginTermination` atomically changes status to TERMINATING and suspends until the active count reaches zero. `markTerminated` is allowed only after successful persistence.

```kotlin
enum class IncarnationStatus { ACTIVE, TERMINATING, TERMINATED }

data class TerminationReason(val code: String, val requestedAtMs: Long)

class IncarnationUnavailableException : IllegalStateException("ATRI incarnation is unavailable")
```

Do not use `synchronized`, `Thread.sleep`, or blocking latches.

- [ ] **Step 4: Run and verify GREEN**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.lifecycle.IncarnationLifecycleGateTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/io/openeden/runtime/lifecycle core/src/commonTest/kotlin/io/openeden/runtime/lifecycle
git commit -m "feat(runtime): gate incarnation lifecycle"
```

### Task 2: Apply Lifecycle Admission Outside Per-Session Turns

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineLifecycleTest.kt`

- [ ] **Step 1: Write failing pipeline admission tests**

```kotlin
@Test fun `terminating incarnation rejects user and heartbeat turns before inference`() = runTest {
    val gate = IncarnationLifecycleGate()
    gate.beginTermination()
    val llm = CountingLlmClient()
    val pipeline = DevelopmentMessagePipeline.create(testPersona(), llmClient = llm, lifecycleGate = gate)

    assertFailsWith<IncarnationUnavailableException> { pipeline.handle(userRequest()) }
    assertFailsWith<IncarnationUnavailableException> { pipeline.handle(heartbeatRequest()) }
    assertEquals(0, llm.calls)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineLifecycleTest"`

Expected: FAIL because pipeline does not use the lifecycle gate.

- [ ] **Step 3: Wrap the existing per-session gate**

```kotlin
fun handleStreaming(request: DevelopmentMessageRequest): Flow<DevelopmentMessageEvent> = flow {
    lifecycleGate.withActiveTurn {
        val sessionId = "${request.platform}:${request.scopeId}"
        turnGate.withSession(sessionId) {
            emit(DevelopmentMessageEvent.Stage(DevelopmentStage.PREPARING))
            emit(DevelopmentMessageEvent.Completed(handleLocked(request, sessionId, ::emit)))
        }
    }
}
```

The global gate controls admission only. Existing per-session mutexes still serialize vector, evolution index, and write-back operations; do not replace them with a global mutex.

- [ ] **Step 4: Run pipeline invariant tests**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineLifecycleTest" --tests "io.openeden.runtime.session.TurnCoordinatorConcurrencyTest" --tests "io.openeden.runtime.pipeline.MessagePipelineStreamingTest"`

Expected: PASS; independent active sessions remain concurrent while ACTIVE.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineLifecycleTest.kt
git commit -m "feat(runtime): enforce incarnation admission"
```

### Task 3: Define a Read-Only Diary Archive Contract

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/archive/ArchivedDiaryEntry.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/archive/DiaryArchivePage.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/archive/DiaryArchiveReader.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/archive/DiaryArchiveContractTest.kt`

- [ ] **Step 1: Write a failing archive paging contract test**

```kotlin
@Test fun `archive reader exposes immutable diary pages`() = runTest {
    val reader: DiaryArchiveReader = FakeDiaryArchiveReader(entries = listOf(archivedDiary("d1")))
    val page = reader.page("atri-1", 50, null)
    assertEquals(listOf("d1"), page.entries.map { it.sourceDiaryId })
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :core:jvmTest --tests "io.openeden.archive.DiaryArchiveContractTest"`

Expected: FAIL because archive contracts do not exist.

- [ ] **Step 3: Implement immutable archive types**

```kotlin
data class ArchivedDiaryEntry(
    val archiveEntryId: String,
    val incarnationId: String,
    val sourceDiaryId: String,
    val content: String,
    val originalCreatedAtMs: Long,
    val archivedAtMs: Long,
    val archiveReason: String,
    val contentSha256: String,
)

data class DiaryArchivePage(
    val entries: List<ArchivedDiaryEntry>,
    val before: String?,
    val hasMore: Boolean,
)

interface DiaryArchiveReader {
    suspend fun page(incarnationId: String, limit: Int, before: String?): DiaryArchivePage
}
```

Do not add `MemorySnippet`, vector embeddings, `MemoryRetriever`, or Prompt Builder dependencies.

- [ ] **Step 4: Run and verify GREEN**

Run: `./gradlew :core:jvmTest --tests "io.openeden.archive.DiaryArchiveContractTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/io/openeden/archive core/src/commonTest/kotlin/io/openeden/archive
git commit -m "feat(core): define immutable diary archive"
```

### Task 4: Add Diary Archive Schema and Purge Queries

**Files:**
- Create: `server/src/main/sqldelight/io/openeden/server/db/DiaryArchive.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/6.sqm`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Transcript.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/SessionState.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Relationship.sq`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/IncarnationArchiveSchemaTest.kt`

- [ ] **Step 1: Write a failing migration/restart test**

Create a version-5 database with one active incarnation, transcript rows, RAW and NARRATIVE memories, state, relationships, trace spans, and diary tasks. Open with the version-6 schema and assert all rows are readable before termination.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.IncarnationArchiveSchemaTest"`

Expected: FAIL because migration 6 and archive table do not exist.

- [ ] **Step 3: Add immutable archive and lifecycle status schema**

```sql
CREATE TABLE diary_archive (
    archive_entry_id TEXT NOT NULL PRIMARY KEY,
    incarnation_id TEXT NOT NULL,
    source_diary_id TEXT NOT NULL,
    content TEXT NOT NULL,
    original_created_at_ms INTEGER NOT NULL,
    archived_at_ms INTEGER NOT NULL,
    archive_reason TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    UNIQUE(incarnation_id, source_diary_id)
);

CREATE INDEX diary_archive_page
ON diary_archive(incarnation_id, original_created_at_ms DESC, archive_entry_id DESC);
```

Migration `6.sqm` creates this table/index and adds `status TEXT NOT NULL DEFAULT 'ACTIVE'` to `incarnation_state`.

Add generated queries for selecting NARRATIVE memory rows; inserting archive rows with `INSERT OR IGNORE`; counting source/archive rows; deleting all memory/embedding, transcript, session state, relationship, trace, diary task, and checkpoint rows; and updating incarnation status with an expected-current-status predicate.

- [ ] **Step 4: Run migration test and verify GREEN**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.IncarnationArchiveSchemaTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/sqldelight/io/openeden/server/db server/src/test/kotlin/io/openeden/server/persistence/sqldelight/IncarnationArchiveSchemaTest.kt
git commit -m "feat(server): add diary archive schema"
```

### Task 5: Implement Verified Atomic Archive and Purge

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationLifecycleRepository.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationLifecycleRepositoryTest.kt`

- [ ] **Step 1: Write failing success and rollback tests**

```kotlin
@Test fun `termination leaves only immutable diary archive`() = runTest {
    seedCompleteIncarnation()
    repository.archiveAndPurge("atri-1", TerminationReason("critical", 100L))

    assertEquals(listOf("diary text"), repository.page("atri-1", 50, null).entries.map { it.content })
    assertEquals(0, counts().conversationTurns)
    assertEquals(0, counts().memoryEntries)
    assertEquals(0, counts().sessionStates)
    assertEquals(0, counts().relationships)
    assertEquals(0, counts().traces)
    assertEquals(0, counts().diaryTasks)
}

@Test fun `archive verification failure rolls back every delete`() = runTest {
    seedCompleteIncarnation()
    repository = repository.withHashFailureForTest()
    assertFailsWith<DiaryArchiveVerificationException> {
        repository.archiveAndPurge("atri-1", TerminationReason("critical", 100L))
    }
    assertTrue(counts().conversationTurns > 0)
    assertTrue(counts().memoryEntries > 0)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightIncarnationLifecycleRepositoryTest"`

Expected: FAIL because lifecycle repository does not exist.

- [ ] **Step 3: Implement one SQLite transaction**

Inside `withContext(Dispatchers.IO)`, use the shared SQLDelight `Database` and driver transaction:

```kotlin
database.transaction {
    require(markTerminating(incarnationId) == 1L)
    val diaries = memoryQueries.selectNarratives().executeAsList()
    diaries.forEach { diary ->
        val hash = sha256(diary.content)
        archiveQueries.insertArchive(
            archiveEntryId = "$incarnationId:${diary.id}",
            incarnationId = incarnationId,
            sourceDiaryId = diary.id,
            content = diary.content,
            originalCreatedAtMs = diary.created_at_ms,
            archivedAtMs = reason.requestedAtMs,
            archiveReason = reason.code,
            contentSha256 = hash,
        )
    }
    verifyArchiveCountAndHashes(diaries)
    purgeAllNonArchiveData()
    require(markTerminated(incarnationId) == 1L)
}
```

Hash with JDK SHA-256 over UTF-8 bytes. Any count/hash/status mismatch throws inside the transaction and rolls back archive inserts and deletes. Do not use multiple repository connections for this transaction.

- [ ] **Step 4: Run repository tests and verify GREEN**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightIncarnationLifecycleRepositoryTest"`

Expected: PASS, including rollback and restart reads.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationLifecycleRepository.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationLifecycleRepositoryTest.kt
git commit -m "feat(server): archive diary and purge incarnation"
```

### Task 6: Coordinate Runtime Termination

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/runtime/IncarnationTerminationCoordinator.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Test: `server/src/test/kotlin/io/openeden/server/runtime/IncarnationTerminationCoordinatorTest.kt`

- [ ] **Step 1: Write failing ordering tests**

Use fake jobs, gate, and repository to assert ordering:

```kotlin
assertEquals(
    listOf("gate-terminating", "jobs-cancelled", "turns-drained", "archive-purged", "gate-terminated"),
    events,
)
```

Add a failure test proving repository failure leaves the gate TERMINATING, does not mark TERMINATED, and does not create a new incarnation.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.runtime.IncarnationTerminationCoordinatorTest"`

Expected: FAIL because coordinator does not exist.

- [ ] **Step 3: Implement structured termination**

The coordinator must:

1. call `lifecycleGate.beginTermination()` to reject new turns and await admitted turns;
2. cancel and `joinAll` heartbeat, runtime tick, diary worker, and elapsed diary jobs;
3. call `archiveAndPurge`;
4. call `lifecycleGate.markTerminated()` only after repository success;
5. keep the server process alive for archive reads but return unavailable for chat/runtime endpoints.

Publish the coordinator and archive reader through typed Ktor `AttributeKey`s. Do not create a new incarnation automatically.

Add an explicit `createFreshIncarnation(nowMs)` operation that is legal only after status TERMINATED. It replaces the singleton active ID with a new UUID and ACTIVE status, initializes no transcript or runtime state, and leaves `diary_archive` untouched. It is never called automatically by termination.

- [ ] **Step 4: Run coordinator and scheduler tests**

Run: `./gradlew :server:test --tests "io.openeden.server.runtime.IncarnationTerminationCoordinatorTest" :core:jvmTest --tests "io.openeden.runtime.heartbeat.HeartbeatSchedulerTest"`

Expected: PASS; stopped proactive jobs cannot write after purge.

- [ ] **Step 5: Commit**

```bash
git add server/src/main/kotlin/io/openeden/server/runtime server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/test/kotlin/io/openeden/server/runtime
git commit -m "feat(server): coordinate incarnation termination"
```

### Task 7: Add Owner and Developer Diary Archive APIs

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/api/route/ArchiveAccess.kt`
- Create: `server/src/main/kotlin/io/openeden/server/api/dto/ArchivedDiaryEntryDto.kt`
- Create: `server/src/main/kotlin/io/openeden/server/api/dto/DeveloperArchivedDiaryEntryDto.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/api/route/Routing.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.yaml`
- Modify: `.env.example`
- Test: `server/src/test/kotlin/io/openeden/server/api/route/DiaryArchiveApiTest.kt`

- [ ] **Step 1: Write failing authorization/privacy tests**

```kotlin
@Test fun `owner archive returns diary text but not technical metadata`() = testApplication {
    val response = client.get("/api/v1/archive/atri-1/diary") {
        bearerAuth("owner-token")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val text = response.bodyAsText()
    assertTrue(text.contains("diary text"))
    assertFalse(text.contains("contentSha256"))
    assertFalse(text.contains("internal_logic"))
}

@Test fun `developer archive adds approved diary metadata only`() = testApplication {
    val text = client.get("/api/v1/developer/archive/atri-1/diary") {
        bearerAuth("developer-token")
    }.bodyAsText()
    assertTrue(text.contains("contentSha256"))
    assertFalse(text.contains("userText"))
    assertFalse(text.contains("snapshot_8D"))
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.api.route.DiaryArchiveApiTest"`

Expected: FAIL with 404.

- [ ] **Step 3: Implement separate access gates and DTOs**

`ArchiveAccess` stores distinct owner and developer bearer tokens using constant-time byte comparison. Both endpoints are disabled and return 404 when their token is absent. Owner DTO contains diary content and chronology only. Developer DTO adds source diary ID, archive reason, archive timestamp, and SHA-256; it contains no transcript, RAW memory, vectors, relationship state, prompt, or trace.

Add configuration:

```yaml
openeden:
  archive:
    ownerToken: "$?OPENEDEN_ARCHIVE_OWNER_TOKEN:"
    developerToken: "$?OPENEDEN_ARCHIVE_DEVELOPER_TOKEN:"
```

- [ ] **Step 4: Add developer termination endpoint**

Add `POST /api/v1/developer/incarnation/terminate`, authorized only by developer token. It accepts a bounded ASCII reason code, calls the coordinator once, and returns `202` while termination runs or the final lifecycle status when already complete. It does not accept arbitrary deletion scopes or SQL identifiers.

Add `POST /api/v1/developer/incarnation/create`, using the same developer authorization. It succeeds only when the singleton status is TERMINATED, calls `createFreshIncarnation`, and returns the new public incarnation ID. A second call while ACTIVE returns `409` and never replaces a living incarnation.

- [ ] **Step 5: Run API tests and verify GREEN**

Run: `./gradlew :server:test --tests "io.openeden.server.api.route.DiaryArchiveApiTest" --tests "io.openeden.server.api.route.DiagnosticsApiTest"`

Expected: PASS; diagnostics authorization remains separate.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/io/openeden/server/api server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/main/resources/application.yaml server/src/test/kotlin/io/openeden/server/api/route/DiaryArchiveApiTest.kt .env.example
git commit -m "feat(server): expose authenticated diary archives"
```

### Task 8: Verify No Inheritance and Complete Destructive Lifecycle Tests

**Files:**
- Create: `server/src/test/kotlin/io/openeden/server/runtime/IncarnationTerminationE2ETest.kt`
- Modify only production files required by failures found in this task.

- [ ] **Step 1: Write the end-to-end termination test**

Seed one active incarnation with transcript, RAW/NARRATIVE memory, embeddings, session state, Omega/ShockState, relationships, trace spans, diary tasks, and checkpoints. Terminate it, reopen the database, and assert:

```kotlin
assertEquals(listOf("final diary"), ownerArchive.entries.map { it.content })
assertTrue(transcript.page(50, null).turns.isEmpty())
assertTrue(memory.selectAll().isEmpty())
assertTrue(sessionState.sessionIds().isEmpty())
assertTrue(relationships.readAll().isEmpty())
assertTrue(traces.readAll().isEmpty())
assertTrue(diaryTasks.readAll().isEmpty())
```

Create a new incarnation explicitly, execute its first prompt, and assert the prompt preview contains neither archived diary text nor the old incarnation ID.

- [ ] **Step 2: Run and verify RED before final integration fixes**

Run: `./gradlew :server:test --tests "io.openeden.server.runtime.IncarnationTerminationE2ETest"`

Expected: FAIL until all purge/read boundaries are fully wired.

- [ ] **Step 3: Make minimal integration fixes**

Fix only concrete failures from the E2E test. Do not add archive lookup to `MemoryStore`, `MemoryRetriever`, Prompt Builder, heartbeat, relationship, or VQ-VAE code. Do not retain deleted data in a hidden developer table.

- [ ] **Step 4: Run full verification**

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :core:jvmTest :server:test :test
git diff --check
```

Expected: `BUILD SUCCESSFUL`, zero failures, no whitespace errors.

- [ ] **Step 5: Audit invariants from the final diff**

Confirm:

- Persona selection and persona YAML behavior are unchanged.
- VQ-VAE and heuristic fallback still run before Prompt Builder.
- Derived D remains runtime-only and absent from stored vectors.
- Existing per-session mutexes still protect vector/evolution writes.
- Lifecycle and database waits suspend rather than block Ktor/inference threads.
- Archive classes do not implement or depend on retrieval interfaces.
- No deleted transcript/runtime field appears in owner or developer archive DTOs.

- [ ] **Step 6: Commit the end-to-end test**

```bash
git add server/src/test/kotlin/io/openeden/server/runtime/IncarnationTerminationE2ETest.kt
git commit -m "test(server): verify diary-only termination"
```

Expected: Step 3 required no untested production edits. If it exposed a production defect, return to the owning task, add a focused failing test, and commit that correction before this test-only commit.
