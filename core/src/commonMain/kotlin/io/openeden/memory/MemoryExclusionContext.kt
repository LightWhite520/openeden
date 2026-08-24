package io.openeden.memory

data class MemoryExclusionContext(
    val sourceTurnIds: Set<String> = emptySet(),
    val sourceMemoryIds: Set<String> = emptySet(),
    val contentFingerprints: Set<String> = emptySet(),
)
