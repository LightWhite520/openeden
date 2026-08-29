package io.openeden.transcript

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PromptHistoryAssembler(
    val serializer: PromptHistorySerializer = PromptHistorySerializer(),
    private val turnCeiling: Int = DEFAULT_TURN_CEILING,
    private val minimumMutableTailTurns: Int = DEFAULT_MUTABLE_TAIL_TURNS,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init {
        require(turnCeiling > 0) { "turnCeiling must be positive" }
        require(minimumMutableTailTurns >= 0) { "minimumMutableTailTurns must not be negative" }
    }

    suspend fun assemble(
        sessionId: String,
        turns: List<ConversationTurn>,
        requiredTailTurns: Int,
        tokenBudget: Int,
        existingStableChunks: List<PromptHistoryChunk> = emptyList(),
        existingSummary: PromptHistorySummary? = null,
        cacheEpoch: Long = 0L,
        storedSerializerVersion: Int = serializer.serializerVersion,
    ): PromptHistorySnapshot = withContext(computationDispatcher) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(requiredTailTurns >= 0) { "requiredTailTurns must not be negative" }
        require(tokenBudget > 0) { "tokenBudget must be positive" }
        require(cacheEpoch >= 0L) { "cacheEpoch must not be negative" }
        require(turns.all { it.sessionId == sessionId }) {
            "Prompt history turns must belong to the requested session"
        }
        require(existingStableChunks.all { it.sessionId == sessionId }) {
            "Prompt history chunks must belong to the requested session"
        }

        val summarizedTurnIds = existingSummary?.sourceTurnIds.orEmpty()
        val chronologicalTurns = turns
            .asSequence()
            .filterNot { it.turnId in summarizedTurnIds }
            .sortedWith(turnComparator)
            .toList()
        var activeEpoch = cacheEpoch
        var stableChunks = existingStableChunks
            .filter { chunk ->
                chunk.cacheEpoch == cacheEpoch &&
                    chunk.items.all(serializer::isValid)
            }
            .sortedWith(compareBy<PromptHistoryChunk> { chunk ->
                chronologicalTurns.indexOfFirst { it.turnId == chunk.firstTurnId }.let { index ->
                    if (index < 0) Int.MAX_VALUE else index
                }
            })
        val serializerChanged = storedSerializerVersion != serializer.serializerVersion ||
            stableChunks.any { it.serializerVersion != serializer.serializerVersion }
        if (serializerChanged) {
            require(activeEpoch < Long.MAX_VALUE) { "cacheEpoch cannot be advanced for serializer migration" }
            activeEpoch += 1L
            stableChunks = emptyList()
        }

        val reservedTailSize = maxOf(requiredTailTurns, minimumMutableTailTurns)
        val reservedTail = chronologicalTurns.takeLast(reservedTailSize)
        val stableTurnIds = stableChunks.flatMapTo(mutableSetOf(), PromptHistoryChunk::turnIds)
        val reservedTailIds = reservedTail
            .asSequence()
            .map(ConversationTurn::turnId)
            .filterNot(stableTurnIds::contains)
            .toMutableSet()
        val eligibleTurns = chronologicalTurns.filter { turn ->
            turn.turnId !in stableTurnIds && turn.turnId !in reservedTailIds
        }
        val newlySealed = mutableListOf<PromptHistoryChunk>()
        var candidate = mutableListOf<ConversationTurn>()
        eligibleTurns.forEach { turn ->
            val proposed = candidate + turn
            val proposedChunk = serializer.createChunk(sessionId, activeEpoch, proposed)
            val exceeds = candidate.isNotEmpty() &&
                (proposed.size > turnCeiling || proposedChunk.tokenCount > tokenBudget)
            if (exceeds) {
                newlySealed += serializer.createChunk(sessionId, activeEpoch, candidate)
                candidate = mutableListOf(turn)
            } else {
                candidate = proposed.toMutableList()
            }

            val currentChunk = serializer.createChunk(sessionId, activeEpoch, candidate)
            if (candidate.size >= turnCeiling || currentChunk.tokenCount >= tokenBudget) {
                newlySealed += currentChunk
                candidate = mutableListOf()
            }
        }

        val allStableChunks = (stableChunks + newlySealed).distinctBy { chunk ->
            chunk.sessionId to chunk.cacheEpoch to chunk.firstTurnId
        }
        val mutableTailTurns = candidate + reservedTail.filterNot { it.turnId in stableTurnIds }
        val mutableTail = serializer.createItems(mutableTailTurns)
        PromptHistorySnapshot(
            stableChunks = allStableChunks,
            summary = existingSummary,
            mutableTail = mutableTail,
            sourceTurnIds = (
                summarizedTurnIds +
                    allStableChunks.flatMap { it.turnIds } +
                    mutableTail.map(PromptHistoryItem::turnId)
                ).toSet(),
            cacheEpoch = activeEpoch,
        )
    }

    private companion object {
        const val DEFAULT_TURN_CEILING = 16
        const val DEFAULT_MUTABLE_TAIL_TURNS = 4

        val turnComparator = compareBy<ConversationTurn>(
            ConversationTurn::completedAtMs,
            ConversationTurn::turnId,
        )
    }
}
