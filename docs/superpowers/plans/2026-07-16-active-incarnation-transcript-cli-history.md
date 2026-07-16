# Active Incarnation Transcript and CLI History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the active ATRI incarnation's public turns on the server, restore the latest 50 turns in the CLI, page older history, and fix inline status/history rendering without changing persona or affect behavior.

**Architecture:** Add focused transcript contracts in `core`, implement them with SQLDelight beside durable session state, and publish a public turn through the same per-session commit boundary as vector state. Expose opaque cursor paging through Ktor, hydrate CLI state before input starts, and make JLine the sole owner of interactive input/output while redirected mode remains deterministic UTF-8.

**Tech Stack:** Kotlin 2.x, coroutines and Flow, Ktor client/server, SQLDelight/SQLite, JLine, kotlin.test, Ktor `testApplication`, Windows ConPTY tests.

---

## File Map

**Core transcript domain**

- Create `core/src/commonMain/kotlin/io/openeden/transcript/ActiveIncarnation.kt`: lifecycle identity and status.
- Create `core/src/commonMain/kotlin/io/openeden/transcript/ConversationTurn.kt`: public completed-turn record.
- Create `core/src/commonMain/kotlin/io/openeden/transcript/ConversationHistoryPage.kt`: stable page contract.
- Create `core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt`: append/page/incarnation interface.
- Create `core/src/commonMain/kotlin/io/openeden/transcript/AtomicTurnCommitStore.kt`: atomically persist runtime state and one public turn.
- Create `core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt`: test/development implementation.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/DevelopmentMessageRequest.kt`: carry stable client turn ID.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`: publish only validated user turns.

**Server persistence and API**

- Create `server/src/main/sqldelight/io/openeden/server/db/Transcript.sq`: active incarnation and public transcript schema/queries.
- Create `server/src/main/sqldelight/io/openeden/server/db/5.sqm`: migration for existing databases.
- Create `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt`: IO-dispatched SQLDelight adapter.
- Modify `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightSessionStateStore.kt`: implement atomic state plus transcript commit on its existing driver.
- Create `server/src/main/kotlin/io/openeden/server/api/dto/ConversationTurnDto.kt`: safe public turn.
- Create `server/src/main/kotlin/io/openeden/server/api/dto/ConversationHistoryPageDto.kt`: page response.
- Modify `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`: create, publish, and close transcript store.
- Modify `server/src/main/kotlin/io/openeden/server/api/route/Routing.kt`: pass `clientRequestId` into pipeline and add history route.

**CLI client and state**

- Create `src/main/kotlin/io/openeden/client/ConversationTurn.kt`: client public turn.
- Create `src/main/kotlin/io/openeden/client/ConversationHistoryPage.kt`: client page.
- Modify `src/main/kotlin/io/openeden/client/OpenEdenServerApi.kt`: add `history`.
- Modify `src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt`: decode history API.
- Modify `src/main/kotlin/io/openeden/cli/state/CliEvent.kt`: history load events.
- Modify `src/main/kotlin/io/openeden/cli/state/CliUiState.kt`: cursor/loading/exhausted state.
- Modify `src/main/kotlin/io/openeden/cli/state/CliReducer.kt`: hydrate/prepend/deduplicate turns.
- Modify `src/main/kotlin/io/openeden/cli/command/CliCommand.kt`: add `HistoryOlder`.
- Modify `src/main/kotlin/io/openeden/cli/command/CliCommandParser.kt`: parse and complete `/history older`.
- Modify `src/main/kotlin/io/openeden/cli/application/CliSessionController.kt`: startup hydration and serialized paging.
- Modify `src/main/kotlin/io/openeden/cli/application/OpenEdenCli.kt`: hydrate before terminal event collection.

**Terminal rendering and ownership**

- Modify `src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt`: selected B layout and committed-scrollback ownership.
- Modify `src/main/kotlin/io/openeden/cli/render/JLineInlineActiveSink.kt`: hide only transient rows.
- Modify `src/main/kotlin/io/openeden/cli/render/FullScreenCliRenderer.kt`: newest viewport and stable prepending hook.
- Modify `src/main/kotlin/io/openeden/cli/Main.kt`: branch interactive/plain paths before wrapping `System` streams.
- Modify `src/main/kotlin/io/openeden/cli/terminal/CliTextStreams.kt`: UTF-8 redirected streams only.
- Delete `src/main/kotlin/io/openeden/cli/terminal/TerminalEncodingProfile.kt`: remove legacy per-stream encoding overrides.

### Task 1: Define Transcript Contracts

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/ActiveIncarnation.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/ConversationTurn.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/ConversationHistoryPage.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/AtomicTurnCommitStore.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/transcript/InMemoryTranscriptStoreTest.kt`

- [ ] **Step 1: Write failing contract tests**

```kotlin
class InMemoryTranscriptStoreTest {
    @Test fun `append is idempotent and latest page is chronological`() = runTest {
        val store = InMemoryTranscriptStore(activeIncarnationId = "atri-1")
        repeat(55) { index -> store.append(turn(index)) }
        store.append(turn(54))

        val page = store.page(limit = 50, before = null)

        assertEquals((5 until 55).map { "turn-$it" }, page.turns.map { it.turnId })
        assertTrue(page.hasMore)
        assertNotNull(page.before)
    }

    @Test fun `older cursor never crosses incarnation`() = runTest {
        val store = InMemoryTranscriptStore(activeIncarnationId = "atri-1")
        store.append(turn(1))
        assertFailsWith<InvalidHistoryCursorException> {
            store.page(50, HistoryCursor("atri-other", 1L, "turn-1"))
        }
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :core:jvmTest --tests "io.openeden.transcript.InMemoryTranscriptStoreTest"`

Expected: FAIL because transcript types do not exist.

- [ ] **Step 3: Implement focused transcript types**

```kotlin
data class ActiveIncarnation(val id: String, val createdAtMs: Long)

data class ConversationTurn(
    val turnId: String,
    val incarnationId: String,
    val sessionId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)

data class HistoryCursor(
    val incarnationId: String,
    val completedAtMs: Long,
    val turnId: String,
)

data class ConversationHistoryPage(
    val turns: List<ConversationTurn>,
    val before: HistoryCursor?,
    val hasMore: Boolean,
)

interface TranscriptStore {
    suspend fun activeIncarnation(): ActiveIncarnation
    suspend fun append(turn: ConversationTurn)
    suspend fun page(limit: Int, before: HistoryCursor?): ConversationHistoryPage
}

interface AtomicTurnCommitStore {
    suspend fun writeCommittedTurn(state: SessionState, turn: ConversationTurn)
}
```

Implement `InMemoryTranscriptStore` with a `Mutex`, `putIfAbsent(turnId)`, active-incarnation checks, descending selection followed by chronological reversal, and a hard `limit.coerceIn(1, 50)` cap.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `./gradlew :core:jvmTest --tests "io.openeden.transcript.InMemoryTranscriptStoreTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/commonMain/kotlin/io/openeden/transcript core/src/commonTest/kotlin/io/openeden/transcript
git commit -m "feat(core): define active transcript contracts"
```

### Task 2: Add SQLDelight Transcript Persistence

**Files:**
- Create: `server/src/main/sqldelight/io/openeden/server/db/Transcript.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/5.sqm`
- Create: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStoreTest.kt`

- [ ] **Step 1: Write restart, idempotency, and paging tests**

```kotlin
@Test fun `active incarnation and turns survive restart`() = runTest {
    SqlDelightTranscriptStore.open(dbPath).use { store ->
        val incarnation = store.activeIncarnation()
        store.append(turn("t1", incarnation.id, 1L))
    }
    SqlDelightTranscriptStore.open(dbPath).use { reopened ->
        assertEquals(listOf("t1"), reopened.page(50, null).turns.map { it.turnId })
    }
}

@Test fun `same turn id is inserted once`() = runTest {
    SqlDelightTranscriptStore.open(dbPath).use { store ->
        val turn = turn("same", store.activeIncarnation().id, 1L)
        store.append(turn)
        store.append(turn)
        assertEquals(1, store.page(50, null).turns.size)
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStoreTest"`

Expected: FAIL because schema and adapter do not exist.

- [ ] **Step 3: Add schema and migration**

`Transcript.sq` must contain:

```sql
CREATE TABLE incarnation_state (
    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
    active_incarnation_id TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE conversation_turns (
    turn_id TEXT NOT NULL PRIMARY KEY,
    incarnation_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    platform TEXT NOT NULL,
    scope_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    user_text TEXT NOT NULL,
    assistant_text TEXT NOT NULL,
    completed_at_ms INTEGER NOT NULL
);

CREATE INDEX conversation_turns_page
ON conversation_turns(incarnation_id, completed_at_ms DESC, turn_id DESC);

insertIncarnationIfAbsent:
INSERT OR IGNORE INTO incarnation_state(singleton_id, active_incarnation_id, created_at_ms)
VALUES (1, ?, ?);

selectActiveIncarnation:
SELECT active_incarnation_id, created_at_ms FROM incarnation_state WHERE singleton_id = 1;

insertTurnIfAbsent:
INSERT OR IGNORE INTO conversation_turns(
    turn_id, incarnation_id, session_id, platform, scope_id, user_id,
    user_text, assistant_text, completed_at_ms
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

selectTurnPage:
SELECT * FROM conversation_turns
WHERE incarnation_id = ?
  AND (? IS NULL OR completed_at_ms < ? OR (completed_at_ms = ? AND turn_id < ?))
ORDER BY completed_at_ms DESC, turn_id DESC
LIMIT ?;
```

Copy the two `CREATE TABLE` statements and index into `5.sqm` for migration from schema version 4.

- [ ] **Step 4: Implement the IO-dispatched adapter**

`SqlDelightTranscriptStore.open(path)` must use `JdbcSqliteDriver`, create parent directories, call `Database.Schema.create` for a new database, initialize exactly one UUID incarnation row, and execute every query inside `withContext(Dispatchers.IO)`. Fetch `limit + 1` rows to compute `hasMore`, reverse selected results for display order, and encode no cursor text in this layer.

- [ ] **Step 5: Run tests and verify GREEN**

Run: `./gradlew :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStoreTest"`

Expected: PASS, including restart and duplicate append.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/sqldelight/io/openeden/server/db/Transcript.sq server/src/main/sqldelight/io/openeden/server/db/5.sqm server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStoreTest.kt
git commit -m "feat(server): persist active incarnation transcript"
```

### Task 3: Atomically Commit Runtime State and Transcript

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/DevelopmentMessageRequest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/session/MutableSessionStateStore.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightSessionStateStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightAtomicTurnCommitTest.kt`

- [ ] **Step 1: Write failing pipeline tests**

```kotlin
@Test fun `validated user turn publishes one public transcript record`() = runTest {
    val transcripts = InMemoryTranscriptStore("atri-1")
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = testPersona(),
        llmClient = ValidLlmClient("public"),
        transcriptStore = transcripts,
    )

    pipeline.handle(DevelopmentMessageRequest(
        turnId = "client-1", platform = "CLI", scopeId = "local",
        userId = "local", text = "hello",
    ))

    assertEquals("public", transcripts.page(50, null).turns.single().assistantText)
}

@Test fun `invalid or heartbeat turn does not enter public transcript`() = runTest {
    // Run one schema-invalid USER turn and one HEARTBEAT turn.
    assertTrue(transcripts.page(50, null).turns.isEmpty())
}

@Test fun `state and transcript rollback together`() = runTest {
    val store = SqlDelightSessionStateStore.open(dbPath, PersonaMode.GROWTH, PersonaStartSubState.PRE_COMMAND)
    testDriver.execute(null, """
        CREATE TRIGGER reject_test_turn BEFORE INSERT ON conversation_turns
        WHEN NEW.turn_id = 'reject-me' BEGIN SELECT RAISE(ABORT, 'test failure'); END
    """.trimIndent(), 0)
    assertFailsWith<Throwable> { writer.commitTurnLocked("CLI:local", snapshot, delta, null, 1L, publicTurn) }
    assertEquals(0L, store.read("CLI:local").evolutionIndex)
    assertTrue(transcript.page(50, null).turns.isEmpty())
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineTranscriptTest" :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightAtomicTurnCommitTest"`

Expected: FAIL because the request and pipeline lack transcript integration.

- [ ] **Step 3: Carry a stable turn ID into the existing commit boundary**

Add `turnId: String` to `DevelopmentMessageRequest`. Before `VectorWriteService.commitTurnLocked`, build the public turn only for a validated USER request:

```kotlin
val publicTurn = if (request.source == TurnSource.USER && validation.output != null) {
    ConversationTurn(
        turnId = request.turnId,
        incarnationId = transcriptStore.activeIncarnation().id,
        sessionId = sessionId,
        platform = request.platform,
        scopeId = request.scopeId,
        userId = request.userId,
        userText = request.text,
        assistantText = validation.output.response,
        completedAtMs = nowMs(),
    )
} else null
```

Pass `publicTurn` into `commitTurnLocked`. Keep this in the existing `NonCancellable` region and inside the per-session turn gate. Do not move quantization, inference, or vector math out of `InferenceDispatcher`. Do not publish heartbeat turns.

- [ ] **Step 4: Implement an atomic write abstraction**

`VectorWriteService` must compute `updated` exactly as it does now, then choose one write:

```kotlin
if (turn != null) {
    val atomic = store as? AtomicTurnCommitStore
        ?: error("Public turns require an atomic turn commit store")
    atomic.writeCommittedTurn(updated, turn)
} else {
    store.write(updated)
}
```

`MutableSessionStateStore` implements `AtomicTurnCommitStore` for tests by updating its state and in-memory transcript under one coroutine `Mutex`.

`SqlDelightSessionStateStore.writeCommittedTurn` uses its existing `Database` and driver in one `withContext(Dispatchers.IO)` transaction:

```kotlin
database.transaction {
    writeStateQueries(state)
    transcriptQueries.insertTurnIfAbsent(
        turn.turnId, turn.incarnationId, turn.sessionId, turn.platform,
        turn.scopeId, turn.userId, turn.userText, turn.assistantText, turn.completedAtMs,
    )
    check(transcriptQueries.selectByTurnId(turn.turnId).executeAsOne().matches(turn))
}
```

The transcript insert must reject a duplicate `turnId` whose stored payload differs. Any insert/check failure rolls back the state update, including `evolution_index`, vector, Omega, and ShockState.

- [ ] **Step 5: Run focused and invariant tests**

Run: `./gradlew :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineTranscriptTest" --tests "io.openeden.runtime.pipeline.MessagePipelineStreamingTest" --tests "io.openeden.runtime.session.TurnCoordinatorConcurrencyTest" :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightAtomicTurnCommitTest"`

Expected: PASS; cancellation still writes neither runtime state nor transcript.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/io/openeden/runtime/pipeline core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt core/src/commonMain/kotlin/io/openeden/runtime/session/MutableSessionStateStore.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightSessionStateStore.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightAtomicTurnCommitTest.kt
git commit -m "feat(runtime): atomically commit public turns"
```

### Task 4: Add the Safe Cursor History API

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/api/dto/ConversationTurnDto.kt`
- Create: `server/src/main/kotlin/io/openeden/server/api/dto/ConversationHistoryPageDto.kt`
- Create: `server/src/main/kotlin/io/openeden/server/api/route/HistoryCursorCodec.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/api/route/Routing.kt`
- Test: `server/src/test/kotlin/io/openeden/server/api/route/ConversationHistoryApiTest.kt`

- [ ] **Step 1: Write failing API privacy and pagination tests**

```kotlin
@Test fun `history returns chronological public turns and opaque older cursor`() = testApplication {
    val response = client.get("/api/v1/history?limit=2")
    assertEquals(HttpStatusCode.OK, response.status)
    val page = response.body<ConversationHistoryPageDto>()
    assertEquals(listOf("t2", "t3"), page.turns.map { it.turnId })
    assertNotNull(page.before)
    assertTrue(page.hasMore)
    assertFalse(response.bodyAsText().contains("internal_logic"))
    assertFalse(response.bodyAsText().contains("vector"))
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :server:test --tests "io.openeden.server.api.route.ConversationHistoryApiTest"`

Expected: FAIL with 404.

- [ ] **Step 3: Implement DTOs and opaque cursor codec**

Use URL-safe Base64 without padding over JSON containing only `incarnationId`, `completedAtMs`, and `turnId`. Decode failures throw a typed `InvalidHistoryCursorException`; route maps it to `400` with a generic message.

```kotlin
@Serializable
data class ConversationTurnDto(
    val turnId: String,
    val platform: String,
    val scopeId: String,
    val userId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)

@Serializable
data class ConversationHistoryPageDto(
    val turns: List<ConversationTurnDto>,
    val before: String?,
    val hasMore: Boolean,
)
```

- [ ] **Step 4: Wire runtime and route**

Publish `TranscriptStoreKey`, put the SQLDelight store in application attributes, inject it into `DevelopmentMessagePipeline.create`, close it during `ApplicationStopping`, and add `GET /api/v1/history?limit=50&before=...`. Clamp limit to `1..50`; never accept an incarnation ID parameter.

Pass `ChatStreamRequestDto.clientRequestId` as `DevelopmentMessageRequest.turnId` in the streaming route. For buffered chat, generate the turn ID once at route entry and pass it through.

- [ ] **Step 5: Run API tests**

Run: `./gradlew :server:test --tests "io.openeden.server.api.route.ConversationHistoryApiTest" --tests "io.openeden.server.api.route.ServerApiTest"`

Expected: PASS; existing chat endpoints remain compatible.

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/io/openeden/server server/src/test/kotlin/io/openeden/server/api/route/ConversationHistoryApiTest.kt
git commit -m "feat(server): expose active conversation history"
```

### Task 5: Add Client History Decoding

**Files:**
- Create: `src/main/kotlin/io/openeden/client/ConversationTurn.kt`
- Create: `src/main/kotlin/io/openeden/client/ConversationHistoryPage.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerApi.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt`
- Test: `src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt`

- [ ] **Step 1: Add a failing client request test**

```kotlin
@Test fun `history encodes cursor and decodes public page`() = runTest {
    val page = client.history(limit = 50, before = "opaque+/=")
    assertEquals("t1", page.turns.single().turnId)
    assertEquals("older", page.before)
    assertTrue(requests.single().contains("before=opaque%2B%2F%3D"))
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :test --tests "io.openeden.client.OpenEdenServerClientTest"`

Expected: FAIL because `history` does not exist.

- [ ] **Step 3: Implement the client contract**

```kotlin
interface OpenEdenServerApi {
    suspend fun history(limit: Int = 50, before: String? = null): ConversationHistoryPage
}

override suspend fun history(limit: Int, before: String?): ConversationHistoryPage {
    val parameters = Parameters.build {
        append("limit", limit.coerceIn(1, 50).toString())
        before?.let { append("before", it) }
    }
    return httpClient.get("$baseUrl/api/v1/history?${parameters.formUrlEncode()}").decodeSuccess()
}
```

- [ ] **Step 4: Run and verify GREEN**

Run: `./gradlew :test --tests "io.openeden.client.OpenEdenServerClientTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/openeden/client src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt
git commit -m "feat(cli): add conversation history client"
```

### Task 6: Hydrate and Page Immutable CLI State

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/state/CliEvent.kt`
- Modify: `src/main/kotlin/io/openeden/cli/state/CliUiState.kt`
- Modify: `src/main/kotlin/io/openeden/cli/state/CliReducer.kt`
- Modify: `src/main/kotlin/io/openeden/cli/application/CliSessionController.kt`
- Test: `src/test/kotlin/io/openeden/cli/state/CliReducerTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/application/CliSessionControllerTest.kt`

- [ ] **Step 1: Write failing hydration and deduplication tests**

```kotlin
@Test fun `initial history becomes stable user assistant pairs`() {
    val state = CliUiState.initial("local").reduce(CliEvent.HistoryLoaded(page("t1", "t2"), initial = true))
    assertEquals(listOf("t1:user", "t1:assistant", "t2:user", "t2:assistant"), state.messages.map { it.id })
    assertEquals("older", state.historyBefore)
}

@Test fun `older page prepends without duplicates`() {
    val once = initial.reduce(CliEvent.HistoryLoaded(page("t2", "t3"), true))
    val twice = once.reduce(CliEvent.HistoryLoaded(page("t1", "t2"), false))
    assertEquals(listOf("t1:user", "t1:assistant", "t2:user", "t2:assistant", "t3:user", "t3:assistant"), twice.messages.map { it.id })
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./gradlew :test --tests "io.openeden.cli.state.CliReducerTest" --tests "io.openeden.cli.application.CliSessionControllerTest"`

Expected: FAIL because history state/events do not exist.

- [ ] **Step 3: Add paging state and reducer events**

Add to `CliUiState`:

```kotlin
val historyBefore: String? = null,
val historyLoading: Boolean = false,
val historyExhausted: Boolean = false,
```

Add `HistoryLoading`, `HistoryLoaded(page, initial)`, and `HistoryLoadFailed(message)` events. Map every public turn to two `COMPLETE` messages with IDs `${turnId}:user` and `${turnId}:assistant`; prepend older messages and deduplicate by ID while preserving order.

- [ ] **Step 4: Add serialized controller methods**

```kotlin
suspend fun initializeHistory() = loadHistory(initial = true)

private suspend fun loadHistory(initial: Boolean) {
    if (state.historyLoading || (!initial && state.historyExhausted)) return
    dispatch(CliEvent.HistoryLoading)
    runCatching { api.history(50, if (initial) null else state.historyBefore) }
        .onSuccess { dispatch(CliEvent.HistoryLoaded(it, initial)) }
        .onFailure { dispatch(CliEvent.HistoryLoadFailed("Conversation history unavailable.")) }
}
```

Track the paging job in `activeCommand`; do not block the terminal reader coroutine.

- [ ] **Step 5: Run and verify GREEN**

Run: `./gradlew :test --tests "io.openeden.cli.state.CliReducerTest" --tests "io.openeden.cli.application.CliSessionControllerTest"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/openeden/cli/state src/main/kotlin/io/openeden/cli/application/CliSessionController.kt src/test/kotlin/io/openeden/cli/state src/test/kotlin/io/openeden/cli/application/CliSessionControllerTest.kt
git commit -m "feat(cli): hydrate active conversation history"
```

### Task 7: Add `/history older`

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/command/CliCommand.kt`
- Modify: `src/main/kotlin/io/openeden/cli/command/CliCommandParser.kt`
- Modify: `src/main/kotlin/io/openeden/cli/application/CliSessionController.kt`
- Test: `src/test/kotlin/io/openeden/cli/command/CliCommandParserTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/application/CliSessionControllerTest.kt`

- [ ] **Step 1: Write failing parser and command tests**

```kotlin
@Test fun `history older parses and completes`() {
    assertEquals(CliCommand.HistoryOlder, parser.parse("/history older"))
    assertEquals(listOf("older"), parser.complete("/history o").map { it.value })
}

@Test fun `history older requests one page while load is active`() = runTest {
    controller.accept(Submit("/history older"))
    controller.accept(Submit("/history older"))
    controller.drain()
    assertEquals(1, api.historyCalls)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :test --tests "io.openeden.cli.command.CliCommandParserTest" --tests "io.openeden.cli.application.CliSessionControllerTest"`

Expected: FAIL because the command is unknown.

- [ ] **Step 3: Implement parser, completion, help text, and controller dispatch**

Add `CliCommand.HistoryOlder`, require exactly `/history older`, add root and argument candidates, and launch `loadHistory(initial = false)` from `handleCommand`. When `historyExhausted`, emit notice `No older conversation history.` without calling the API.

- [ ] **Step 4: Run and verify GREEN**

Run: `./gradlew :test --tests "io.openeden.cli.command.CliCommandParserTest" --tests "io.openeden.cli.application.CliSessionControllerTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/openeden/cli/command src/main/kotlin/io/openeden/cli/application/CliSessionController.kt src/test/kotlin/io/openeden/cli/command src/test/kotlin/io/openeden/cli/application/CliSessionControllerTest.kt
git commit -m "feat(cli): page older conversation history"
```

### Task 8: Fix Inline Status and Scrollback Ownership

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt`
- Modify: `src/main/kotlin/io/openeden/cli/render/JLineInlineActiveSink.kt`
- Test: `src/test/kotlin/io/openeden/cli/render/InlineCliRendererTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt`

- [ ] **Step 1: Write failing row and lifecycle tests**

```kotlin
@Test fun `status and ATRI start at column zero on separate rows`() {
    val rows = renderer.activeRows(streamingState("你好", "generating"), 80)
    assertEquals("[status] generating", rows[0])
    assertEquals("ATRI: 你好", rows[1])
}

@Test fun `wrapped assistant label appears once`() {
    val rows = renderer.rows(completedAssistant("123456789"), 10)
    assertTrue(rows[0].startsWith("ATRI: "))
    assertFalse(rows.drop(1).any { it.startsWith("ATRI:") })
}
```

Extend the PTY assertion so the first completed response remains present after the second submission and no output line begins with ` " [status]"` or `" ATRI:"`.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :test --tests "io.openeden.cli.render.InlineCliRendererTest" --tests "io.openeden.cli.terminal.CliPseudoTerminalTest"`

Expected: FAIL on row order, leading spaces, and retained scrollback assertion.

- [ ] **Step 3: Implement selected B layout**

Build assistant rows with `ATRI: ` on the first rendered line and six spaces on continuation lines. Build active rows explicitly in this order:

```kotlin
buildList {
    state.stage?.let { add("[status] $it") }
    state.messages.filter { it.status == STREAMING }.forEach { addAll(messageRows(it, width)) }
    state.notice?.let { add("[notice] $it") }
}
```

Commit completed user/assistant blocks with `printAbove` before calling `active.clear()`. `JLineInlineActiveSink.clear()` must hide only the current `Status` rows and never close/recreate `Status` during a turn.

- [ ] **Step 4: Run focused renderer and PTY tests**

Run: `./gradlew :test --tests "io.openeden.cli.render.InlineCliRendererTest" --tests "io.openeden.cli.terminal.CliPseudoTerminalTest"`

Expected: PASS; transcript contains both turns and no compensating leading spaces.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt src/main/kotlin/io/openeden/cli/render/JLineInlineActiveSink.kt src/test/kotlin/io/openeden/cli/render/InlineCliRendererTest.kt src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt
git commit -m "fix(cli): preserve inline history and align status"
```

### Task 9: Make JLine the Only Interactive Stream Owner

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/Main.kt`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/CliTextStreams.kt`
- Delete: `src/main/kotlin/io/openeden/cli/terminal/TerminalEncodingProfile.kt`
- Delete: `src/test/kotlin/io/openeden/cli/terminal/TerminalEncodingProfileTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/terminal/CliTextStreamsTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/terminal/WindowsTerminalInputE2ETest.kt`

- [ ] **Step 1: Write failing ownership tests**

Add a `Main` seam or small `CliIoModeSelector` test proving interactive no-argument execution does not create redirected readers/writers, while one-shot and redirected execution does. Update stream tests to assert fixed UTF-8 and BOM stripping without environment overrides.

```kotlin
@Test fun `redirected streams are always UTF-8`() {
    val output = ByteArrayOutputStream()
    val streams = CliTextStreams.create(ByteArrayInputStream("你好".toByteArray()), output, output)
    assertEquals("你好", streams.reader.readText())
}
```

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :test --tests "io.openeden.cli.terminal.CliTextStreamsTest" --tests "io.openeden.cli.terminal.WindowsTerminalInputE2ETest"`

Expected: FAIL because `CliTextStreams.create` still requires a profile and `Main` constructs it unconditionally.

- [ ] **Step 3: Remove the environment encoding workaround**

Change `CliTextStreams.create(input, output, error)` to hard-code `UTF_8` for redirected byte streams and retain `Utf8BomStrippingInputStream`. Delete `TerminalEncodingProfile` and the three `OPENEDEN_*_ENCODING` configuration references from README and `.env.example` if present.

Restructure `Main` so interactive no-argument execution creates `JLineTerminalSession` first and routes all interactive output through `terminal.writer()`. Only non-interactive/one-shot mode constructs `CliTextStreams`. Do not call `chcp`, change PowerShell settings, replace global `System` streams, or use JVM default charset.

- [ ] **Step 4: Run Windows and redirected tests**

Run: `./gradlew :test --tests "io.openeden.cli.terminal.CliTextStreamsTest" --tests "io.openeden.cli.terminal.WindowsTerminalInputE2ETest" --tests "io.openeden.cli.terminal.CliPseudoTerminalTest"`

Expected: PASS under the existing code page without invoking `chcp`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/openeden/cli/Main.kt src/main/kotlin/io/openeden/cli/terminal src/test/kotlin/io/openeden/cli/terminal README.md README.zh-CN.md .env.example
git commit -m "fix(cli): isolate interactive and redirected IO"
```

### Task 10: Hydrate Before Input and Add Full-Screen Paging Hook

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/application/OpenEdenCli.kt`
- Modify: `src/main/kotlin/io/openeden/cli/render/FullScreenCliRenderer.kt`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/CliTerminalEvent.kt`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/JLineTerminalSession.kt`
- Test: `src/test/kotlin/io/openeden/cli/application/OpenEdenCliTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/render/FullScreenCliRendererTest.kt`
- Test: `src/test/kotlin/io/openeden/cli/terminal/JLineTerminalSessionTest.kt`

- [ ] **Step 1: Write failing startup and PageUp tests**

Assert `api.history` is called before the first `readLine`, restored messages are rendered once, and PageUp at full-screen top emits `CliTerminalEvent.LoadOlderHistory`. Assert prepending rows preserves the previously visible top message.

- [ ] **Step 2: Run and verify RED**

Run: `./gradlew :test --tests "io.openeden.cli.application.OpenEdenCliTest" --tests "io.openeden.cli.render.FullScreenCliRendererTest" --tests "io.openeden.cli.terminal.JLineTerminalSessionTest"`

Expected: FAIL because startup hydration and PageUp events do not exist.

- [ ] **Step 3: Implement startup order and deterministic PageUp fallback**

Call `controller.initializeHistory()` before `controller.run(session.events())`. Add `LoadOlderHistory` terminal event and bind the terminal's `key_ppage` capability to a JLine widget. Dispatch it only in full-screen mode; controller launches the same serialized older-page loader used by `/history older`.

Add `scrollOffset` to full-screen renderer state. Render the newest rows by default with `takeLast(viewportHeight)`; when older rows are prepended, increase offset by the inserted row count to retain the visual anchor.

- [ ] **Step 4: Run and verify GREEN**

Run: `./gradlew :test --tests "io.openeden.cli.application.OpenEdenCliTest" --tests "io.openeden.cli.render.FullScreenCliRendererTest" --tests "io.openeden.cli.terminal.JLineTerminalSessionTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/openeden/cli/application/OpenEdenCli.kt src/main/kotlin/io/openeden/cli/render/FullScreenCliRenderer.kt src/main/kotlin/io/openeden/cli/terminal src/test/kotlin/io/openeden/cli
git commit -m "feat(cli): restore and page visible conversation"
```

### Task 11: Cross-Module Verification

**Files:**
- Modify only files required by test failures introduced by Tasks 1-10.

- [ ] **Step 1: Run formatting and diff checks**

Run: `git diff --check`

Expected: no whitespace errors.

- [ ] **Step 2: Run the complete relevant test matrix**

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :core:jvmTest :server:test :test
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 3: Run the real Windows ConPTY CLI scenario**

Run the existing `CliPseudoTerminalTest` and `WindowsTerminalInputE2ETest` under a non-65001 code page without changing `chcp`. Send two Chinese turns, verify both completed turns remain visible, restart the CLI, and verify the last 50 turns restore from the server.

Expected: no replacement characters, no deleted first turn, `[status]` and `ATRI:` at column zero, and restored history before the prompt.

- [ ] **Step 4: Review architectural invariants**

Confirm from the diff that Persona YAML, VQ-VAE quantization order, derived-D storage, pre-tick base application, and per-session mutex ownership are unchanged. Confirm transcript DTOs contain public text only and all SQL I/O uses `Dispatchers.IO`.

- [ ] **Step 5: Keep verification clean**

Expected: verification creates no new diff. If it exposes a defect, return to the task that owns that file, add a failing regression test there, and repeat that task's RED/GREEN/commit steps before rerunning this matrix.
