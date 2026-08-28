package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.runtime.inference.DirectInferenceExecutor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryContextDeduplicationTest {
    @Test
    fun `raw and narrative entries sharing a source turn are injected once`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        palace.write(entry("raw", "raw event", kind = MemoryKind.RAW, sourceTurnIds = listOf("turn-1")))
        palace.write(entry("narrative", "narrative event", kind = MemoryKind.NARRATIVE, sourceTurnIds = listOf("turn-1")))
        palace.write(entry("other", "independent event"))

        val result = palace.retrieve(request())

        assertEquals(2, result.memories.size)
        assertEquals(2, result.memories.map { it.id }.toSet().size)
        assertTrue(result.memories.map { it.id }.contains("other"))
        assertEquals(1, result.memories.count { it.id == "raw" || it.id == "narrative" })
        assertTrue(result.lineageExcludedCount >= 1)
        assertFalse(result.underfilled)
    }

    @Test
    fun `exact legacy duplicate is replaced by a deeper candidate`() = runTest {
        val fingerprint = "v1:sha-256:duplicate"
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        val rankedEmbedding = InMemoryMemoryPalace.embedText("event")
        palace.write(entry("legacy-a", "same legacy content", contentFingerprint = fingerprint, semanticEmbedding = rankedEmbedding))
        palace.write(entry("legacy-b", "same legacy content", contentFingerprint = fingerprint, semanticEmbedding = rankedEmbedding))
        palace.write(entry("unique", "different content", semanticEmbedding = rankedEmbedding))

        val result = palace.retrieve(request())

        assertEquals(2, result.memories.size)
        assertEquals(setOf("legacy-a", "unique"), result.memories.map { it.id }.toSet())
        assertTrue(result.fingerprintExcludedCount >= 1)
        assertTrue(result.backfillDepth > 0)
    }

    @Test
    fun `entries without lineage remain available even with matching semantic scores`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        palace.write(entry("unrelated-a", "same semantic text"))
        palace.write(entry("unrelated-b", "same semantic text"))

        val result = palace.retrieve(request(userInput = "same semantic"))

        assertEquals(setOf("unrelated-a", "unrelated-b"), result.memories.map { it.id }.toSet())
        assertEquals(0, result.lineageExcludedCount)
        assertEquals(0, result.fingerprintExcludedCount)
    }

    @Test
    fun `external lineage and fingerprint exclusions backfill to full capacity`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        palace.write(entry("transcript-copy", "old turn", sourceTurnIds = listOf("turn-1")))
        palace.write(entry("legacy-copy", "legacy", contentFingerprint = "v1:sha-256:already-seen"))
        palace.write(entry("fresh-a", "fresh a"))
        palace.write(entry("fresh-b", "fresh b"))

        val result = palace.retrieve(
            request().copy(
                exclusionContext = MemoryExclusionContext(
                    sourceTurnIds = setOf("turn-1"),
                    contentFingerprints = setOf("v1:sha-256:already-seen"),
                ),
            ),
        )

        assertEquals(setOf("fresh-a", "fresh-b"), result.memories.map { it.id }.toSet())
        assertTrue(result.lineageExcludedCount >= 1)
        assertTrue(result.fingerprintExcludedCount >= 1)
        assertFalse(result.underfilled)
    }

    @Test
    fun `recent lineage is excluded and lower ranked unique memory backfills capacity`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 3)
        val rankedEmbedding = InMemoryMemoryPalace.embedText("event")
        repeat(10) { index ->
            palace.write(
                entry(
                    "recent-copy-$index",
                    "event",
                    sourceTurnIds = listOf("t2"),
                    semanticEmbedding = rankedEmbedding,
                ),
            )
        }
        palace.write(entry("unique-a", "event", sourceTurnIds = listOf("t3"), semanticEmbedding = rankedEmbedding))
        palace.write(entry("unique-b", "event", sourceTurnIds = listOf("t4"), semanticEmbedding = rankedEmbedding))
        palace.write(entry("unique-c", "event", sourceTurnIds = listOf("t5"), semanticEmbedding = rankedEmbedding))

        val result = palace.retrieve(
            request().copy(
                exclusionContext = MemoryExclusionContext(sourceTurnIds = setOf("t2")),
            ),
        )

        assertEquals(3, result.memories.size)
        assertTrue(result.memories.none { "t2" in it.metadata.lineage.sourceTurnIds })
        assertEquals(
            emptySet(),
            result.memories.flatMap { it.metadata.lineage.sourceTurnIds }.toSet() intersect setOf("t2"),
        )
        assertEquals(10, result.diagnostics.excludedByTurnLineage)
        assertEquals(3, result.diagnostics.backfilled)
        assertFalse(result.diagnostics.underfilled)
    }

    @Test
    fun `diagnostics attribute overlap to turn then memory then fingerprint`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 3)
        val rankedEmbedding = InMemoryMemoryPalace.embedText("event")
        val excludedFingerprint = "v1:sha-256:excluded"
        palace.write(
            entry(
                "turn-first",
                "event",
                sourceTurnIds = listOf("turn-excluded"),
                sourceMemoryIds = listOf("memory-excluded"),
                contentFingerprint = excludedFingerprint,
                semanticEmbedding = rankedEmbedding,
            ),
        )
        palace.write(
            entry(
                "memory-second",
                "event",
                sourceMemoryIds = listOf("memory-excluded"),
                contentFingerprint = excludedFingerprint,
                semanticEmbedding = rankedEmbedding,
            ),
        )
        palace.write(
            entry(
                "fingerprint-third",
                "event",
                contentFingerprint = excludedFingerprint,
                semanticEmbedding = rankedEmbedding,
            ),
        )
        repeat(3) { index ->
            palace.write(entry("unique-$index", "event", semanticEmbedding = rankedEmbedding))
        }

        val result = palace.retrieve(
            request().copy(
                exclusionContext = MemoryExclusionContext(
                    sourceTurnIds = setOf("turn-excluded"),
                    sourceMemoryIds = setOf("memory-excluded"),
                    contentFingerprints = setOf(excludedFingerprint),
                ),
            ),
        )

        assertEquals(setOf("unique-0", "unique-1", "unique-2"), result.memories.mapTo(hashSetOf()) { it.id })
        assertEquals(1, result.diagnostics.excludedByTurnLineage)
        assertEquals(1, result.diagnostics.excludedByMemoryLineage)
        assertEquals(1, result.diagnostics.excludedByFingerprint)
        assertEquals(3, result.diagnostics.backfilled)
        assertFalse(result.diagnostics.underfilled)
    }

    @Test
    fun `mixed mode preserves six forty lane target with unique candidates`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor)
        repeat(10) { index -> palace.write(entry("mixed-$index", "same text $index")) }

        val result = palace.retrieve(
            request(userInput = "same text", mode = RetrievalMode.MIXED),
        )

        assertEquals(10, result.memories.size)
        assertEquals(6, result.congruentCount)
        assertEquals(4, result.positiveSkewCount)
        assertFalse(result.underfilled)
    }

    @Test
    fun `mixed mode does not exceed positive target when congruent lane is exhausted`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor)
        palace.write(
            entry(
                id = "excluded-congruent",
                content = "unrelated",
                sourceTurnIds = listOf("excluded-congruent-turn"),
            ),
        )
        repeat(5) { index ->
            palace.write(
                entry(
                    id = "positive-only-$index",
                    content = "positive-only $index",
                    emotionalEmbedding = listOf(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f),
                    semanticEmbedding = List(16) { 0.0f },
                ),
            )
        }

        val result = palace.retrieve(
            request(userInput = "unrelated", mode = RetrievalMode.MIXED).copy(
                exclusionContext = MemoryExclusionContext(
                    sourceTurnIds = setOf("excluded-congruent-turn"),
                ),
            ),
        )

        assertEquals(0, result.congruentCount)
        assertEquals(5, result.positiveSkewCount)
        assertEquals(5, result.memories.size)
        assertTrue(result.lineageExcludedCount >= 1)
        assertTrue(result.underfilled)
        assertTrue(result.backfillDepth > 0)
    }

    @Test
    fun `normal retrieval ranks only the initial progressive depth when it fills capacity`() = runTest {
        val index = RecordingVectorIndex()
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            index = index,
        )
        repeat(100) { indexValue -> palace.write(entry("deep-$indexValue", "deep candidate $indexValue")) }

        palace.retrieve(request(userInput = "deep", mode = RetrievalMode.MIXED))

        assertEquals(listOf(30, 30), index.requests.map { it.limit })
        assertTrue(index.requests[0].emotionalEmbedding != index.requests[1].emotionalEmbedding)

        index.requests.clear()
        palace.retrieve(
            request(mode = RetrievalMode.CONTRAST).copy(
                currentVector = BioVector.Neutral.copy(p = 0.1f, v = 0.2f),
            ),
        )

        assertEquals(listOf(30), index.requests.map { it.limit })
    }

    @Test
    fun `retrieval deepens progressively until exclusions backfill or corpus exhausts`() = runTest {
        val index = RecordingVectorIndex()
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            maxResults = 3,
            index = index,
        )
        repeat(35) { candidate ->
            palace.write(
                entry(
                    id = "excluded-$candidate",
                    content = "event",
                    sourceTurnIds = listOf("excluded-turn-$candidate"),
                    semanticEmbedding = InMemoryMemoryPalace.embedText("event"),
                ),
            )
        }
        repeat(3) { candidate ->
            palace.write(
                entry(
                    id = "valid-$candidate",
                    content = "event",
                    semanticEmbedding = InMemoryMemoryPalace.embedText("event"),
                ),
            )
        }

        val result = palace.retrieve(
            request().copy(
                exclusionContext = MemoryExclusionContext(
                    sourceTurnIds = (0 until 35).mapTo(hashSetOf()) { "excluded-turn-$it" },
                ),
            ),
        )

        assertEquals(listOf(9, 18, 36, 38), index.requests.map { it.limit })
        assertEquals(setOf("valid-0", "valid-1", "valid-2"), result.memories.mapTo(hashSetOf()) { it.id })
        assertFalse(result.underfilled)
    }

    @Test
    fun `candidate diagnostics use one first reason across mixed lanes and recent selection`() = runTest {
        val fingerprint = "v1:sha-256:shared"
        val rankedEmbedding = InMemoryMemoryPalace.embedText("event")
        val shared = entry(
            id = "shared-candidate",
            content = "shared",
            sourceTurnIds = listOf("shared-turn"),
            contentFingerprint = fingerprint,
            semanticEmbedding = rankedEmbedding,
        )
        val selected = entry(
            id = "selected-lineage",
            content = "selected",
            sourceTurnIds = listOf("shared-turn"),
            semanticEmbedding = rankedEmbedding,
        )
        val fillers = (0 until 12).map { entry("filler-$it", "event", semanticEmbedding = rankedEmbedding) }
        val index = OrderedLaneVectorIndex(listOf(shared, selected) + fillers)
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            index = index,
        )
        (listOf(shared, selected) + fillers).forEach { palace.write(it) }

        val result = palace.retrieve(
            request(mode = RetrievalMode.MIXED).copy(
                exclusionContext = MemoryExclusionContext(contentFingerprints = setOf(fingerprint)),
            ),
        )

        assertEquals(1, result.diagnostics.excludedByFingerprint)
        assertEquals(0, result.diagnostics.excludedByTurnLineage)
        assertEquals(1, result.lineageExcludedCount + result.diagnostics.excludedByFingerprint)
    }

    @Test
    fun `mixed lanes only select from their own emotional target pools`() = runTest {
        val embeddingModel = object : MemoryEmbeddingModel {
            override suspend fun embed(text: String): List<Float> = listOf(0.0f, 1.0f)

            override suspend fun embed(vector: BioVector): List<Float> =
                if (vector.p > 0.7f) listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f)
        }
        val congruentEntries = (0 until 30).map { index ->
            entry(
                id = "congruent-source-$index",
                content = "congruent source $index",
                semanticEmbedding = listOf(1.0f, 0.0f),
                emotionalEmbedding = listOf(0.0f, 1.0f),
            )
        }
        val positiveEntries = (0 until 30).map { index ->
            entry(
                id = "positive-source-$index",
                content = "positive source $index",
                semanticEmbedding = listOf(0.0f, 1.0f),
                emotionalEmbedding = listOf(1.0f, 0.0f),
            )
        }
        val index = SourcePartitioningVectorIndex(congruentEntries, positiveEntries)
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            embeddingModel = embeddingModel,
            index = index,
        )
        (congruentEntries + positiveEntries).forEach { palace.write(it) }

        val result = palace.retrieve(request(userInput = "query", mode = RetrievalMode.MIXED))

        assertEquals(6, result.congruentCount)
        assertEquals(4, result.positiveSkewCount)
        assertTrue(result.memories.take(6).all { it.id.startsWith("congruent-source-") })
        assertTrue(result.memories.takeLast(4).all { it.id.startsWith("positive-source-") })
    }

    @Test
    fun `mixed lane exclusions backfill within each lane without changing quotas`() = runTest {
        val embeddingModel = object : MemoryEmbeddingModel {
            override suspend fun embed(text: String): List<Float> = listOf(0.0f, 1.0f)

            override suspend fun embed(vector: BioVector): List<Float> =
                if (vector.p > 0.7f) listOf(1.0f, 0.0f) else listOf(0.0f, 1.0f)
        }
        val congruentEntries = (0 until 38).map { index ->
            entry(
                id = "congruent-$index",
                content = "congruent $index",
                sourceTurnIds = if (index < 32) listOf("excluded-congruent-$index") else emptyList(),
                semanticEmbedding = listOf(1.0f, 0.0f),
                emotionalEmbedding = listOf(0.0f, 1.0f),
            )
        }
        val positiveEntries = (0 until 36).map { index ->
            entry(
                id = "positive-$index",
                content = "positive $index",
                sourceTurnIds = if (index < 32) listOf("excluded-positive-$index") else emptyList(),
                semanticEmbedding = listOf(0.0f, 1.0f),
                emotionalEmbedding = listOf(1.0f, 0.0f),
            )
        }
        val palace = InMemoryMemoryPalace(
            inferenceExecutor = DirectInferenceExecutor,
            embeddingModel = embeddingModel,
            index = SourcePartitioningVectorIndex(congruentEntries, positiveEntries),
        )
        (congruentEntries + positiveEntries).forEach { palace.write(it) }

        val result = palace.retrieve(
            request(userInput = "query", mode = RetrievalMode.MIXED).copy(
                exclusionContext = MemoryExclusionContext(
                    sourceTurnIds = buildSet {
                        repeat(32) { index ->
                            add("excluded-congruent-$index")
                            add("excluded-positive-$index")
                        }
                    },
                ),
            ),
        )

        assertEquals(10, result.memories.size)
        assertEquals(6, result.congruentCount)
        assertEquals(4, result.positiveSkewCount)
        assertTrue(result.memories.take(6).all { it.id.startsWith("congruent-") })
        assertTrue(result.memories.takeLast(4).all { it.id.startsWith("positive-") })
        assertEquals(64, result.diagnostics.excludedByTurnLineage)
        assertEquals(10, result.diagnostics.backfilled)
        assertFalse(result.diagnostics.underfilled)
    }

    @Test
    fun `underfilled reports when exclusions exhaust available unique candidates`() = runTest {
        val palace = InMemoryMemoryPalace(DirectInferenceExecutor, maxResults = 2)
        palace.write(entry("seen", "seen", sourceTurnIds = listOf("turn-1")))

        val result = palace.retrieve(
            request().copy(
                exclusionContext = MemoryExclusionContext(sourceTurnIds = setOf("turn-1")),
            ),
        )

        assertTrue(result.memories.isEmpty())
        assertTrue(result.underfilled)
        assertEquals(0, result.memories.size)
    }

    @Test
    fun `stable entropy baseline matches the latest authorized window`() = runTest {
        val candidate = entry(
            id = "candidate",
            content = "candidate",
            semanticEmbedding = listOf(0.98f, 0.02f),
            emotionalEmbedding = listOf(0.98f, 0.02f),
        )
        val recentStable = listOf(
            entry(
                id = "recent-stable-1",
                content = "recent stable 1",
                tags = setOf("daily", "stable"),
                semanticEmbedding = listOf(1.0f, 0.0f),
                emotionalEmbedding = listOf(1.0f, 0.0f),
            ),
            entry(
                id = "recent-stable-2",
                content = "recent stable 2",
                tags = setOf("daily", "stable"),
                semanticEmbedding = listOf(1.0f, 0.0f),
                emotionalEmbedding = listOf(1.0f, 0.0f),
            ),
        )
        val config = MemoryUtilityFilterConfig(
            minSemanticSimilarity = -1.0f,
            minEmotionalSimilarity = -1.0f,
            entropyTolerance = 0.0f,
            baselineWindow = 2,
        )
        suspend fun retrieve(stableEntries: List<MemoryEntry>): RetrievalResult {
            val palace = InMemoryMemoryPalace(
                inferenceExecutor = DirectInferenceExecutor,
                maxResults = 1,
                index = FixedCandidateVectorIndex(candidate),
                utilityFilterConfig = config,
            )
            (stableEntries + candidate).forEach { palace.write(it) }
            return palace.retrieve(request(userInput = "candidate"))
        }
        val reference = retrieve(recentStable)
        val expanded = retrieve(
            listOf(
                entry(
                    id = "older-stable",
                    content = "older stable",
                    tags = setOf("daily", "stable"),
                    semanticEmbedding = listOf(0.5f, 0.5f),
                    emotionalEmbedding = listOf(0.5f, 0.5f),
                ),
            ) + recentStable + entry(
                id = "unauthorized-newest-stable",
                content = "unauthorized newest stable",
                tags = setOf("daily", "stable"),
                visibility = MemoryVisibility.PrivateSubject("other-subject"),
                semanticEmbedding = listOf(0.5f, 0.5f),
                emotionalEmbedding = listOf(0.5f, 0.5f),
            ),
        )

        assertTrue(reference.memories.isEmpty())
        assertEquals(reference.memories, expanded.memories)
        assertEquals(1, reference.filterRejectedCount)
        assertEquals(reference.filterRejectedCount, expanded.filterRejectedCount)
        assertEquals(reference.filterDegraded, expanded.filterDegraded)
    }

    private fun request(
        userInput: String = "event",
        mode: RetrievalMode = RetrievalMode.CONGRUENT,
    ) = RetrievalRequest(
        sessionId = "CLI:u1",
        userInput = userInput,
        currentVector = BioVector.Neutral,
        origin = BioVector.Neutral,
        mode = mode,
        incarnationId = "incarnation-1",
    )

    private fun entry(
        id: String,
        content: String,
        kind: MemoryKind = MemoryKind.RAW,
        sourceTurnIds: List<String> = emptyList(),
        sourceMemoryIds: List<String> = emptyList(),
        contentFingerprint: String? = null,
        tags: Set<String> = emptySet(),
        visibility: MemoryVisibility = MemoryVisibility.ScopeShared("CLI:u1"),
        semanticEmbedding: List<Float> = InMemoryMemoryPalace.embedText(content),
        emotionalEmbedding: List<Float> = InMemoryMemoryPalace.embedVector(BioVector.Neutral),
    ) = MemoryEntry(
        id = id,
        sessionId = "CLI:u1",
        content = content,
        room = MemoryRoom.EVENT_ROOM,
        kind = kind,
        tags = tags,
        semanticEmbedding = semanticEmbedding,
        emotionalEmbedding = emotionalEmbedding,
        metadata = MemoryMetadata(
            snapshot8D = BioVector.Neutral,
            omegaState = 0.1f,
            deltaVec = VectorDelta.Zero,
            snapshotOrigin = BioVector.Neutral,
            userId = "user-1",
            incarnationId = "incarnation-1",
            sourceSessionId = "CLI:u1",
            visibility = visibility,
            lineage = MemoryLineage(
                sourceTurnIds = sourceTurnIds,
                sourceMemoryIds = sourceMemoryIds,
            ),
            contentFingerprint = contentFingerprint,
        ),
    )

    private class RecordingVectorIndex : VectorIndex {
        val requests = mutableListOf<VectorSearchRequest>()
        private val entries = linkedMapOf<String, MemoryEntry>()

        override suspend fun insert(entry: MemoryEntry) {
            entries[entry.id] = entry
        }

        override suspend fun remove(memoryId: String) {
            entries.remove(memoryId)
        }

        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) {
            this.entries.clear()
            entries.forEach { entry -> this.entries[entry.id] = entry }
        }

        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            requests += request
            return entries.values
                .filter { it.sessionId == request.sessionId }
                .take(request.limit)
                .map { entry -> VectorSearchHit(entry.id, entry, 1.0f, 1.0f) }
        }

        override suspend fun markDirty() = Unit
    }

    private class SourcePartitioningVectorIndex(
        private val congruentEntries: List<MemoryEntry>,
        private val positiveEntries: List<MemoryEntry>,
    ) : VectorIndex {
        override suspend fun insert(entry: MemoryEntry) = Unit

        override suspend fun remove(memoryId: String) = Unit

        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit

        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> {
            val pool = if (request.emotionalEmbedding?.firstOrNull() == 1.0f) {
                positiveEntries
            } else {
                congruentEntries
            }
            return pool.take(request.limit).map { entry ->
                VectorSearchHit(entry.id, entry, 1.0f, 1.0f)
            }
        }

        override suspend fun markDirty() = Unit
    }

    private class OrderedLaneVectorIndex(
        private val entries: List<MemoryEntry>,
    ) : VectorIndex {
        override suspend fun insert(entry: MemoryEntry) = Unit
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> =
            entries.take(request.limit).map { VectorSearchHit(it.id, it, 1.0f, 1.0f) }
        override suspend fun markDirty() = Unit
    }

    private class FixedCandidateVectorIndex(
        private val candidate: MemoryEntry,
    ) : VectorIndex {
        override suspend fun insert(entry: MemoryEntry) = Unit
        override suspend fun remove(memoryId: String) = Unit
        override suspend fun rebuild(entries: Iterable<MemoryEntry>, batchSize: Int) = Unit
        override suspend fun search(request: VectorSearchRequest): List<VectorSearchHit> =
            if (request.limit <= 0) emptyList() else listOf(
                VectorSearchHit(candidate.id, candidate, 1.0f, 1.0f),
            )
        override suspend fun markDirty() = Unit
    }
}
