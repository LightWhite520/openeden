package io.openeden.runtime.pipeline

import io.openeden.memory.RetrievalResult
import io.openeden.transcript.PromptHistorySnapshot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Trace identifiers and sealed hashes, never user text or model instructions. */
internal fun contextEvidence(history: PromptHistorySnapshot, retrieval: RetrievalResult): Map<String, String> {
    fun ids(values: Iterable<String>) = JsonArray(values.sorted().map(::JsonPrimitive))
    val memories = retrieval.memories + retrieval.recentMemories
    return mapOf(
        "mode" to retrieval.mode.name,
        "history_epoch" to history.cacheEpoch.toString(),
        "history_source_turn_ids" to ids(history.sourceTurnIds).toString(),
        "history_sealed_chunks" to JsonArray(history.stableChunks.map { chunk ->
            buildJsonObject {
                put("sha256", chunk.fingerprint)
                put("turn_ids", ids(chunk.turnIds))
                put("serializer_version", chunk.serializerVersion)
            }
        }).toString(),
        "history_summary_sha256" to (history.summary?.fingerprint ?: ""),
        "history_summary_source_turn_ids" to ids(history.summary?.sourceTurnIds.orEmpty()).toString(),
        "retrieved_lineage" to JsonArray(memories.map { memory ->
            buildJsonObject {
                put("memory_id", memory.id)
                put("source_turn_ids", ids(memory.metadata.lineage.sourceTurnIds))
                put("source_memory_ids", ids(memory.metadata.lineage.sourceMemoryIds))
            }
        }).toString(),
        "history_rag_turn_overlap" to memories.flatMap { it.metadata.lineage.sourceTurnIds }
            .toSet().intersect(history.sourceTurnIds).size.toString(),
        "excluded_by_turn_lineage" to retrieval.diagnostics.excludedByTurnLineage.toString(),
        "excluded_by_memory_lineage" to retrieval.diagnostics.excludedByMemoryLineage.toString(),
        "excluded_by_fingerprint" to retrieval.diagnostics.excludedByFingerprint.toString(),
        "backfilled" to retrieval.diagnostics.backfilled.toString(),
        "underfilled" to retrieval.diagnostics.underfilled.toString(),
    )
}
