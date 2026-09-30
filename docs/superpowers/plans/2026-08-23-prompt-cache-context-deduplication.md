# Prompt Cache Context Deduplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Preserve all required OpenEden context while eliminating cross-layer duplicate injections and extending exact prompt-cache reuse through immutable conversation history.

**Architecture:** Add an authoritative, session-scoped transcript context contract; attach explicit source lineage and canonical fingerprints to memories; retrieve an oversized ranked candidate pool and backfill after lineage filtering; then serialize immutable transcript chunks before dynamic Codebook/runtime/RAG/tail blocks. The existing relay capability gate remains unchanged, and cache keys stay stable across history epochs.

**Tech Stack:** Kotlin Multiplatform common/JVM, Ktor, coroutines, SQLDelight SQLite migrations, kotlinx.serialization, OpenAI Responses API, existing InferenceDispatcher, TraceStore, and Gradle JVM test tasks.

---

## Scope And File Map

The implementation is one coordinated feature with independently testable phases.

- Memory contract: core/src/commonMain/kotlin/io/openeden/memory/MemoryMetadata.kt, new MemoryLineage.kt, new MemoryContentFingerprint.kt.
- Memory persistence: server/src/main/sqldelight/io/openeden/server/db/Memory.sq, new migrations 9.sqm, and SqlDelightMemoryRepository.kt.
- Transcript context: TranscriptStore.kt, InMemoryTranscriptStore.kt, Transcript.sq, SqlDelightTranscriptStore.kt, and new prompt-history types.
- Retrieval filtering: RetrievalRequest.kt, RetrievalResult.kt, new MemoryExclusionContext.kt, MemoryPalace.kt, SqlDelightMemoryRepository.kt.
- Prompt assembly: PromptInput.kt, BuiltPrompt.kt, OpenEdenPromptBuilder.kt, and new deterministic history serializer/assembler types.
- Provider serialization: OpenAiResponsesLlmClient.kt and its JVM tests.
- Observability: TraceTag.kt, prompt assembly metrics, existing LLM cache metrics, and diagnostic DTOs where exposed.

Do not modify persona YAML, VQ-VAE model code, vector math, retrieval-mode definitions, Illusion server files, or the existing untracked docs/diagnostics/ directory.

## Task 1: Establish The Pure Lineage And Fingerprint Contract

Files:
- Create core/src/commonMain/kotlin/io/openeden/memory/MemoryLineage.kt
- Create core/src/commonMain/kotlin/io/openeden/memory/MemoryContentFingerprint.kt
- Modify core/src/commonMain/kotlin/io/openeden/memory/MemoryMetadata.kt
- Create core/src/commonTest/kotlin/io/openeden/memory/MemoryLineageTest.kt

- [ ] Step 1: Write failing lineage tests.

Cover these exact behaviors:

    @Test
    fun lineage_preserves_ordered_distinct_ids() {
        val lineage = MemoryLineage(
            sourceTurnIds = listOf("turn-2", "turn-1", "turn-2"),
            sourceMemoryIds = listOf("memory-b", "memory-a", "memory-b"),
        )
        assertEquals(listOf("turn-1", "turn-2"), lineage.sourceTurnIds)
        assertEquals(listOf("memory-a", "memory-b"), lineage.sourceMemoryIds)
    }

    @Test
    fun fingerprint_normalizes_only_boundary_representation_noise() {
        val first = MemoryContentFingerprint.of("user: hi\r\nassistant: hello  ")
        val second = MemoryContentFingerprint.of("user: hi\nassistant: hello")
        assertEquals(first, second)
        assertNotEquals(first, MemoryContentFingerprint.of("user:  hi\nassistant: hello"))
    }

- [ ] Step 2: Run the focused test and verify it fails.

    .\gradlew.bat :core:jvmTest --tests '*MemoryLineageTest'

Expected: compilation failure because the new types do not exist.

- [ ] Step 3: Add the minimum data types.

MemoryLineage exposes sorted distinct sourceTurnIds and sourceMemoryIds, a lineageVersion, and isEmpty. MemoryContentFingerprint canonicalizes CRLF/CR to LF and trims only line-end and document-boundary whitespace before SHA-256 hashing. Keep platform hashing behind a small common interface if the common target cannot provide SHA-256.

Extend MemoryMetadata with these defaulted properties:

    val lineage: MemoryLineage = MemoryLineage.Empty
    val contentFingerprint: String? = null

Defaults preserve existing fixtures and old serialized metadata.

- [ ] Step 4: Run the focused test and verify it passes.

    .\gradlew.bat :core:jvmTest --tests '*MemoryLineageTest'

Expected: PASS.

- [ ] Step 5: Commit.

    git add core/src/commonMain/kotlin/io/openeden/memory/MemoryLineage.kt core/src/commonMain/kotlin/io/openeden/memory/MemoryContentFingerprint.kt core/src/commonMain/kotlin/io/openeden/memory/MemoryMetadata.kt core/src/commonTest/kotlin/io/openeden/memory/MemoryLineageTest.kt
    git commit -m "feat(memory): add explicit memory lineage contract"

## Task 2: Persist Lineage Without Changing Retrieval Yet

Files:
- Modify server/src/main/sqldelight/io/openeden/server/db/Memory.sq
- Create server/src/main/sqldelight/io/openeden/server/db/9.sqm
- Modify server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Modify core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt
- Test server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepositoryTest.kt
- Test core/src/commonTest/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGeneratorTest.kt

- [ ] Step 1: Add migration and round-trip tests first.

Open a pre-v8 fixture, write a RAW entry with a turn ID, reopen it, and assert lineage and fingerprint survive. Write a NARRATIVE entry from a diary task with sourceMemoryId and assert direct source-memory lineage.

- [ ] Step 2: Run tests and verify failure.

    .\gradlew.bat :server:test --tests '*SqlDelightMemoryRepositoryTest' --tests '*LlmDiaryNarrativeGeneratorTest'

Expected: failure because generated SQLDelight schema has no lineage columns.

- [ ] Step 3: Add SQLDelight columns and migration 9.

Add to memory_entries and migration 9.sqm:

    source_turn_ids_json TEXT NOT NULL DEFAULT '[]',
    source_memory_ids_json TEXT NOT NULL DEFAULT '[]',
    content_fingerprint TEXT,
    lineage_version INTEGER NOT NULL DEFAULT 1

The persisted lineage representation must be bounded. Do not silently truncate source IDs. Store a bounded exact ID list plus explicit overflow metadata: a source-turn time range when the source rows are contiguous and a digest of the complete ordered source set. Overlap checks may use exact IDs and explicitly represented ranges only; they must never infer overlap from the digest alone. Add round-trip tests for both the bounded case and overflow case.

Update selectById, selectByIds, selectBySession, selectRecent, selectRawMemoryRange, selectLatestRawMemory, insertEntry, and every row mapper. Keep active embedding-model filtering unchanged.

- [ ] Step 4: Thread source IDs into writes.

When MessagePipeline creates a RAW MemoryEntry, set sourceTurnIds to the committed turn ID and compute the fingerprint from canonical visible user/assistant content. Reuse the same turn ID for transcript commit.

When LlmDiaryNarrativeGenerator creates a NARRATIVE entry, copy diary-task sourceMemoryId and the source memory lineage. If the source cannot be loaded, preserve empty lineage and fingerprint the narrative text.

- [ ] Step 5: Run focused persistence tests.

    .\gradlew.bat :server:test --tests '*SqlDelightMemoryRepositoryTest' --tests '*LlmDiaryNarrativeGeneratorTest'

Expected: PASS.

- [ ] Step 6: Commit.

    git add server/src/main/sqldelight/io/openeden/server/db/Memory.sq server/src/main/sqldelight/io/openeden/server/db/9.sqm server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepositoryTest.kt core/src/commonTest/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGeneratorTest.kt
    git commit -m "feat(memory): persist source lineage for memories"

## Task 3: Make TranscriptStore The Recent-Conversation Authority

Files:
- Modify core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt
- Modify core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt
- Modify server/src/main/sqldelight/io/openeden/server/db/Transcript.sq
- Create server/src/main/sqldelight/io/openeden/server/db/10.sqm
- Modify server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Test core/src/commonTest/kotlin/io/openeden/transcript/InMemoryTranscriptStoreTest.kt
- Test server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStoreTest.kt
- Test core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt

- [ ] Step 1: Add the session-scoped API and failing tests.

Add this method to TranscriptStore:

    suspend fun recentForSession(sessionId: String, limit: Int): List<ConversationTurn>

The result is chronological, capped at limit, and includes only that session. Use two sessions in one incarnation in tests.

- [ ] Step 2: Run focused tests and verify the API is missing.

    .\gradlew.bat :core:jvmTest --tests '*InMemoryTranscriptStoreTest' --tests '*MessagePipelineTranscriptTest'
    .\gradlew.bat :server:test --tests '*SqlDelightTranscriptStoreTest'

Expected: compilation failure.

- [ ] Step 3: Implement both recent queries.

In memory, filter by session, sort by completedAtMs and turnId, take newest limit, and return chronological order. In Transcript.sq add:

    CREATE INDEX conversation_turns_session_recent
    ON conversation_turns(session_id, completed_at_ms DESC, turn_id DESC);

    selectRecentForSession:
    SELECT turn_id, incarnation_id, session_id, platform, scope_id, user_id,
           user_text, assistant_text, completed_at_ms
    FROM conversation_turns
    WHERE session_id = :sessionId
    ORDER BY completed_at_ms DESC, turn_id DESC
    LIMIT :limit;

Add the index in migration 10.sqm and reverse the query result in SqlDelightTranscriptStore.

- [ ] Step 4: Replace the memory-backed recent overwrite.

At MessagePipeline.kt near the current memoryStore.recent call, load recentForSession before retrieval/prompt construction. Remove the assignment that replaces RetrievalResult.recentMemories with memory rows. recent_turns must only come from transcript records.

- [ ] Step 5: Run transcript and pipeline tests.

    .\gradlew.bat :core:jvmTest --tests '*InMemoryTranscriptStoreTest' --tests '*MessagePipelineTranscriptTest'
    .\gradlew.bat :server:test --tests '*SqlDelightTranscriptStoreTest'

Expected: PASS; normal dialogue keeps two turns and immediate-reference inputs keep four.

- [ ] Step 6: Commit.

    git add core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt server/src/main/sqldelight/io/openeden/server/db/Transcript.sq server/src/main/sqldelight/io/openeden/server/db/10.sqm server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/transcript/InMemoryTranscriptStoreTest.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStoreTest.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt
    git commit -m "fix(transcript): source recent context from completed turns"

## Task 4: Add Lineage-Aware Retrieval Overfetch And Backfill

Files:
- Create core/src/commonMain/kotlin/io/openeden/memory/MemoryExclusionContext.kt
- Modify core/src/commonMain/kotlin/io/openeden/memory/RetrievalRequest.kt
- Modify core/src/commonMain/kotlin/io/openeden/memory/RetrievalResult.kt
- Modify core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt
- Modify server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Create core/src/commonTest/kotlin/io/openeden/memory/MemoryContextDeduplicationTest.kt
- Modify server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepositoryTest.kt

- [ ] Step 1: Define exclusion data and failing tests.

Add:

    data class MemoryExclusionContext(
        val sourceTurnIds: Set<String> = emptySet(),
        val sourceMemoryIds: Set<String> = emptySet(),
        val contentFingerprints: Set<String> = emptySet(),
    )

Add it to RetrievalRequest with an empty default. Extend RetrievalResult with lineageExcludedCount, fingerprintExcludedCount, backfillDepth, and underfilled, all defaulted for old fixtures.

Test that different RAW/NARRATIVE IDs sharing a source turn are injected once, an exact legacy duplicate is replaced by a deeper candidate, and related entries without lineage remain.

- [ ] Step 2: Run the focused test and verify failure.

    .\gradlew.bat :core:jvmTest --tests '*MemoryContextDeduplicationTest'

Expected: compilation or assertion failure.

- [ ] Step 3: Implement deterministic candidate oversampling.

Keep public maxResults unchanged. Internally request maxResults times 3, capped by available rows and never below maxResults when rows exist. Apply utility filtering, then mode-aware ranking, then lineage/fingerprint exclusion, then final selection.

For MIXED, overfetch and filter congruent and positive-skew streams independently, preserve the 60/40 target when both lanes have enough unique candidates, and use current-vector fill only after a lane is exhausted. Never use semantic similarity as a destructive filter.

- [ ] Step 4: Pass all injected-history exclusions from MessagePipeline.

Build MemoryExclusionContext from every history block injected in this request, including stable chunks, frozen summary, and mutable tail. Pass it into RetrievalRequest. Do not add a second independent dedup algorithm to the renderer.

- [ ] Step 5: Run focused tests.

    .\gradlew.bat :core:jvmTest --tests '*MemoryContextDeduplicationTest' --tests '*MessagePipelineTranscriptTest' --tests '*RuntimePipelineTest'
    .\gradlew.bat :server:test --tests '*SqlDelightMemoryRepositoryTest'

Expected: PASS with original RAG capacity preserved whenever unique candidates exist and explicit underfill otherwise.

- [ ] Step 6: Commit.

    git add core/src/commonMain/kotlin/io/openeden/memory/MemoryExclusionContext.kt core/src/commonMain/kotlin/io/openeden/memory/RetrievalRequest.kt core/src/commonMain/kotlin/io/openeden/memory/RetrievalResult.kt core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/memory/MemoryContextDeduplicationTest.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepositoryTest.kt
    git commit -m "fix(memory): backfill lineage-filtered retrieval results"

## Task 5: Implement Deterministic Immutable History Chunks

Files:
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryChunk.kt
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySnapshot.kt
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryAssembler.kt
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySerializer.kt
- Modify core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt
- Modify core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt
- Modify server/src/main/sqldelight/io/openeden/server/db/Transcript.sq
- Create server/src/main/sqldelight/io/openeden/server/db/11.sqm
- Modify server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt
- Create core/src/commonTest/kotlin/io/openeden/transcript/PromptHistoryAssemblerTest.kt
- Create server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightPromptHistoryStoreTest.kt

- [ ] Step 1: Define contracts.

    data class PromptHistoryChunk(
        val sessionId: String,
        val cacheEpoch: Long,
        val firstTurnId: String,
        val lastTurnId: String,
        val turnIds: List<String>,
        val serializedText: String,
        val tokenCount: Int,
        val fingerprint: String,
        val serializerVersion: Int,
    )

    data class PromptHistorySnapshot(
        val stableChunks: List<PromptHistoryChunk> = emptyList(),
        val frozenSummary: String? = null,
        val mutableTail: List<ConversationTurn> = emptyList(),
        val sourceTurnIds: Set<String> = emptySet(),
        val cacheEpoch: Long = 0L,
    )

Add suspend promptHistory(sessionId, requiredTailTurns, tokenBudget) to TranscriptStore. Never return another session's chunks.

- [ ] Step 2: Write failing exact-serialization tests.

Assert exact serialized text, chunk fingerprints, source turn IDs, deterministic boundaries, unchanged sealed chunks after appending, and four-turn immediate-reference tails.

- [ ] Step 3: Run and verify failure.

    .\gradlew.bat :core:jvmTest --tests '*PromptHistoryAssemblerTest'

Expected: compilation failure.

- [ ] Step 4: Implement serializer and assembler.

Use fixed headers, chronological order, fixed escaping, UTF-8 text, and a serializer version. Seal when the next turn exceeds token budget or turn ceiling. Keep the newest required tail mutable. Inject token counting so tests use a deterministic estimator and JVM production can use OpenAiTokenCounter.

- [ ] Step 5: Persist chunks and epoch state.

Add to Transcript.sq and migration 11.sqm:

    CREATE TABLE prompt_history_state (
        session_id TEXT NOT NULL PRIMARY KEY,
        cache_epoch INTEGER NOT NULL,
        serializer_version INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL
    );

    CREATE TABLE prompt_history_chunks (
        chunk_id TEXT NOT NULL PRIMARY KEY,
        session_id TEXT NOT NULL,
        cache_epoch INTEGER NOT NULL,
        first_turn_id TEXT NOT NULL,
        last_turn_id TEXT NOT NULL,
        turn_ids_json TEXT NOT NULL,
        serialized_text TEXT NOT NULL,
        token_count INTEGER NOT NULL,
        fingerprint TEXT NOT NULL,
        serializer_version INTEGER NOT NULL
    );

Add deterministic queries by session and epoch. Serializer changes increment epoch instead of mutating old chunks.

- [ ] Step 6: Run tests.

    .\gradlew.bat :core:jvmTest --tests '*PromptHistoryAssemblerTest' --tests '*InMemoryTranscriptStoreTest'
    .\gradlew.bat :server:test --tests '*SqlDelightPromptHistoryStoreTest' --tests '*SqlDelightTranscriptStoreTest'

Expected: PASS, including restart persistence and stable fingerprints.

- [ ] Step 7: Commit.

    git add core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryChunk.kt core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySnapshot.kt core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryAssembler.kt core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySerializer.kt core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt core/src/commonMain/kotlin/io/openeden/transcript/InMemoryTranscriptStore.kt server/src/main/sqldelight/io/openeden/server/db/Transcript.sq server/src/main/sqldelight/io/openeden/server/db/11.sqm server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt core/src/commonTest/kotlin/io/openeden/transcript/PromptHistoryAssemblerTest.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightPromptHistoryStoreTest.kt
    git commit -m "feat(transcript): add immutable prompt history chunks"

## Task 6: Split Prompt Assembly Into Stable And Dynamic Blocks

Files:
- Modify core/src/commonMain/kotlin/io/openeden/prompt/BuiltPrompt.kt
- Modify core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt
- Modify core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Modify core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt
- Modify core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt

- [ ] Step 1: Add explicit prompt-layer fields and failing tests.

Use this shape:

    data class BuiltPrompt(
        val systemText: String,
        val personaText: String,
        val userText: String,
        val stableContextBlocks: List<String> = emptyList(),
        val dynamicContextBlocks: List<String> = emptyList(),
        val cacheNamespace: String? = null,
    )

Keep a read-only contextText compatibility property for existing non-provider callers. It joins blocks for diagnostics and must never serialize as one provider context item. Add PromptHistorySnapshot and cacheNamespace to PromptInput with empty defaults for stubs.

- [ ] Step 2: Run prompt tests and verify old assumptions fail.

    .\gradlew.bat :core:jvmTest --tests '*DefaultPromptBuilderTest' --tests '*MessagePipelineTranscriptTest'

Expected: compilation or assertion failures identify old single-context assumptions.

- [ ] Step 3: Implement stable/dynamic ordering.

Stable blocks are frozen summary followed by sealed chunks. The model-visible session anchor contains only immutable persona mode and starting point; opaque cache namespace is metadata only. Dynamic blocks are Codebook/derived D, runtime state, user affect/relationship, deduplicated memories, mutable tail, heartbeat context, and system time.

Remove recentMemories as the source of recent_turns from memory_retrieval. Render transcript tail only from PromptHistorySnapshot. Keep RAG memories and retrieval-mode labels.

- [ ] Step 4: Run common prompt and pipeline tests.

    .\gradlew.bat :core:jvmTest --tests '*DefaultPromptBuilderTest' --tests '*MessagePipelineTranscriptTest' --tests '*RuntimePipelineTest'

Expected: PASS with explicit order and no duplicate recent-memory section.

- [ ] Step 5: Commit.

    git add core/src/commonMain/kotlin/io/openeden/prompt/BuiltPrompt.kt core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt
    git commit -m "refactor(prompt): separate stable and dynamic context blocks"

## Task 7: Serialize All Blocks In The OpenAI Responses Client

Files:
- Modify core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt
- Modify core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt

- [ ] Step 1: Add failing request-shape tests.

Assert exact input order: system, persona, frozen summary/chunks, dynamic Codebook/runtime, dynamic RAG, dynamic tail, system time within final dynamic ordering, then user. Test official host and custom relay; custom relay must have no prompt_cache_breakpoint. Change only tail and assert stable blocks and cache key remain unchanged.

- [ ] Step 2: Run and verify failure.

    .\gradlew.bat :core:jvmTest --tests '*OpenAiResponsesLlmClientTest'

Expected: failures while the client still emits one context item and places breakpoint on persona.

- [ ] Step 3: Implement block serialization and key derivation.

Emit one Responses input message per stable or dynamic block. Apply an explicit breakpoint only to the last stable block when promptCacheOptions is active and the provider branch permits it. Keep custom-provider AUTO without breakpoint and do not add automatic retry after a 5xx or partial stream.

Derive prompt_cache_key from model, stable system/persona/schema text, opaque cacheNamespace, and a fixed dialogue namespace label. Exclude tail, vectors, time, source text, raw session ID, cache epoch, and serializer revision. The key stays stable across ordinary turns and compaction epochs.

- [ ] Step 4: Remove full prompt logging.

Replace current buffered and streaming prompt logs with content-free segment fingerprints and token counts. Never log prompt bodies, memory text, user text, API keys, or authorization headers.

- [ ] Step 5: Run JVM client tests.

    .\gradlew.bat :core:jvmTest --tests '*OpenAiResponsesLlmClientTest'

Expected: PASS.

- [ ] Step 6: Commit.

    git add core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt core/src/jvmMain/kotlin/io/openeden/llm/OpenAiPromptCachingMode.kt
    git commit -m "feat(llm): serialize immutable prompt cache layers"

## Task 8: Add Compaction Epochs And Safe Fallback

Files:
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryCompactor.kt
- Create core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySummary.kt
- Modify core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryAssembler.kt
- Modify core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt
- Modify server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt
- Modify server/src/main/sqldelight/io/openeden/server/db/Transcript.sq
- Create core/src/commonTest/kotlin/io/openeden/transcript/PromptHistoryCompactorTest.kt
- Create server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightPromptHistoryCompactorTest.kt

- [ ] Step 1: Define summary generator boundary and failing tests.

Use:

    interface PromptHistorySummaryGenerator {
        suspend fun summarize(source: List<PromptHistoryChunk>): PromptHistorySummary
    }

Tests verify failed generation leaves the previous epoch active, successful generation increments cacheEpoch once, and the required recent tail is not compacted.

- [ ] Step 2: Implement epoch transitions.

Persist summary text, source chunk IDs, source turn IDs, summary fingerprint, generator revision, and creation time. Increment only after persistence and source-integrity verification. Keep old chunks until the new epoch is active.

- [ ] Step 3: Implement non-blocking fallback.

Run summary generation through the existing inference boundary or a dedicated suspend generator. On failure return the previous snapshot and emit history_compaction=FALLBACK. Never block the current response or delete transcript/memory rows.

- [ ] Step 4: Run tests and commit.

    .\gradlew.bat :core:jvmTest --tests '*PromptHistoryCompactorTest'
    .\gradlew.bat :server:test --tests '*SqlDelightPromptHistoryCompactorTest'

    git add core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryCompactor.kt core/src/commonMain/kotlin/io/openeden/transcript/PromptHistorySummary.kt core/src/commonMain/kotlin/io/openeden/transcript/PromptHistoryAssembler.kt core/src/commonMain/kotlin/io/openeden/transcript/TranscriptStore.kt server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightTranscriptStore.kt server/src/main/sqldelight/io/openeden/server/db/Transcript.sq core/src/commonTest/kotlin/io/openeden/transcript/PromptHistoryCompactorTest.kt server/src/test/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightPromptHistoryCompactorTest.kt
    git commit -m "feat(transcript): add safe prompt history compaction"

Expected: PASS for success, failure, restart, lineage, and tail preservation.

## Task 9: Add Context Diagnostics And Quality Gates

Files:
- Create core/src/commonMain/kotlin/io/openeden/prompt/PromptAssemblyMetrics.kt
- Modify core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt
- Modify core/src/commonMain/kotlin/io/openeden/llm/LlmCacheMetrics.kt
- Modify server/src/main/kotlin/io/openeden/server/api/dto/LlmCacheMetricsDto.kt if an existing endpoint exposes new fields
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Create core/src/commonTest/kotlin/io/openeden/prompt/PromptAssemblyMetricsTest.kt
- Modify core/src/commonTest/kotlin/io/openeden/llm/LlmCacheMetricsTest.kt
- Create core/src/commonTest/kotlin/io/openeden/runtime/pipeline/PromptQualityGateTest.kt

- [ ] Step 1: Define content-free metrics and failing aggregation tests.

PromptAssemblyMetrics contains segment fingerprints, token counts, cache epoch, candidate count, lineage exclusions, fingerprint exclusions, backfill depth, final unique count, underfill, and provider branch. Fingerprints are hashes only. Add providerMetricsAvailable to cache aggregation so missing usage is not interpreted as zero.

- [ ] Step 2: Add exact trace tags and instrumentation.

    const val ContextDedupLineage = "context_dedup=LINEAGE"
    const val ContextDedupFingerprint = "context_dedup=FINGERPRINT"
    const val ContextDedupUnderfilled = "context_dedup=UNDERFILLED"
    const val HistoryChunkSealed = "history_chunk=SEALED"
    const val HistoryCompactionCompleted = "history_compaction=COMPLETED"
    const val HistoryCompactionFallback = "history_compaction=FALLBACK"

Emit attributes through existing trace spans without prompt bodies.

- [ ] Step 3: Add replay quality gate.

Build old and new prompt inputs from the same fixture and assert equal Codebook/dynamic state, no decrease in required recent turns, no decrease in target RAG capacity when unique rows exist, and no remaining duplicate source turn/memory/fingerprint. Run existing grounding and schema validators.

- [ ] Step 4: Run complete relevant suites.

    .\gradlew.bat :core:jvmTest
    .\gradlew.bat :server:test

Expected: PASS.

- [ ] Step 5: Commit.

    git add core/src/commonMain/kotlin/io/openeden/prompt/PromptAssemblyMetrics.kt core/src/commonMain/kotlin/io/openeden/trace/TraceTag.kt core/src/commonMain/kotlin/io/openeden/llm/LlmCacheMetrics.kt server/src/main/kotlin/io/openeden/server/api/dto/LlmCacheMetricsDto.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/prompt/PromptAssemblyMetricsTest.kt core/src/commonTest/kotlin/io/openeden/llm/LlmCacheMetricsTest.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/PromptQualityGateTest.kt
    git commit -m "test(llm): add prompt cache quality diagnostics"

## Task 10: Feature-Flag Rollout And Final Verification

Files:
- Modify server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt
- Modify server/src/main/resources/application.yaml
- Modify core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
- Test the configuration and pipeline files found by the search
- Modify docs/superpowers/specs/2026-08-23-prompt-cache-context-deduplication-design.md when implementation details materially change the approved design

- [ ] Step 1: Add conservative flags.

Use separate flags for authoritative transcript recent context, lineage deduplication, immutable chunks, and compaction. Defaults preserve the existing prompt shape until each phase is enabled. Bound chunk token budget, tail turns, oversampling factor 3, and history budget.

- [ ] Step 2: Add shadow comparison.

Assemble old and new context from the same fixture, record only fingerprints and counts, and assert the quality gate before replacing the old assembler. Shadow mode must not call the provider or log prompt bodies.

- [ ] Step 3: Enable one-session canary behavior.

Verify cached tokens do not decrease, longest reused prefix grows beyond the previous 8,960-token boundary when chunks exist, total input and unique memory count do not decrease, underfill stays zero when enough candidates exist, no duplicate source IDs/fingerprints reach the final prompt, and custom relay requests omit prompt_cache_breakpoint.

- [ ] Step 4: Run final verification.

    .\gradlew.bat :core:jvmTest
    .\gradlew.bat :server:test
    .\gradlew.bat :server:build
    git diff --check
    git status --short

Expected: all relevant tests and build tasks pass; only pre-existing user files remain outside feature commits.

- [ ] Step 5: Commit rollout.

    git add core server docs/superpowers/specs/2026-08-23-prompt-cache-context-deduplication-design.md
    git commit -m "feat(runtime): enable cache-aware context assembly"
    git log -1 --format=%s

Expected title: feat(runtime): enable cache-aware context assembly.

## Plan Self-Review

- Tasks 4, 6, 9, and 10 enforce the hard quality constraint through backfill, required-tail checks, replay comparison, and final metrics.
- VQ-VAE, Codebook ordering, 8D-derived state, retrieval modes, diary behavior, and non-blocking inference remain preserved and are tested.
- Duplicate injection is handled at transcript authority and memory lineage boundaries; the renderer does not add a second ad hoc filter.
- Immutable chunks and epochs are persisted and tested across restart; epoch changes do not alter the stable cache key.
- Official-provider breakpoint behavior and custom-relay omission are covered by Task 7.
- Semantic similarity is never a destructive deduplication rule.
- All implementation steps specify concrete files, commands, expected outcomes, and commit boundaries.
