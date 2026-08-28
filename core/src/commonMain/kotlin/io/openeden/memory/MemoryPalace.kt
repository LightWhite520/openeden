package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.VectorMapping
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.trace.TraceTag
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class InMemoryMemoryPalace(
    private val inferenceExecutor: InferenceExecutor,
    private val maxResults: Int = 10,
    private val embeddingModel: MemoryEmbeddingModel = DeterministicMemoryEmbeddingModel,
    private val index: VectorIndex = RebuildableInMemoryVectorIndex(inferenceExecutor),
    private val utilityFilterConfig: MemoryUtilityFilterConfig = MemoryUtilityFilterConfig(),
    private val preExcludedTurnLineageIds: Set<String> = emptySet(),
) : MemoryStore {
    private val entries = mutableListOf<MemoryEntry>()

    override suspend fun write(entry: MemoryEntry): Set<String> {
        val existingIndex = entries.indexOfFirst { it.id == entry.id }
        if (existingIndex >= 0) {
            entries[existingIndex] = entry
        } else {
            entries += entry
        }
        index.insert(entry)
        return setOf(TraceTag.MemoryWritten)
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        inferenceExecutor.run {
            val querySemantic = embeddingModel.embed(request.userInput)
            val queryEmotion = embeddingModel.embed(request.currentVector)
            val positiveSkew = request.currentVector.copy(
                p = (request.currentVector.p + 0.3f).coerceAtMost(1.0f),
                v = (request.currentVector.v + 0.2f).coerceAtMost(1.0f),
            )
            val contrastTarget = VectorMapping.centerSymmetricTarget(request.currentVector, request.origin)
            val searchTargets = when (request.mode) {
                RetrievalMode.CONGRUENT -> listOf(request.currentVector)
                RetrievalMode.MIXED -> listOf(request.currentVector, positiveSkew)
                RetrievalMode.CONTRAST -> listOf(contrastTarget)
            }
            var overfetchLimit = initialRetrievalLimit()
            while (true) {
            val targetCandidatePools = buildList<List<MemoryEntry>> {
                for (emotionalTarget in searchTargets) {
                    val targetCandidates = buildList {
                        index.search(
                            VectorSearchRequest(
                                sessionId = request.sessionId,
                                incarnationId = request.incarnationId,
                                canonicalSubjectId = request.canonicalSubjectId,
                                operatorAuthorized = request.operatorAuthorized,
                                semanticEmbedding = querySemantic,
                                emotionalEmbedding = embeddingModel.embed(emotionalTarget),
                                limit = overfetchLimit,
                            ),
                        ).forEach { hit -> hit.entry?.let(::add) }
                    }
                    add(targetCandidates.filter { it.isVisibleTo(request) }.distinctBy { it.id })
                }
            }
            val congruentCandidates = targetCandidatePools.firstOrNull().orEmpty()
            val positiveCandidates = targetCandidatePools.getOrNull(1).orEmpty()
            val baselineEntropy = stableBaselineEntropy(request)
            val filtered = MemoryUtilityFilter.filter(
                candidates = congruentCandidates,
                querySemantic = querySemantic,
                queryEmotion = queryEmotion,
                baselineEntropy = baselineEntropy,
                config = utilityFilterConfig,
            )
            val recentRanked = entries.asReversed()
                .asSequence()
                .filter { it.isVisibleTo(request) }
                .take(overfetchLimit)
                .map(::snippet)
                .toList()
            var congruentCount = 0
            var positiveSkewCount = 0
            val exclusionTracker = ExclusionTracker(preExcludedTurnLineageIds)
            val selectedResult = when (request.mode) {
                RetrievalMode.CONGRUENT -> selectUnique(
                    ranked = rank(filtered.candidates, request, request.currentVector, overfetchLimit),
                    limit = maxResults,
                    exclusionContext = request.exclusionContext,
                    exclusionTracker = exclusionTracker,
                ).also {
                    congruentCount = it.selected.size
                }
                RetrievalMode.MIXED -> {
                    val positiveFiltered = MemoryUtilityFilter.filter(
                        candidates = positiveCandidates,
                        querySemantic = querySemantic,
                        queryEmotion = embeddingModel.embed(positiveSkew),
                        baselineEntropy = baselineEntropy,
                        config = utilityFilterConfig,
                    )
                    val congruentTarget = ceil(maxResults * 0.6).toInt()
                    val positiveTarget = floor(maxResults * 0.4).toInt()
                    val congruentRanked = rank(
                        filtered.candidates,
                        request,
                        request.currentVector,
                        overfetchLimit,
                    )
                    val positiveRanked = rank(
                        positiveFiltered.candidates,
                        request,
                        positiveSkew,
                        overfetchLimit,
                    )
                    val congruentResult = selectUnique(
                        ranked = congruentRanked,
                        limit = congruentTarget,
                        exclusionContext = request.exclusionContext,
                        exclusionTracker = exclusionTracker,
                    )
                    congruentCount = congruentResult.selected.size
                    val positiveResult = selectUnique(
                        ranked = positiveRanked,
                        limit = positiveTarget,
                        exclusionContext = request.exclusionContext,
                        alreadySelected = congruentResult.selected,
                        exclusionTracker = exclusionTracker,
                    )
                    positiveSkewCount = positiveResult.selected.size
                    var selectedResult = congruentResult + positiveResult
                    var remaining = maxResults - selectedResult.selected.size
                    if (remaining > 0 && congruentResult.selected.size < congruentTarget) {
                        val positiveFill = selectUnique(
                            ranked = positiveRanked,
                            limit = remaining,
                            backfillBaseline = positiveTarget,
                            exclusionContext = request.exclusionContext,
                            alreadySelected = selectedResult.selected,
                            exclusionTracker = exclusionTracker,
                        )
                        selectedResult += positiveFill
                        positiveSkewCount += positiveFill.selected.size
                        remaining = maxResults - selectedResult.selected.size
                    }
                    if (remaining > 0 && positiveResult.selected.size < positiveTarget) {
                        val congruentFill = selectUnique(
                            ranked = congruentRanked,
                            limit = remaining,
                            backfillBaseline = congruentTarget,
                            exclusionContext = request.exclusionContext,
                            alreadySelected = selectedResult.selected,
                            exclusionTracker = exclusionTracker,
                        )
                        selectedResult += congruentFill
                        congruentCount += congruentFill.selected.size
                    }
                    selectedResult
                }
                RetrievalMode.CONTRAST -> {
                    val contrastFiltered = MemoryUtilityFilter.filter(
                        candidates = congruentCandidates,
                        querySemantic = querySemantic,
                        queryEmotion = embeddingModel.embed(contrastTarget),
                        baselineEntropy = baselineEntropy,
                        config = utilityFilterConfig,
                    )
                    selectUnique(
                        ranked = rank(contrastFiltered.candidates, request, contrastTarget, overfetchLimit),
                        limit = maxResults,
                        exclusionContext = request.exclusionContext,
                        exclusionTracker = exclusionTracker,
                    )
                }
            }
            val selected = selectedResult.selected
            val recentSelection = selectUnique(
                ranked = recentRanked,
                limit = maxResults,
                exclusionContext = request.exclusionContext,
                alreadySelected = selected,
                exclusionTracker = exclusionTracker,
            )
            val recentMemories = recentSelection.selected.asReversed()
            val combinedSelection = selectedResult + recentSelection
            val backfillDepth = maxOf(recentSelection.backfillDepth, selectedResult.backfillDepth)
            val result = RetrievalResult(
                mode = request.mode,
                injectionLabel = RetrievalModeSelector.injectionLabel(request.mode),
                memories = selected,
                recentMemories = recentMemories,
                traceTags = buildSet {
                    if (selected.isNotEmpty() || recentMemories.isNotEmpty()) add(TraceTag.MemoryRetrieved)
                    if (selected.any { it.metadata.userId == request.userId }) add(TraceTag.IdentityAffinityApplied)
                    if (filtered.rejectedCount > 0) add(TraceTag.MemoryUtilityRejected)
                    if (filtered.degraded) add(TraceTag.MemoryUtilityDegraded)
                },
                congruentCount = congruentCount,
                positiveSkewCount = positiveSkewCount,
                filterAcceptedCount = filtered.acceptedCount,
                filterRejectedCount = filtered.rejectedCount,
                filterDegraded = filtered.degraded,
                diagnostics = combinedSelection.diagnostics(
                    underfilled = selected.size < maxResults,
                    exclusionTracker = exclusionTracker,
                ),
                backfillDepth = backfillDepth,
            )
            if (!result.underfilled || overfetchLimit >= entries.size) return@run result
            val nextLimit = minOf(entries.size, doubledDepth(overfetchLimit))
            if (nextLimit <= overfetchLimit) return@run result
            overfetchLimit = nextLimit
            }
            error("progressive retrieval loop terminated unexpectedly")
        }

    private fun initialRetrievalLimit(): Int {
        if (maxResults <= 0) return 0
        return minOf(entries.size, saturatingMultiply(maxResults, 3))
    }

    private fun doubledDepth(depth: Int): Int = saturatingMultiply(depth, 2)

    private fun saturatingMultiply(value: Int, multiplier: Int): Int =
        if (value > Int.MAX_VALUE / multiplier) Int.MAX_VALUE else value * multiplier

    private fun stableBaselineEntropy(request: RetrievalRequest): Float? {
        val entropies = FloatArray(utilityFilterConfig.baselineWindow)
        var count = 0
        for (entry in entries.asReversed()) {
            if (!entry.isVisibleTo(request) || "daily" !in entry.tags || "stable" !in entry.tags) continue
            val entropy = MemoryUtilityFilter.meanEmbeddingEntropy(entry)
            if (!entropy.isFinite()) return Float.NaN
            entropies[count] = entropy
            count += 1
            if (count == utilityFilterConfig.baselineWindow) break
        }
        if (count == 0) return null
        var sum = 0.0
        for (index in count - 1 downTo 0) sum += entropies[index]
        return (sum / count).toFloat()
    }

    private fun snippet(entry: MemoryEntry): MemorySnippet = MemorySnippet(
        id = entry.id,
        content = entry.content,
        metadata = entry.metadata,
        createdAtMs = entry.createdAtMs,
    )

    private fun selectUnique(
        ranked: List<MemorySnippet>,
        limit: Int,
        backfillBaseline: Int = limit,
        exclusionContext: MemoryExclusionContext,
        alreadySelected: List<MemorySnippet> = emptyList(),
        exclusionTracker: ExclusionTracker,
    ): SelectionResult {
        if (limit <= 0) return SelectionResult()
        val selected = alreadySelected.toMutableList()
        val result = mutableListOf<MemorySnippet>()
        var backfillDepth = 0
        val backfilled = mutableSetOf<String>()
        val seenIds = alreadySelected.mapTo(hashSetOf()) { it.id }
        for ((rankIndex, candidate) in ranked.withIndex()) {
            if (candidate.id in seenIds) continue
            val turnLineageOverlap = exclusionContext.excludesTurnLineage(candidate.metadata.lineage) ||
                selected.any { selectedCandidate ->
                    candidate.metadata.lineage.sharesSourceTurnWith(selectedCandidate.metadata.lineage)
            }
            if (turnLineageOverlap) {
                exclusionTracker.exclude(candidate.id, ExclusionReason.TURN_LINEAGE)
                continue
            }
            val memoryLineageOverlap = exclusionContext.excludesMemoryLineage(candidate.id, candidate.metadata.lineage) ||
                selected.any { selectedCandidate ->
                    candidate.metadata.lineage.sharesSourceMemoryWith(
                        memoryId = candidate.id,
                        otherMemoryId = selectedCandidate.id,
                        other = selectedCandidate.metadata.lineage,
                    )
            }
            if (memoryLineageOverlap) {
                exclusionTracker.exclude(candidate.id, ExclusionReason.MEMORY_LINEAGE)
                continue
            }
            val fingerprintOverlap = candidate.metadata.contentFingerprint?.let { fingerprint ->
                fingerprint in exclusionContext.contentFingerprints ||
                    selected.any { it.metadata.contentFingerprint == fingerprint }
            } == true
            if (fingerprintOverlap) {
                exclusionTracker.exclude(candidate.id, ExclusionReason.FINGERPRINT)
                continue
            }
            result += candidate
            selected += candidate
            seenIds += candidate.id
            if (rankIndex >= backfillBaseline) {
                backfilled += candidate.id
                backfillDepth = maxOf(backfillDepth, rankIndex + 1 - backfillBaseline)
            }
            if (result.size == limit) break
        }
        return SelectionResult(
            selected = result,
            backfilled = backfilled,
            backfillDepth = backfillDepth,
        )
    }

    private data class SelectionResult(
        val selected: List<MemorySnippet> = emptyList(),
        val backfilled: Set<String> = emptySet(),
        val backfillDepth: Int = 0,
    ) {
        operator fun plus(other: SelectionResult): SelectionResult = SelectionResult(
            selected = selected + other.selected,
            backfilled = backfilled + other.backfilled,
            backfillDepth = maxOf(backfillDepth, other.backfillDepth),
        )

        fun diagnostics(
            underfilled: Boolean,
            exclusionTracker: ExclusionTracker,
        ): RetrievalDiagnostics = RetrievalDiagnostics(
            excludedByTurnLineage = exclusionTracker.count(ExclusionReason.TURN_LINEAGE),
            excludedByMemoryLineage = exclusionTracker.count(ExclusionReason.MEMORY_LINEAGE),
            excludedByFingerprint = exclusionTracker.count(ExclusionReason.FINGERPRINT),
            backfilled = backfilled.size,
            underfilled = underfilled,
        )
    }

    private class ExclusionTracker(preExcludedTurnLineageIds: Set<String>) {
        private val reasons = preExcludedTurnLineageIds.associateWithTo(linkedMapOf()) {
            ExclusionReason.TURN_LINEAGE
        }

        fun exclude(candidateId: String, reason: ExclusionReason) {
            reasons.putIfAbsent(candidateId, reason)
        }

        fun count(reason: ExclusionReason): Int = reasons.values.count { it == reason }
    }

    private enum class ExclusionReason {
        TURN_LINEAGE,
        MEMORY_LINEAGE,
        FINGERPRINT,
    }

    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> =
        if (sessionId.isBlank()) emptyList() else inferenceExecutor.run {
            entries.asReversed()
                .asSequence()
                .filter { it.metadata.incarnationId == sessionId }
                .filter { "daily" in it.tags && "stable" in it.tags }
                .take(limit)
                .map { it.metadata.snapshot8D }
            .toList()
        }

    override suspend fun recent(sessionId: String, limit: Int): List<MemorySnippet> =
        entries.asReversed()
            .asSequence()
            .filter { it.sessionId == sessionId }
            .take(limit.coerceAtLeast(0))
            .map { entry ->
                MemorySnippet(
                    id = entry.id,
                    content = entry.content,
                    metadata = entry.metadata,
                    createdAtMs = entry.createdAtMs,
                )
            }
            .toList()
            .asReversed()

    private suspend fun rank(
        candidates: List<MemoryEntry>,
        request: RetrievalRequest,
        emotionalTarget: BioVector,
        limit: Int,
    ): List<MemorySnippet> {
        if (limit <= 0) return emptyList()
        val querySemantic = embeddingModel.embed(request.userInput)
        val queryEmotion = embeddingModel.embed(emotionalTarget)
        val beta = emotionalWeight(request.currentVector)
        val alpha = 1.0f - beta
        return candidates
            .asSequence()
            .map { entry ->
                val semantic = cosine(querySemantic, entry.semanticEmbedding)
                val emotion = cosine(queryEmotion, entry.emotionalEmbedding)
                val momentum = momentumImpact(entry.metadata)
                val identityAffinity = identityAffinity(entry, request.userId)
                val score = alpha * semantic + beta * emotion + 0.15f * momentum + identityAffinity
                MemorySnippet(
                    id = entry.id,
                    content = entry.content,
                    metadata = entry.metadata,
                    createdAtMs = entry.createdAtMs,
                    score = score,
                )
            }
            .sortedByDescending { it.score }
            .toList()
    }

    companion object {
        fun embedText(text: String, dimensions: Int = 16): List<Float> {
            val buckets = FloatArray(dimensions)
            for ((index, char) in text.withIndex()) {
                val bucket = (char.code + index).mod(dimensions)
                buckets[bucket] += 1.0f
            }
            return normalize(buckets)
        }

        fun embedVector(vector: BioVector): List<Float> = vector.toList()

        private fun emotionalWeight(vector: BioVector): Float {
            val stress = maxOf(vector.p, vector.s)
            return if (stress > 0.6f) 0.7f else 0.4f
        }

        private fun momentumImpact(metadata: MemoryMetadata): Float {
            val delta = metadata.deltaVec
            return (abs(delta.p) + abs(delta.v)).coerceIn(0.0f, 1.0f)
        }

        private fun identityAffinity(entry: MemoryEntry, userId: String): Float {
            if (userId.isBlank() || entry.metadata.userId != userId) return 0.0f
            return when (entry.room) {
                MemoryRoom.PROFILE_ROOM -> 0.12f
                MemoryRoom.EVENT_ROOM -> 0.06f
                else -> 0.02f
            }
        }

        private fun normalize(values: FloatArray): List<Float> {
            var norm = 0.0f
            for (value in values) norm += value * value
            val denominator = sqrt(norm)
            if (denominator == 0.0f) return values.toList()
            return values.map { it / denominator }
        }

        private fun cosine(left: List<Float>, right: List<Float>): Float {
            val size = minOf(left.size, right.size)
            if (size == 0) return 0.0f
            var dot = 0.0f
            var leftNorm = 0.0f
            var rightNorm = 0.0f
            for (index in 0 until size) {
                dot += left[index] * right[index]
                leftNorm += left[index] * left[index]
                rightNorm += right[index] * right[index]
            }
            val denominator = sqrt(leftNorm) * sqrt(rightNorm)
            return if (denominator == 0.0f) 0.0f else dot / denominator
        }
    }
}
