package io.openeden.memory

data class MemoryExclusionContext(
    val sourceTurnIds: Set<String> = emptySet(),
    val sourceMemoryIds: Set<String> = emptySet(),
    val contentFingerprints: Set<String> = emptySet(),
) {
    internal fun excludesTurnLineage(lineage: MemoryLineage): Boolean =
        lineage.overlapsSourceTurns(sourceTurnIds)

    internal fun excludesMemoryLineage(memoryId: String, lineage: MemoryLineage): Boolean =
        lineage.overlapsSourceMemories(memoryId, sourceMemoryIds)
}
