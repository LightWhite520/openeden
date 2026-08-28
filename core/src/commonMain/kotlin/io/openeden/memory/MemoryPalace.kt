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
) : MemoryStore {
    private val entries = mutableListOf<MemoryEntry>()

    override suspend fun write(entry: MemoryEntry): Set<String> {
        entries += entry
        index.insert(entry)
        return setOf(TraceTag.MemoryWritten)
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        inferenceExecutor.run {
            val overfetchLimit = retrievalLimit()
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
            val baselineEntropy = entries.asSequence()
                .filter { it.isVisibleTo(request) && "daily" in it.tags && "stable" in it.tags }
                .toList()
                .takeLast(utilityFilterConfig.baselineWindow)
                .map { MemoryUtilityFilter.meanEmbeddingEntropy(it) }
                .toList()
                .let { values ->
                    when {
                        values.isEmpty() -> null
                        values.any { !it.isFinite() } -> Float.NaN
                        else -> values.average().toFloat()
                    }
                }
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
            val selectedResult = when (request.mode) {
                RetrievalMode.CONGRUENT -> selectUnique(
                    ranked = rank(filtered.candidates, request, request.currentVector, overfetchLimit),
                    limit = maxResults,
                    exclusionContext = request.exclusionContext,
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
                    )
                    congruentCount = congruentResult.selected.size
                    val positiveResult = selectUnique(
                        ranked = positiveRanked,
                        limit = positiveTarget,
                        exclusionContext = request.exclusionContext,
                        alreadySelected = congruentResult.selected,
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
                    )
                }
            }
            val selected = selectedResult.selected
            val recentSelection = selectUnique(
                ranked = recentRanked,
                limit = maxResults,
                exclusionContext = request.exclusionContext,
                alreadySelected = selected,
            )
            val recentMemories = recentSelection.selected.asReversed()
            val lineageExcludedCount = recentSelection.lineageExcludedCount + selectedResult.lineageExcludedCount
            val fingerprintExcludedCount = recentSelection.fingerprintExcludedCount + selectedResult.fingerprintExcludedCount
            val backfillDepth = maxOf(recentSelection.backfillDepth, selectedResult.backfillDepth)
            RetrievalResult(
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
                lineageExcludedCount = lineageExcludedCount,
                fingerprintExcludedCount = fingerprintExcludedCount,
                backfillDepth = backfillDepth,
                underfilled = selected.size < maxResults,
            )
        }

    private fun retrievalLimit(): Int {
        if (maxResults <= 0) return 0
        val triple = if (maxResults > Int.MAX_VALUE / 3) Int.MAX_VALUE else maxResults * 3
        return maxOf(maxResults, triple).coerceAtMost(entries.size)
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
    ): SelectionResult {
        if (limit <= 0) return SelectionResult()
        val selected = alreadySelected.toMutableList()
        val result = mutableListOf<MemorySnippet>()
        var lineageExcludedCount = 0
        var fingerprintExcludedCount = 0
        var backfillDepth = 0
        val seenIds = alreadySelected.mapTo(hashSetOf()) { it.id }
        for ((rankIndex, candidate) in ranked.withIndex()) {
            if (candidate.id in seenIds) continue
            val lineageOverlap = overlapsLineage(candidate, exclusionContext) ||
                selected.any { selectedCandidate -> overlapsLineage(candidate, selectedCandidate) }
            if (lineageOverlap) {
                lineageExcludedCount += 1
                continue
            }
            val fingerprintOverlap = candidate.metadata.contentFingerprint?.let { fingerprint ->
                fingerprint in exclusionContext.contentFingerprints ||
                    selected.any { it.metadata.contentFingerprint == fingerprint }
            } == true
            if (fingerprintOverlap) {
                fingerprintExcludedCount += 1
                continue
            }
            result += candidate
            selected += candidate
            seenIds += candidate.id
            if (rankIndex >= backfillBaseline) {
                backfillDepth = maxOf(backfillDepth, rankIndex + 1 - backfillBaseline)
            }
            if (result.size == limit) break
        }
        return SelectionResult(result, lineageExcludedCount, fingerprintExcludedCount, backfillDepth)
    }

    private fun overlapsLineage(
        candidate: MemorySnippet,
        exclusionContext: MemoryExclusionContext,
    ): Boolean {
        val lineage = candidate.metadata.lineage
        return lineage.sourceTurnIds.any { it in exclusionContext.sourceTurnIds } ||
            lineage.sourceMemoryIds.any { it in exclusionContext.sourceMemoryIds } ||
            candidate.id in exclusionContext.sourceMemoryIds
    }

    private fun overlapsLineage(candidate: MemorySnippet, selected: MemorySnippet): Boolean {
        val candidateLineage = candidate.metadata.lineage
        val selectedLineage = selected.metadata.lineage
        return candidateLineage.sourceTurnIds.any { it in selectedLineage.sourceTurnIds } ||
            candidateLineage.sourceMemoryIds.any { it in selectedLineage.sourceMemoryIds || it == selected.id } ||
            selectedLineage.sourceMemoryIds.any { it == candidate.id || it in candidateLineage.sourceMemoryIds }
    }

    private data class SelectionResult(
        val selected: List<MemorySnippet> = emptyList(),
        val lineageExcludedCount: Int = 0,
        val fingerprintExcludedCount: Int = 0,
        val backfillDepth: Int = 0,
    ) {
        operator fun plus(other: SelectionResult): SelectionResult = SelectionResult(
            selected = selected + other.selected,
            lineageExcludedCount = lineageExcludedCount + other.lineageExcludedCount,
            fingerprintExcludedCount = fingerprintExcludedCount + other.fingerprintExcludedCount,
            backfillDepth = maxOf(backfillDepth, other.backfillDepth),
        )
    }

    override suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector> =
        inferenceExecutor.run {
            entries.asReversed()
                .asSequence()
                .filter { it.metadata.incarnationId.isBlank() || it.metadata.incarnationId == sessionId }
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
