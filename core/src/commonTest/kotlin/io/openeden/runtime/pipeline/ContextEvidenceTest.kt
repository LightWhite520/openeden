package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.memory.*
import io.openeden.transcript.*
import io.openeden.trace.TraceContext
import io.openeden.trace.TraceSanitizer
import io.openeden.trace.TraceSpan
import io.openeden.trace.TraceStatus
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ContextEvidenceTest {
    @Test
    fun `evidence exposes real overlap and sealed mutations without leaking text`() {
        val vector = BioVector(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val item = PromptHistoryItem("user", "private transcript", "turn-1", "item-hash")
        val chunk = PromptHistoryChunk("session", 0, listOf(item), 10, 2)
        val history = PromptHistorySnapshot(stableChunks = listOf(chunk), sourceTurnIds = setOf("turn-1"))
        val memory = MemorySnippet("memory-1", "private memory", MemoryMetadata(vector, 0f, VectorDelta.Zero,
            vector, "owner", lineage = MemoryLineage(sourceTurnIds = listOf("turn-1"))))
        val retrieval = RetrievalResult(RetrievalMode.CONGRUENT, "label", listOf(memory))
        val evidence = contextEvidence(history, retrieval)
        val persisted = TraceSanitizer.sanitize(TraceSpan(context = TraceContext("trace", "turn", "session"),
            spanId = "span", stage = "retrieval", status = TraceStatus.OK, startedAtMs = 0, attributes = evidence))
        assertEquals(evidence, persisted.attributes)
        assertEquals("1", evidence["history_rag_turn_overlap"])
        assertFalse(evidence.toString().contains("private transcript"))
        assertFalse(evidence.toString().contains("private memory"))
        val tracedChunk = Json.parseToJsonElement(evidence.getValue("history_sealed_chunks")).jsonArray.single().jsonObject
        assertEquals(chunk.fingerprint, tracedChunk.getValue("sha256").jsonPrimitive.content)
        val changed = history.copy(stableChunks = listOf(chunk.copy(items = listOf(item.copy(fingerprint = "changed")))))
        assertNotEquals(evidence["history_sealed_chunks"], contextEvidence(changed, retrieval)["history_sealed_chunks"])
        assertEquals("0", contextEvidence(history, retrieval.copy(memories = emptyList()))["history_rag_turn_overlap"])
        val repeated = contextEvidence(history, retrieval.copy(recentMemories = listOf(memory)))
        assertEquals(2, Json.parseToJsonElement(repeated.getValue("retrieved_lineage")).jsonArray.size)
    }
}
