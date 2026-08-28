package io.openeden.memory

import kotlinx.serialization.Serializable

@Serializable(with = MemoryLineageSerializer::class)
class MemoryLineage private constructor(
    val sourceTurnIds: List<String> = emptyList(),
    val sourceMemoryIds: List<String> = emptyList(),
    val lineageVersion: Int = CURRENT_VERSION,
) {
    constructor(
        sourceTurnIds: Iterable<String> = emptyList(),
        sourceMemoryIds: Iterable<String> = emptyList(),
        lineageVersion: Int = CURRENT_VERSION,
    ) : this(
        sourceTurnIds = sourceTurnIds.distinct().sorted(),
        sourceMemoryIds = sourceMemoryIds.distinct().sorted(),
        lineageVersion = lineageVersion,
    ) {
        require(lineageVersion > 0) { "lineageVersion must be positive" }
    }

    val isEmpty: Boolean
        get() = sourceTurnIds.isEmpty() && sourceMemoryIds.isEmpty()

    internal fun overlapsSourceTurns(turnIds: Set<String>): Boolean =
        sourceTurnIds.any { it in turnIds }

    internal fun overlapsSourceMemories(memoryId: String, memoryIds: Set<String>): Boolean =
        memoryId in memoryIds || sourceMemoryIds.any { it in memoryIds }

    internal fun sharesSourceTurnWith(other: MemoryLineage): Boolean =
        sourceTurnIds.any { it in other.sourceTurnIds }

    internal fun sharesSourceMemoryWith(
        memoryId: String,
        otherMemoryId: String,
        other: MemoryLineage,
    ): Boolean =
        sourceMemoryIds.any { it == otherMemoryId || it in other.sourceMemoryIds } ||
            other.sourceMemoryIds.any { it == memoryId || it in sourceMemoryIds }

    override fun equals(other: Any?): Boolean =
        other is MemoryLineage &&
            sourceTurnIds == other.sourceTurnIds &&
            sourceMemoryIds == other.sourceMemoryIds &&
            lineageVersion == other.lineageVersion

    override fun hashCode(): Int =
        (((sourceTurnIds.hashCode() * 31) + sourceMemoryIds.hashCode()) * 31) + lineageVersion

    override fun toString(): String =
        "MemoryLineage(sourceTurnIds=$sourceTurnIds, sourceMemoryIds=$sourceMemoryIds, lineageVersion=$lineageVersion)"

    companion object {
        const val CURRENT_VERSION: Int = 1

        val Empty: MemoryLineage = MemoryLineage(
            sourceTurnIds = emptySequence<String>().asIterable(),
            sourceMemoryIds = emptySequence<String>().asIterable(),
        )
    }
}
