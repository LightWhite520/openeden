# Unified Companion Quality And Prompt Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved stranger-to-lover ATRI experience, single-incarnation continuity, authoritative relationship and memory state, append-oriented prompt history, relay-compatible prompt caching, temporal determinism, and anti-saturating 8D mechanics without removing any existing context capability.

**Architecture:** Move physiological ownership from `platform:scope_id` to the already-existing active `incarnation_id`, while keeping transcripts and cache history scoped to each conversation. Persist relationship facts as an append-only event ledger keyed by canonical subject, render persona behavior exclusively from YAML, make transcript history the immutable prompt prefix, and keep current Bio/relationship/RAG/time as a dynamic suffix before the user message. Introduce observability and deterministic evaluation first, then migrate state and behavior in the dependency order defined by the spec.

**Tech Stack:** Kotlin 2.x, Kotlin Multiplatform/JVM, Ktor, Coroutines/Flow, kotlinx.serialization, kotlinx-datetime, SQLDelight/SQLite, DJL, Qdrant, PowerShell test tooling, OpenAI Responses-compatible API.

**Spec:** `docs/superpowers/specs/2026-08-25-unified-companion-quality-and-prompt-cache-optimization-design.md`

## Global Constraints

- Response quality is a hard constraint; retain VQ-VAE, all eight Bio dimensions, Codebook semantics, derived D, Omega, ShockState, relationship state, RAG capacity, and recent context.
- Persona-as-Data: persona behavior, romantic expression, anti-service language, and few-shot examples live only in `persona/*.yaml`; Kotlin contains mechanics and schemas only.
- Stored Bio dimensions remain exactly `[L, P, E, S, tau, V, M, F]`; D remains derived and never enters persistence or training data.
- All inference, retrieval, persistence, compaction, mapping, and capability probes remain suspend-based and non-blocking; DJL and vector transformations run through `InferenceExecutor`.
- Prompt caching is measured only from provider-reported usage. Missing usage is `UNOBSERVABLE`, never a hit or miss.
- History prefix optimization must not cache stale current state and must not delete useful context.
- Use PowerShell commands locally. Do not modify Illusion Server or its port `8080`.
- Every commit uses Conventional Commits and includes only the files for its task.

---

### Task 1: Align The Engineering Contract With Single-Incarnation Bio

**Files:**
- Modify: `AGENTS.md`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/state/StateOwnership.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/RuntimeInvariantConstants.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt`

**Interfaces:**
- Consumes: approved global-incarnation decision from the spec.
- Produces: one authoritative rule: Bio state is keyed by `incarnation_id`; conversation state is keyed by `platform:scope_id`.

- [ ] **Step 1: Add a failing invariant test for identity ownership**

```kotlin
@Test
fun `bio ownership is incarnation global while transcript ownership is conversation scoped`() {
    assertEquals(StateOwnership.INCARNATION, RuntimeInvariantConstants.bioStateOwnership)
    assertEquals(StateOwnership.CONVERSATION, RuntimeInvariantConstants.transcriptOwnership)
}
```

- [ ] **Step 2: Run the focused test and confirm the missing contract**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.state.RuntimeInvariantTest"`

Expected: FAIL because `StateOwnership` and the ownership constants do not exist.

- [ ] **Step 3: Add the mechanical ownership contract and update AGENTS.md**

```kotlin
enum class StateOwnership { INCARNATION, CONVERSATION }

object RuntimeInvariantConstants {
    val bioStateOwnership = StateOwnership.INCARNATION
    val transcriptOwnership = StateOwnership.CONVERSATION
}
```

Rewrite AGENTS.md sections 1.1, 9, 10, 13, and 14.2 so the same incarnation owns 8D, Omega, ShockState, centroid, persona selection, and `evolution_index`; conversation scopes retain transcript, delivery, recent history, and cache epoch. State that one incarnation mutex serializes all Bio writes across scopes.

- [ ] **Step 4: Run the invariant test**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.state.RuntimeInvariantTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add AGENTS.md core/src/commonMain/kotlin/io/openeden/runtime/state/StateOwnership.kt core/src/commonMain/kotlin/io/openeden/runtime/state/RuntimeInvariantConstants.kt core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt
git commit -m "docs(runtime): define global incarnation state ownership"
```

### Task 2: Establish Prompt And Cache Observability

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/hash/Sha256.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptManifestEntry.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptManifest.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/CacheMetricAvailability.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryContentFingerprint.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/llm/LlmCacheMetrics.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/llm/LlmCacheMetricsTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`

**Interfaces:**
- Consumes: current `BuiltPrompt` four-field request shape and `TraceStore`.
- Produces: `PromptManifest.from(BuiltPrompt)` and explicit cache metric availability.

- [ ] **Step 1: Write failing tests for redacted manifests and unavailable metrics**

```kotlin
@Test
fun `manifest records hashes and sizes without prompt text`() {
    val manifest = PromptManifest.from(BuiltPrompt("system secret", "persona", "user", "context"))
    assertEquals(listOf("system", "persona", "context", "user"), manifest.entries.map { it.id })
    assertFalse(manifest.toString().contains("system secret"))
}

@Test
fun `unreported provider usage remains unobservable`() {
    assertEquals(CacheMetricAvailability.UNOBSERVABLE, LlmCacheMetrics.Unobservable.availability)
}
```

- [ ] **Step 2: Run the tests and confirm missing types**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.LlmCacheMetricsTest" --tests "io.openeden.runtime.pipeline.MessagePipelineTest"`

Expected: FAIL on unresolved `PromptManifest` and `CacheMetricAvailability`.

- [ ] **Step 3: Implement the manifest and availability contract**

```kotlin
data class PromptManifestEntry(
    val id: String,
    val utf8Bytes: Int,
    val fingerprint: String,
)

data class PromptManifest(val entries: List<PromptManifestEntry>) {
    companion object {
        fun from(prompt: BuiltPrompt): PromptManifest = PromptManifest(
            listOf(
                "system" to prompt.systemText,
                "persona" to prompt.personaText,
                "context" to prompt.contextText,
                "user" to prompt.userText,
            ).filter { it.second.isNotBlank() }.map { (id, text) ->
                PromptManifestEntry(id, text.encodeToByteArray().size, Sha256.hex(text.encodeToByteArray()))
            },
        )
    }
}

enum class CacheMetricAvailability { REPORTED, UNOBSERVABLE }

data class LlmCacheMetrics(
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long,
    val availability: CacheMetricAvailability,
    val requestCount: Int = 1,
) {
    companion object {
        val Unobservable = LlmCacheMetrics(0, 0, 0, CacheMetricAvailability.UNOBSERVABLE, 0)
    }
}
```

Trace only IDs, sizes, fingerprints, cache availability, token counts, and local identical-prefix bytes. Never trace prompt text.

- [ ] **Step 4: Run core tests**

Run: `.\gradlew.bat :core:jvmTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/hash core/src/commonMain/kotlin/io/openeden/prompt core/src/commonMain/kotlin/io/openeden/llm core/src/commonMain/kotlin/io/openeden/memory/MemoryContentFingerprint.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt core/src/commonTest/kotlin/io/openeden
git commit -m "feat(prompt): trace redacted prompt and cache metrics"
```

### Task 3: Introduce An Authoritative Runtime Clock

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/time/RuntimeClock.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/time/SystemRuntimeClock.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/time/TemporalContext.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/time/TemporalContextProvider.kt`
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/time/MutableRuntimeClock.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/PromptTimeTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/heartbeat/HeartbeatSchedulerTest.kt`

**Interfaces:**
- Produces: `RuntimeClock.nowMs(): Long` and `TemporalContextProvider.forTurn(input, lastActivityMs)`.
- Consumers: pipeline, heartbeat, tick, transcript/prompt-history timestamps, long-run harness.

- [ ] **Step 1: Write failing conditional-time tests**

```kotlin
@Test
fun `ordinary adjacent turn omits exact timestamp`() {
    val clock = MutableRuntimeClock(1_777_000_000_000L)
    val context = TemporalContextProvider(clock).forTurn("今天吃什么", clock.nowMs() - 60_000L)
    assertNull(context.exactTime)
    assertEquals("recent", context.elapsedBucket)
}

@Test
fun `direct time question receives exact authoritative time`() {
    val context = TemporalContextProvider(MutableRuntimeClock(1234L)).forTurn("现在几点", null)
    assertEquals(1234L, context.exactTime)
}
```

- [ ] **Step 2: Run focused tests**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.prompt.PromptTimeTest" --tests "io.openeden.runtime.heartbeat.HeartbeatSchedulerTest"`

Expected: FAIL because the clock and temporal provider do not exist.

- [ ] **Step 3: Implement and inject the clock**

```kotlin
fun interface RuntimeClock { fun nowMs(): Long }

object SystemRuntimeClock : RuntimeClock {
    override fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}

data class TemporalContext(
    val exactTime: Long? = null,
    val elapsedBucket: String? = null,
    val dayPeriod: String? = null,
)
```

Replace direct runtime wall-clock calls in the touched path with the injected clock. Render temporal context only when non-empty and keep it after RAG in the dynamic suffix.

- [ ] **Step 4: Run core and server clock-sensitive tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*PromptTimeTest" --tests "*HeartbeatSchedulerTest" --tests "*SqlDelightTranscriptStoreTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add core/src server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt
git commit -m "refactor(runtime): inject authoritative runtime clock"
```

### Task 4: Probe Relay Cache Capabilities Without Production Side Effects

**Files:**
- Create: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiProviderCapabilities.kt`
- Create: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiCapabilityProbe.kt`
- Create: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiCapabilityCache.kt`
- Create: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiCapabilityProbeTest.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.conf`

**Interfaces:**
- Produces: `suspend fun probe(): OpenAiProviderCapabilities` keyed by base URL, model, and routing fingerprint.
- Consumers: `OpenAiResponsesLlmClient` in Task 15.

- [ ] **Step 1: Write the request-matrix test**

```kotlin
@Test
fun `probe distinguishes accepted metadata from observable cache usage`() = runTest {
    val capabilities = probeAgainstScriptedServer(
        basic = 200,
        cacheKey = 200,
        cacheOptions = 200,
        breakpoint = 502,
        usage = null,
    )
    assertTrue(capabilities.cacheKeyAccepted)
    assertFalse(capabilities.explicitBreakpointAccepted)
    assertEquals(CacheMetricAvailability.UNOBSERVABLE, capabilities.metricAvailability)
}
```

- [ ] **Step 2: Run the test**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.OpenAiCapabilityProbeTest"`

Expected: FAIL on missing probe types.

- [ ] **Step 3: Implement bounded canaries**

```kotlin
data class OpenAiProviderCapabilities(
    val basicResponses: Boolean,
    val cacheKeyAccepted: Boolean,
    val cacheOptionsAccepted: Boolean,
    val explicitBreakpointAccepted: Boolean,
    val previousResponseAccepted: Boolean,
    val metricAvailability: CacheMetricAvailability,
    val expiresAtMs: Long,
)
```

Use a dedicated canary namespace and minimal deterministic prompts. Never reuse a user request, never retry after SSE starts, and never include an API key or response body in traces.

- [ ] **Step 4: Run the probe tests**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.OpenAiCapabilityProbeTest"`

Expected: PASS for the full five-shape matrix.

- [ ] **Step 5: Commit**

```powershell
git add core/src/jvmMain/kotlin/io/openeden/llm core/src/jvmTest/kotlin/io/openeden/llm server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/main/resources/application.conf
git commit -m "feat(llm): probe relay prompt cache capabilities"
```

### Task 5: Build The Deterministic Long-Run Baseline Harness

**Files:**
- Create: `server/src/test/kotlin/io/openeden/server/evaluation/RelationshipScenario.kt`
- Create: `server/src/test/kotlin/io/openeden/server/evaluation/RelationshipLongRunHarness.kt`
- Create: `server/src/test/kotlin/io/openeden/server/evaluation/RelationshipLongRunHarnessTest.kt`
- Create: `scripts/run-relationship-evaluation.ps1`
- Create: `docs/evaluation/companion-quality-rubric.md`

**Interfaces:**
- Consumes: `RuntimeClock`, runtime pipeline, transcript, diagnostics, trace store.
- Produces: JSONL transcript, Bio CSV, relationship-event JSONL, prompt/cache manifest JSONL, and Markdown evaluation report.

- [ ] **Step 1: Write a failing deterministic clock/export test**

```kotlin
@Test
fun `scenario advances one authoritative virtual clock and exports every turn`() = runTest {
    val result = RelationshipLongRunHarness.fake().run(RelationshipScenario.canonical())
    assertTrue(result.turns.size in 120..200)
    assertTrue(result.turns.zipWithNext().all { (a, b) -> a.nowMs <= b.nowMs })
    assertEquals(result.turns.size, result.bioSnapshots.size)
}
```

- [ ] **Step 2: Run the harness test**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.evaluation.RelationshipLongRunHarnessTest"`

Expected: FAIL because the harness does not exist.

- [ ] **Step 3: Implement the canonical scenario and exports**

```kotlin
data class ScenarioTurn(
    val advanceMs: Long,
    val userText: String,
    val tags: Set<String>,
)

data class LongRunResult(
    val turns: List<EvaluatedTurn>,
    val bioSnapshots: List<BioVector>,
    val cacheReadRate: Double?,
)
```

Include at least twenty `要不要` negatives, confession/acceptance, restart, hot-romance reciprocity, chores, silence, heartbeat, real boundary, conflict, and repair. The PowerShell runner must accept `-Variant A|B`, `-Runs 3`, and `-OutputDirectory`.

- [ ] **Step 4: Run the fake deterministic baseline**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.evaluation.RelationshipLongRunHarnessTest"`

Expected: PASS and stable artifact fingerprints across two fake runs.

- [ ] **Step 5: Commit**

```powershell
git add server/src/test/kotlin/io/openeden/server/evaluation scripts/run-relationship-evaluation.ps1 docs/evaluation/companion-quality-rubric.md
git commit -m "test(runtime): add deterministic companion baseline harness"
```

### Task 6: Persist Bio State By Active Incarnation

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/incarnation/IncarnationState.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/incarnation/IncarnationStateStore.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/incarnation/MutableIncarnationStateStore.kt`
- Create: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationStateStore.kt`
- Create: `server/src/main/sqldelight/io/openeden/server/db/12.sqm`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Incarnation.sq`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/SessionState.sq`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationStateStoreTest.kt`

**Interfaces:**
- Produces: `read(incarnationId)`, `readOrCreate(incarnationId, mode, start)`, and `write(IncarnationState)`.
- Preserves: vector, origin, Omega, evolution, persona selection, activity/tick timestamps, ShockState.

- [ ] **Step 1: Write migration and cross-scope continuity tests**

```kotlin
@Test
fun `migration chooses one established state for the active incarnation`() = runTest {
    val store = openVersion11DatabaseWithTwoSessionStates()
    val state = store.read(activeIncarnationId)
    assertEquals(99L, state.evolutionIndex)
    assertEquals(PersonaSubState.AWAKENED, state.personaStartSubState)
}
```

- [ ] **Step 2: Run the persistence test**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.persistence.sqldelight.SqlDelightIncarnationStateStoreTest"`

Expected: FAIL because migration 12 and the store are absent.

- [ ] **Step 3: Implement the state contract and migration**

```kotlin
data class IncarnationState(
    val incarnationId: String,
    val vector: BioVector,
    val origin: BioVector,
    val omega: OmegaState,
    val evolutionIndex: Long,
    val personaMode: PersonaMode,
    val personaStartSubState: PersonaSubState,
    val lastUserActivityMs: Long?,
    val lastRuntimeTickAtMs: Long?,
    val shockState: ShockState?,
)

interface IncarnationStateStore {
    suspend fun read(incarnationId: String): IncarnationState
    suspend fun readOrCreate(
        incarnationId: String,
        personaMode: PersonaMode,
        personaStartSubState: PersonaSubState,
    ): IncarnationState
    suspend fun write(state: IncarnationState)
}
```

Migration 12 adds Bio columns to the singleton incarnation row, selects the most established legacy session by `(evolution_index DESC, last_user_activity_ms DESC, session_id ASC)`, copies it once, and leaves old session rows readable until Task 7 completes.

- [ ] **Step 4: Run SQLDelight migration and restart tests**

Run: `.\gradlew.bat :server:test --tests "*SqlDelightIncarnationStateStoreTest" --tests "*SqlDelightTranscriptStoreTest"`

Expected: PASS from schema versions 0, 4, 10, and 11.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/incarnation server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationStateStore.kt server/src/main/sqldelight server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightIncarnationStateStoreTest.kt
git commit -m "feat(runtime): persist bio state by incarnation"
```

### Task 7: Serialize All Bio Mutations Through The Incarnation Gate

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/incarnation/IncarnationTurnGate.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/incarnation/IncarnationMutexRegistry.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteResult.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/session/TurnCoordinatorConcurrencyTest.kt`

**Interfaces:**
- Consumes: `IncarnationStateStore` from Task 6.
- Produces: one mutex for all vector/Omega/Shock/evolution writes, while conversation transcript writes retain `sessionId`.

- [ ] **Step 1: Write the two-scope serialization test**

```kotlin
@Test
fun `different scopes of one incarnation cannot race bio writes`() = runTest {
    val fixture = GlobalIncarnationPipelineFixture()
    coroutineScope {
        launch { fixture.send("QQ:group-a", "a") }
        launch { fixture.send("WEB:user-a", "b") }
    }
    assertEquals(2L, fixture.state().evolutionIndex)
    assertEquals(1, fixture.maximumConcurrentBioWrites)
}
```

- [ ] **Step 2: Run concurrency tests**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.session.TurnCoordinatorConcurrencyTest"`

Expected: FAIL because writes are still guarded by session ID.

- [ ] **Step 3: Rewire pipeline, heartbeat, and tick**

```kotlin
suspend fun <T> withIncarnation(incarnationId: String, block: suspend () -> T): T =
    registry.forIncarnation(incarnationId).withLock { block() }
```

Resolve active incarnation before acquiring the Bio gate. Keep `sessionId` on `ConversationTurn`, retrieval visibility, delivery, and prompt-history calls. Atomic turn commit must write the global incarnation state and scoped transcript turn in one SQL transaction.

- [ ] **Step 4: Run core and server concurrency suites**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*TurnCoordinatorConcurrencyTest" --tests "*SqlDelightAtomicTurnCommitTest" --tests "*RuntimeTickSchedulerTest" --tests "*HeartbeatSchedulerTest"`

Expected: PASS with no lost `evolution_index` increments.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/main/kotlin/io/openeden/server/persistence/sqldelight
git commit -m "refactor(runtime): serialize bio writes by incarnation"
```

### Task 8: Add Canonical Subjects And Memory Visibility

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/identity/CanonicalSubjectId.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/identity/CanonicalSubjectResolver.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/memory/MemoryVisibility.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryMetadata.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/RetrievalRequest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/diary/DiaryTaskStore.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/diary/SessionDiaryQueue.kt`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Memory.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/13.sqm`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightDiaryTaskStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/MemoryVisibilityTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepositoryTest.kt`

**Interfaces:**
- Produces: canonical subject identity and `MemoryVisibility` authorization before ranking.
- Consumers: relationship key, memory writes, retrieval, exports.

- [ ] **Step 1: Write privacy-boundary tests**

```kotlin
@Test
fun `private memory is visible only to the bound canonical subject`() {
    assertTrue(MemoryVisibility.PrivateSubject("host").permits("host", "QQ:private"))
    assertFalse(MemoryVisibility.PrivateSubject("host").permits("guest", "QQ:group"))
}
```

- [ ] **Step 2: Run memory tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*MemoryVisibilityTest" --tests "*SqlDelightMemoryRepositoryTest"`

Expected: FAIL on missing visibility schema.

- [ ] **Step 3: Implement resolver and visibility filtering**

```kotlin
sealed interface MemoryVisibility {
    data class PrivateSubject(val subjectId: String) : MemoryVisibility
    data class ScopeShared(val sessionId: String) : MemoryVisibility
    data object IncarnationShared : MemoryVisibility
    data object OperatorOnly : MemoryVisibility
}
```

Unbound platform users resolve to a stable platform-local subject. Explicit configuration is the only cross-platform binding path. Migration 13 adds `incarnation_id`, `source_session_id`, `canonical_subject_id`, and visibility columns to memory/diary rows, backfills them from the active incarnation and legacy session/user metadata, and changes long-term queries and centroid sampling to incarnation ownership. Apply visibility filtering before semantic/emotional scoring so unauthorized candidates never enter diagnostics or backfill.

- [ ] **Step 4: Run memory and Qdrant projection tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*Memory*Test" --tests "*QdrantProjectionSynchronizerTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/identity core/src/commonMain/kotlin/io/openeden/memory core/src/commonMain/kotlin/io/openeden/runtime/diary/DiaryTaskStore.kt core/src/commonMain/kotlin/io/openeden/runtime/diary/SessionDiaryQueue.kt server/src/main/sqldelight server/src/main/kotlin/io/openeden/server/persistence/sqldelight server/src/test/kotlin/io/openeden/server/persistence/sqldelight
git commit -m "feat(memory): enforce subject and scope visibility"
```

### Task 9: Persist Relationship Signals, Facts, And Events

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipPhase.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipFacts.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipEvent.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipReducer.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipCorrection.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipState.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipStateStore.kt`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Relationship.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/14.sqm`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightRelationshipStateStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/RelationshipReducerTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightRelationshipStateStoreTest.kt`

**Interfaces:**
- Consumes: `(incarnationId, canonicalSubjectId)` from Tasks 6 and 8.
- Produces: idempotent append-only events and durable relationship facts.

- [ ] **Step 1: Write confession and restart tests**

```kotlin
@Test
fun `mutual acceptance establishes couple exactly once`() {
    val reduced = RelationshipReducer.reduce(
        RelationshipState.neutral("inc-1", "host"),
        listOf(userConfession("t1"), atriAcceptance("t1")),
    )
    assertEquals(RelationshipPhase.COUPLE, reduced.facts.phase)
    assertNotNull(reduced.facts.mutualCommitmentAtMs)
    assertEquals(reduced, RelationshipReducer.reduce(reduced, listOf(atriAcceptance("t1"))))
}
```

- [ ] **Step 2: Run relationship tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*RelationshipReducerTest" --tests "*SqlDelightRelationshipStateStoreTest"`

Expected: FAIL on missing facts and ledger tables.

- [ ] **Step 3: Implement the dual-layer model**

```kotlin
enum class RelationshipPhase { STRANGER, FAMILIAR, MUTUAL_INTEREST, COUPLE, ESTABLISHED_COUPLE }

data class RelationshipFacts(
    val phase: RelationshipPhase = RelationshipPhase.STRANGER,
    val userConfessedAtMs: Long? = null,
    val atriAcceptedAtMs: Long? = null,
    val mutualCommitmentAtMs: Long? = null,
    val preferredAddresses: Set<String> = emptySet(),
)
```

Add `reciprocalInterest` to continuous state. Ledger uniqueness is `(source_turn_id, event_type, incarnation_id, subject_id)`. Corrections append a `supersedes_event_id` event and rebuild facts by replay; reset appends an explicit reset event instead of silently deleting audit history. No turn-count-only transition may create `COUPLE`.

- [ ] **Step 4: Run restart and migration tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*Relationship*Test"`

Expected: PASS, including neutral migration of legacy rows.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/relationship core/src/commonTest/kotlin/io/openeden/relationship server/src/main/sqldelight server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightRelationshipStateStore.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightRelationshipStateStoreTest.kt
git commit -m "feat(relationship): persist facts and event ledger"
```

### Task 10: Replace Substring Relationship Classification

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/RelationshipEventEvaluator.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/relationship/DeterministicRelationshipEventEvaluator.kt`
- Create: `core/src/jvmMain/kotlin/io/openeden/relationship/OpenAiRelationshipEventEvaluator.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/relationship/RelationshipEventEvaluatorTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`

**Interfaces:**
- Produces: `suspend fun evaluate(turn: RelationshipTurn): RelationshipEvaluation`.
- Consumers: Task 9 ledger/reducer after validated LLM output.

- [ ] **Step 1: Write the boundary regression corpus**

```kotlin
@Test
fun `proposal phrases are never boundary requests`() = runTest {
    val evaluator = DeterministicRelationshipEventEvaluator()
    listOf("要不要吃饭", "你要不要抱我", "我不是不要你").forEach { text ->
        assertFalse(evaluator.evaluate(turn(text)).events.any { it.type == BOUNDARY_REQUEST })
    }
    assertTrue(evaluator.evaluate(turn("不要这样，请停下")).events.any { it.type == BOUNDARY_REQUEST })
}
```

- [ ] **Step 2: Run evaluator tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*RelationshipEventEvaluatorTest" --tests "*MessagePipelineTest"`

Expected: FAIL against the current `contains(Regex("不要|..."))` path.

- [ ] **Step 3: Implement structured evaluation and confidence gating**

```kotlin
data class RelationshipEvaluation(
    val events: List<RelationshipEvent>,
    val confidence: Float,
) {
    val committableEvents: List<RelationshipEvent>
        get() = if (confidence >= 0.75f) events else emptyList()
}

data class RelationshipTurn(
    val sourceTurnId: String,
    val incarnationId: String,
    val subjectId: String,
    val userText: String,
    val assistantText: String,
    val completedAtMs: Long,
)
```

The deterministic evaluator handles exact high-precision phrases and negation/proposal exclusions. The optional LLM evaluator uses strict JSON and sees only the validated user/ATRI turn. It cannot generate response text. Remove the substring classifier from `MessagePipeline`.

- [ ] **Step 4: Run all relationship and pipeline tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*Relationship*Test" --tests "*MessagePipeline*Test"`

Expected: PASS with zero `要不要` false positives.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/relationship core/src/jvmMain/kotlin/io/openeden/relationship core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden
git commit -m "fix(relationship): replace substring boundary detection"
```

### Task 11: Redesign ATRI Persona And Separate Private/Public Voice

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/persona/PersonaFewShot.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/persona/PersonaExampleMessage.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/persona/PersonaExampleRole.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/persona/PersonaOutputPolicy.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/PersonaResponseRewriter.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/persona/PersonaConfig.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/persona/PersonaFileLoader.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptSectionKeys.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/llm/LlmOutputValidator.kt`
- Modify: `persona/atri.yaml`
- Test: `core/src/jvmTest/kotlin/io/openeden/persona/AtriPersonaGuardTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/llm/LlmOutputValidatorTest.kt`

**Interfaces:**
- Consumes: relationship phase/facts from Task 9.
- Produces: first-person core, structured multi-turn examples, private-log/public-response boundary, persona-owned anti-service vocabulary.

- [ ] **Step 1: Write persona guards before editing YAML**

```kotlin
@Test
fun `atri persona covers all relationship phases with role messages`() {
    val persona = PersonaFileLoader.load(atriPath)
    assertEquals(RelationshipPhase.entries.toSet(), persona.fewShots.map { it.phase }.toSet())
    assertTrue(persona.fewShots.all { shot -> shot.messages.map { it.role }.containsAll(listOf(USER, ASSISTANT)) })
}

@Test
fun `public response rejects leaked operational vocabulary`() {
    val result = LlmOutputValidator.validate(validOutput(response = "收到，已经登记进库存"), atriPolicy())
    assertFalse(result.isValid)
}
```

- [ ] **Step 2: Run persona and validator tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*AtriPersonaGuardTest" --tests "*DefaultPromptBuilderTest" --tests "*LlmOutputValidatorTest"`

Expected: FAIL because persona few-shots are flat strings and no public-language policy exists.

- [ ] **Step 3: Implement structured persona data**

```kotlin
data class PersonaFewShot(
    val phase: RelationshipPhase,
    val messages: List<PersonaExampleMessage>,
)

enum class PersonaExampleRole { USER, ASSISTANT }

data class PersonaExampleMessage(
    val role: PersonaExampleRole,
    val content: String,
)

data class PersonaOutputPolicy(
    val prohibitedPublicPhrases: Set<String>,
    val maximumRepeatedOpening: Int,
)

fun interface PersonaResponseRewriter {
    suspend fun rewriteResponseOnly(
        output: LlmOutput,
        policy: PersonaOutputPolicy,
    ): LlmOutput
}
```

Write original Chinese multi-turn examples for stranger, familiar, mutual interest, couple, and established couple. Include direct romantic reciprocity and ordinary chores. Keep diary-like `internal_logic` instructions separate and explicitly require transformation into lively public ATRI speech. `PersonaResponseRewriter` performs at most one response-only rewrite after a schema-valid output violates the persona-owned public-language policy; it preserves `internal_logic` and `vector_delta`. Do not copy source dialogue or public repository prompts.

- [ ] **Step 4: Run persona, prompt, validator, and smoke tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*Persona*Test" --tests "*PromptBuilderTest" --tests "*LlmOutputValidatorTest" --tests "*ArtifactBackedKernelSmokeTest"`

Expected: PASS; tracked persona contains no recognizable source lines.

- [ ] **Step 5: Commit**

```powershell
git add persona/atri.yaml core/src/commonMain/kotlin/io/openeden/persona core/src/jvmMain/kotlin/io/openeden/persona core/src/commonMain/kotlin/io/openeden/prompt core/src/commonMain/kotlin/io/openeden/llm/LlmOutputValidator.kt core/src/commonMain/kotlin/io/openeden/llm/PersonaResponseRewriter.kt core/src/commonTest core/src/jvmTest
git commit -m "feat(persona): strengthen atri relationship voice"
```

### Task 12: Complete Lineage Deduplication With Capacity-Preserving Backfill

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryLineage.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryExclusionContext.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/RetrievalRequest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/RetrievalResult.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/memory/MemoryContextDeduplicationTest.kt`

**Interfaces:**
- Consumes: transcript and prompt-history `sourceTurnIds`.
- Produces: exact lineage exclusion, per-lane overfetch, and unique backfill to requested capacity.

- [ ] **Step 1: Write overlap and backfill tests**

```kotlin
@Test
fun `recent lineage is excluded and lower ranked unique memory backfills capacity`() = runTest {
    val result = fixture.retrieve(limit = 3, excludedTurnIds = setOf("t2"))
    assertEquals(3, result.memories.size)
    assertTrue(result.memories.none { "t2" in it.lineage.sourceTurnIds })
    assertEquals(emptySet(), result.memories.flatMap { it.lineage.sourceTurnIds }.toSet() intersect setOf("t2"))
}
```

- [ ] **Step 2: Run memory dedup tests**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.memory.MemoryContextDeduplicationTest"`

Expected: FAIL where candidate depth or lineage coverage is insufficient.

- [ ] **Step 3: Implement overfetch and lane-preserving backfill**

```kotlin
data class RetrievalDiagnostics(
    val excludedByTurnLineage: Int,
    val excludedByMemoryLineage: Int,
    val excludedByFingerprint: Int,
    val backfilled: Int,
    val underfilled: Boolean,
)
```

Retrieve at least `3 * K`, filter visibility first, then turn lineage, direct source memory, and legacy fingerprint. Preserve MIXED lane quotas and CONTRAST targeting. Continue deeper ranking until K unique memories or genuine exhaustion.

- [ ] **Step 4: Run all memory tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*Memory*Test" --tests "*Retrieval*Test"`

Expected: PASS and no capacity reduction when enough unique rows exist.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/memory core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/memory
git commit -m "fix(memory): backfill lineage-deduplicated retrieval"
```

### Task 13: Preserve Wire Items Across Prompt-History Sealing

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryItem.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryCompactor.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySummary.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryChunk.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySnapshot.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySerializer.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryAssembler.kt`
- Modify: `server/src/main/sqldelight/io/openeden/server/db/Transcript.sq`
- Create: `server/src/main/sqldelight/io/openeden/server/db/15.sqm`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/transcript/PromptHistoryAssemblerTest.kt`
- Test: `server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightPromptHistoryStoreTest.kt`

**Interfaces:**
- Produces: flat immutable role items whose serialized bytes do not change when moved from tail to sealed storage.
- Consumers: typed Prompt and provider client.

- [ ] **Step 1: Write byte-identity rollover tests**

```kotlin
@Test
fun `tail to sealed rollover preserves flattened wire items`() = runTest {
    val before = assembler.assemble(sessionId, turns.take(4), 2, 100)
    val after = assembler.assemble(sessionId, turns.take(5), 2, 100, before.stableChunks)
    assertEquals(before.flattenItems(), after.flattenItems().take(before.flattenItems().size))
}

@Test
fun `compaction preserves commitments and starts one new epoch`() = runTest {
    val compacted = compactor.compact("compact-1", snapshotWithCommitment())
    assertTrue(requireNotNull(compacted.summary).text.contains("约定"))
    assertEquals(1L, compacted.cacheEpoch)
    assertEquals(compacted, compactor.compact("compact-1", snapshotWithCommitment()))
}
```

- [ ] **Step 2: Run prompt-history tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*PromptHistory*Test"`

Expected: FAIL because chunks currently expose one `serializedText` instead of recoverable wire items.

- [ ] **Step 3: Implement immutable wire items**

```kotlin
data class PromptHistoryItem(
    val role: String,
    val text: String,
    val turnId: String,
    val fingerprint: String,
)

data class PromptHistoryChunk(
    val sessionId: String,
    val cacheEpoch: Long,
    val items: List<PromptHistoryItem>,
    val tokenCount: Int,
    val serializerVersion: Int,
)

data class PromptHistorySummary(
    val text: String,
    val sourceTurnIds: Set<String>,
    val fingerprint: String,
    val serializerVersion: Int,
)

data class PromptHistorySnapshot(
    val stableChunks: List<PromptHistoryChunk>,
    val summary: PromptHistorySummary?,
    val mutableTail: List<PromptHistoryItem>,
    val sourceTurnIds: Set<String>,
    val cacheEpoch: Long,
)

interface PromptHistoryCompactor {
    suspend fun compact(
        requestId: String,
        snapshot: PromptHistorySnapshot,
    ): PromptHistorySnapshot
}
```

Persist `items_json`; retain legacy `serialized_text` only for migration reads. Flatten items identically before and after sealing. `PromptHistoryCompactor` uses a versioned schema requiring named entities, commitments, unresolved questions, relationship facts, and chronology; it atomically activates a summary only after validation. A failed compaction keeps the previous epoch active and does not block the turn. Compaction alone may replace old items and increment epoch.

- [ ] **Step 4: Run transcript and migration tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*PromptHistory*Test" --tests "*SqlDelightTranscriptStoreTest"`

Expected: PASS with one explicit epoch change on serializer migration.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/transcript core/src/commonTest/kotlin/io/openeden/transcript server/src/main/sqldelight server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight
git commit -m "feat(transcript): preserve prompt history wire items"
```

### Task 14: Replace BuiltPrompt Strings With Typed Segments

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptRole.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptSegmentKind.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptStability.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptSegment.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/BuiltPrompt.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/StreamingTestFixtures.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt`

**Interfaces:**
- Consumes: PromptHistorySnapshot, relationship facts, deduplicated RAG, TemporalContext.
- Produces: ordered `BuiltPrompt.segments` and stable cache identity.

- [ ] **Step 1: Write the exact-order test**

```kotlin
@Test
fun `history precedes every current dynamic segment`() = runTest {
    val prompt = builder.build(inputWithAllContext())
    assertEquals(
        listOf(SYSTEM_CONTRACT, PERSONA, INCARNATION_ANCHOR, HISTORY, BIO, RELATIONSHIP, RAG, TEMPORAL, USER),
        prompt.segments.map { it.kind },
    )
}
```

- [ ] **Step 2: Run prompt and transcript tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*DefaultPromptBuilderTest" --tests "*MessagePipelineTranscriptTest"`

Expected: FAIL because `BuiltPrompt` still has four strings.

- [ ] **Step 3: Implement typed segments and pipeline history reads**

```kotlin
data class PromptSegment(
    val id: String,
    val role: PromptRole,
    val kind: PromptSegmentKind,
    val stability: PromptStability,
    val text: String,
    val fingerprint: String,
    val turnIds: List<String> = emptyList(),
)

enum class PromptRole { SYSTEM, DEVELOPER, USER, ASSISTANT }

enum class PromptStability { STABLE, APPEND_ONLY, DYNAMIC }

enum class PromptSegmentKind {
    SYSTEM_CONTRACT,
    PERSONA,
    INCARNATION_ANCHOR,
    HISTORY,
    BIO,
    RELATIONSHIP,
    RAG,
    TEMPORAL,
    USER,
}

data class BuiltPrompt(
    val segments: List<PromptSegment>,
    val cacheIdentity: String,
)
```

Pipeline calls `transcriptStore.promptHistory`, passes all history lineage into RAG exclusion, and never calls `takeLast(N)` to rebuild the wire prefix. Current Codebook, relationship, RAG, temporal context, and user input remain dynamic and current.

- [ ] **Step 4: Run core tests**

Run: `.\gradlew.bat :core:jvmTest`

Expected: PASS, including stable-manifest golden tests.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/prompt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden
git commit -m "refactor(prompt): assemble typed immutable segments"
```

### Task 15: Send Append-Oriented Requests With Capability-Gated Cache Metadata

**Files:**
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiPromptCachingMode.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Modify: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.conf`

**Interfaces:**
- Consumes: typed segments and capabilities from Tasks 4 and 14.
- Produces: `OFFICIAL_EXPLICIT`, `RELAY_APPEND_ONLY`, `OBSERVE_ONLY`, and `CACHE_DISABLED` request policies.

- [ ] **Step 1: Write relay request-shape and no-unsafe-retry tests**

```kotlin
@Test
fun `relay append mode omits breakpoint and preserves segment order`() = runTest {
    val request = captureRequest(client(RELAY_APPEND_ONLY), typedPrompt())
    assertNull(request.find("prompt_cache_breakpoint"))
    assertEquals(typedPrompt().segments.map { it.role.apiValue }, request.input.map { it.role })
}

@Test
fun `unknown 502 is not retried`() = runTest {
    val server = scriptedServer(502, 200)
    assertFails { client(server).complete(typedPrompt()) }
    assertEquals(1, server.requestCount)
}
```

- [ ] **Step 2: Run OpenAI client tests**

Run: `.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.OpenAiResponsesLlmClientTest"`

Expected: FAIL because the client still reconstructs four messages and model-name heuristics enable options.

- [ ] **Step 3: Implement provider policy selection**

```kotlin
enum class OpenAiCachePolicy {
    OFFICIAL_EXPLICIT,
    RELAY_APPEND_ONLY,
    OBSERVE_ONLY,
    CACHE_DISABLED,
}
```

Serialize each segment as its own Responses input message. Custom URLs default to `RELAY_APPEND_ONLY`; explicit options and breakpoints require positive capability evidence. Parse buffered and SSE usage identically. Retry only a recognized unsupported-field 4xx before any response bytes and only once with metadata removed.

Derive `prompt_cache_key` from provider/model policy revision, system/schema revision, persona/starting-point revision, opaque conversation cache identity, and dialogue namespace. Do not include current Bio, relationship, RAG, time, request ID, or prompt text in model-visible cache metadata.

- [ ] **Step 4: Run client and bootstrap tests**

Run: `.\gradlew.bat :core:jvmTest :server:test --tests "*OpenAi*Test" --tests "*LlmGenerationConfigTest"`

Expected: PASS, including the production-like breakpoint-502 fixture.

- [ ] **Step 5: Commit**

```powershell
git add core/src/jvmMain/kotlin/io/openeden/llm core/src/jvmTest/kotlin/io/openeden/llm server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/main/resources/application.conf
git commit -m "feat(llm): add relay append prompt cache policy"
```

### Task 16: Apply Boundary-Aware 8D Damping And Homeostasis

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorDeltaReducer.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorDeltaReduction.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorDeltaContext.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteResult.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/state/VectorDeltaReducerTest.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/state/RuntimeInvariantTest.kt`

**Interfaces:**
- Consumes: proposed LLM delta, current vector, dynamic centroid, elapsed time, authoritative shock flag.
- Produces: effective delta and traceable clamp/homeostasis contributions.

- [ ] **Step 1: Write signed, neutral, and saturation tests**

```kotlin
@Test
fun `ordinary positive proposals cannot pin a dimension at one`() {
    var vector = BioVector.Neutral.copy(p = 0.98f)
    repeat(11) {
        vector = reducer.reduce(vector, origin, VectorDelta(p = 0.1f), VectorDeltaContext.Ordinary).result
    }
    assertTrue(vector.p < 0.99f)
}

@Test
fun `movement away from a boundary remains available`() {
    val reduction = reducer.reduce(
        BioVector.Neutral.copy(f = 0.99f),
        origin,
        VectorDelta(f = -0.1f),
        VectorDeltaContext.Ordinary,
    )
    assertTrue(reduction.effectiveDelta.f < -0.05f)
}
```

- [ ] **Step 2: Run state tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*VectorDeltaReducerTest" --tests "*RuntimeInvariantTest"`

Expected: FAIL because raw deltas are directly applied.

- [ ] **Step 3: Implement the reducer**

```kotlin
data class VectorDeltaReduction(
    val proposedDelta: VectorDelta,
    val effectiveDelta: VectorDelta,
    val homeostaticDelta: VectorDelta,
    val result: BioVector,
    val reasons: Set<String>,
)

sealed interface VectorDeltaContext {
    data object Ordinary : VectorDeltaContext
    data class Authoritative(val confidence: Float) : VectorDeltaContext
}
```

Validate finite values, clamp per dimension, apply a neutral dead zone, damp motion toward storage boundaries by remaining headroom, apply elapsed-time pull toward the dynamic centroid in internal space, and map back. Shock/high-confidence external signals use an explicit stronger gain; persona text never selects gains.

- [ ] **Step 4: Run all Bio, tick, Shock, and pipeline tests**

Run: `.\gradlew.bat :core:jvmTest --tests "*Vector*Test" --tests "*RuntimeTick*Test" --tests "*Shock*Test" --tests "*MessagePipeline*Test"`

Expected: PASS; neutral median absolute effective delta fixture is at most `0.02`.

- [ ] **Step 5: Commit**

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/state core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden
git commit -m "fix(runtime): damp saturated bio vector deltas"
```

### Task 17: Enforce The Full A/B Acceptance Gate

**Files:**
- Create: `server/src/test/kotlin/io/openeden/server/evaluation/CompanionQualityMetrics.kt`
- Create: `server/src/test/kotlin/io/openeden/server/evaluation/PairwiseEvaluation.kt`
- Modify: `server/src/test/kotlin/io/openeden/server/evaluation/RelationshipLongRunHarness.kt`
- Modify: `scripts/run-relationship-evaluation.ps1`
- Modify: `docs/evaluation/companion-quality-rubric.md`

**Interfaces:**
- Consumes: A/B transcripts and per-turn exports from Task 5.
- Produces: a pass/fail release report with every threshold from spec section 18.

- [ ] **Step 1: Write metric boundary tests**

```kotlin
@Test
fun `release fails when romance improves but factual quality regresses`() {
    val result = CompanionQualityMetrics(
        boundaryFalsePositives = 0,
        romanticReciprocity = 0.95,
        proceduralReplyRate = 0.01,
        pairwiseWinRate = 0.75,
        factualRegression = true,
    )
    assertFalse(result.passesReleaseGate())
}
```

- [ ] **Step 2: Run evaluation tests**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.evaluation.*"`

Expected: FAIL because the release gate is absent.

- [ ] **Step 3: Implement exact thresholds and unknown cache semantics**

```kotlin
fun CompanionQualityMetrics.passesReleaseGate(): Boolean =
    boundaryFalsePositives == 0 &&
        romanticReciprocity >= 0.90 &&
        proceduralReplyRate < 0.02 &&
        pairwiseWinRate >= 0.70 &&
        !factualRegression &&
        memoryLineageOverlap == 0 &&
        saturationViolations == 0 &&
        (cacheMetricAvailability == UNOBSERVABLE || warmCacheReadRate >= 0.85)
```

Run at least three candidate repetitions when provider seed control is unavailable. Keep evaluator version, model, scenario fingerprint, and raw pairwise decisions in the report.

- [ ] **Step 4: Run fake A/B acceptance tests and repository tests**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.evaluation.*"`

Expected: PASS for a qualifying fixture and FAIL for every single-threshold regression fixture.

- [ ] **Step 5: Commit**

```powershell
git add server/src/test/kotlin/io/openeden/server/evaluation scripts/run-relationship-evaluation.ps1 docs/evaluation/companion-quality-rubric.md
git commit -m "test(runtime): enforce companion quality release gates"
```

### Task 18: Add Safe State Reset, Export, And Production Runbook

**Files:**
- Create: `server/src/main/kotlin/io/openeden/server/maintenance/IncarnationDataExporter.kt`
- Create: `server/src/main/kotlin/io/openeden/server/maintenance/IncarnationResetService.kt`
- Create: `server/src/test/kotlin/io/openeden/server/maintenance/IncarnationResetServiceTest.kt`
- Create: `scripts/export-production-conversation.ps1`
- Create: `scripts/reset-production-incarnation.ps1`
- Modify: `scripts/verify-production-runtime.ps1`
- Create: `docs/operations/companion-quality-production-rollout.md`

**Interfaces:**
- Consumes: incarnation state, transcript, memories, relationship ledger, diary tasks/archive, trace/cache history.
- Produces: auditable export archive and idempotent explicit reset command.

- [ ] **Step 1: Write reset completeness and idempotency tests**

```kotlin
@Test
fun `reset clears all incarnation layers and creates one fresh active incarnation`() = runTest {
    val result = fixture.reset(requestId = "production-test-reset")
    assertEquals(0L, fixture.memoryCount())
    assertEquals(0L, fixture.relationshipEventCount())
    assertEquals(0L, fixture.transcriptCount())
    assertEquals(0L, fixture.promptHistoryCount())
    assertEquals(IncarnationLifecycle.ACTIVE, result.lifecycle)
    assertEquals(result, fixture.reset(requestId = "production-test-reset"))
}
```

- [ ] **Step 2: Run maintenance tests**

Run: `.\gradlew.bat :server:test --tests "io.openeden.server.maintenance.IncarnationResetServiceTest"`

Expected: FAIL because no unified reset service exists.

- [ ] **Step 3: Implement export-before-reset and guarded scripts**

```kotlin
data class IncarnationExportManifest(
    val incarnationId: String,
    val exportedAtMs: Long,
    val transcriptCount: Long,
    val memoryCount: Long,
    val relationshipEventCount: Long,
    val sha256: String,
)
```

Reset requires an exact active incarnation ID, a request ID, a completed export manifest, and an explicit confirmation flag. It clears transcript, Memory Palace/embeddings, diary queue/archive, relationship state/events, traces, prompt-history state/chunks, Bio/Omega/Shock/evolution, then creates one fresh incarnation. Scripts must refuse port `8080` and target only the configured OpenEden service/data paths.

- [ ] **Step 4: Run the full local release gate**

Run: `.\gradlew.bat clean check`

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-production-runtime.ps1`

Expected: all Gradle checks pass; production verifier reports configured OpenEden API port, Relay policy/capabilities, database schema version, and no attempt to bind `8080`.

- [ ] **Step 5: Commit**

```powershell
git add server/src/main/kotlin/io/openeden/server/maintenance server/src/test/kotlin/io/openeden/server/maintenance scripts docs/operations/companion-quality-production-rollout.md
git commit -m "feat(server): add incarnation export and reset workflow"
```

## Final Execution Gate

After Task 18, do not deploy immediately. Run the real 120-200 turn A/B suite against a clean candidate database, review the pairwise samples manually, and require every Task 17 threshold. When the Relay does not expose cache usage, report the cache result as `UNOBSERVABLE` and inspect only the local identical-prefix metric; do not waive any dialogue-quality or state-continuity gate.

Only after the candidate passes:

1. Export the current production incarnation and verify its manifest hash.
2. Stop OpenEden, leaving Illusion Server and port `8080` untouched.
3. Back up the SQLite database and configuration.
4. Deploy the candidate and run schema migrations once.
5. Execute the guarded incarnation reset requested for production testing.
6. Start OpenEden on its configured external port and run health/capability checks.
7. Run the production conversation scenario and export transcript, Bio, relationship, memory-lineage, prompt-segment, and cache metrics.
