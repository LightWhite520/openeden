package io.openeden.transcript

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PromptHistoryCompactor {
    suspend fun compact(
        requestId: String,
        snapshot: PromptHistorySnapshot,
    ): PromptHistorySnapshot

    data class Request(
        val schemaVersion: Int,
        val priorSummary: PromptHistorySummary?,
        val items: List<PromptHistoryItem>,
        val sourceTurnIds: Set<String>,
    )

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val DEFAULT_MAX_REMEMBERED_REQUESTS: Int = 64

        fun validated(
            maxRememberedRequests: Int = DEFAULT_MAX_REMEMBERED_REQUESTS,
            generate: suspend (Request) -> String,
        ): PromptHistoryCompactor = ValidatedPromptHistoryCompactor(maxRememberedRequests, generate)
    }
}

private class ValidatedPromptHistoryCompactor(
    private val maxRememberedRequests: Int,
    private val generate: suspend (PromptHistoryCompactor.Request) -> String,
) : PromptHistoryCompactor {
    private val stateMutex = Mutex()
    private val completed = linkedMapOf<String, CompletedCompaction>()

    init {
        require(maxRememberedRequests > 0) { "maxRememberedRequests must be positive" }
    }

    override suspend fun compact(
        requestId: String,
        snapshot: PromptHistorySnapshot,
    ): PromptHistorySnapshot {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        stateMutex.lock()
        try {
            completed[requestId]?.let { cached ->
                require(cached.source == snapshot) { "requestId was already used for another snapshot" }
                return cached.result
            }

            val stableItems = snapshot.stableChunks.flatMap(PromptHistoryChunk::items)
            val result = if (stableItems.isEmpty()) {
                snapshot
            } else {
                try {
                    compactValidated(snapshot, stableItems)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    snapshot
                }
            }
            completed[requestId] = CompletedCompaction(snapshot, result)
            while (completed.size > maxRememberedRequests) {
                completed.remove(completed.keys.first())
            }
            return result
        } finally {
            stateMutex.unlock()
        }
    }

    private suspend fun compactValidated(
        snapshot: PromptHistorySnapshot,
        stableItems: List<PromptHistoryItem>,
    ): PromptHistorySnapshot {
        require(snapshot.cacheEpoch < Long.MAX_VALUE) { "cacheEpoch cannot be advanced" }
        val compactedSourceTurnIds = buildSet {
            snapshot.summary?.sourceTurnIds?.let(::addAll)
            stableItems.mapTo(this, PromptHistoryItem::turnId)
        }
        val payload = generate(
            PromptHistoryCompactor.Request(
                schemaVersion = PromptHistoryCompactor.SCHEMA_VERSION,
                priorSummary = snapshot.summary,
                items = stableItems,
                sourceTurnIds = compactedSourceTurnIds,
            ),
        )
        val document = json.decodeFromString<CompactionDocument>(payload)
        require(document.schemaVersion == PromptHistoryCompactor.SCHEMA_VERSION) {
            "Unsupported prompt history compaction schema ${document.schemaVersion}"
        }
        val normalized = document.normalized()
        require(normalized.chronology.isNotEmpty()) { "Compaction chronology must not be empty" }
        val text = normalized.render()
        val summary = PromptHistorySummary(
            text = text,
            sourceTurnIds = compactedSourceTurnIds,
            fingerprint = PromptHistorySerializer.fingerprintText(text),
            serializerVersion = PromptHistoryCompactor.SCHEMA_VERSION,
        )
        return snapshot.copy(
            stableChunks = emptyList(),
            summary = summary,
            cacheEpoch = snapshot.cacheEpoch + 1L,
        )
    }

    private data class CompletedCompaction(
        val source: PromptHistorySnapshot,
        val result: PromptHistorySnapshot,
    )

    private companion object {
        val json = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
    }
}

@Serializable
private data class CompactionDocument(
    @SerialName("schema_version")
    val schemaVersion: Int,
    @SerialName("named_entities")
    val namedEntities: List<String>,
    val commitments: List<String>,
    @SerialName("unresolved_questions")
    val unresolvedQuestions: List<String>,
    @SerialName("relationship_facts")
    val relationshipFacts: List<String>,
    val chronology: List<String>,
) {
    fun normalized(): CompactionDocument = copy(
        namedEntities = namedEntities.normalizedValues("named_entities"),
        commitments = commitments.normalizedValues("commitments"),
        unresolvedQuestions = unresolvedQuestions.normalizedValues("unresolved_questions"),
        relationshipFacts = relationshipFacts.normalizedValues("relationship_facts"),
        chronology = chronology.normalizedValues("chronology"),
    )

    fun render(): String = buildString {
        append("[OPENEDEN_PROMPT_HISTORY_SUMMARY v")
        append(schemaVersion)
        append("]\n")
        appendSection("named_entities", namedEntities)
        appendSection("commitments", commitments)
        appendSection("unresolved_questions", unresolvedQuestions)
        appendSection("relationship_facts", relationshipFacts)
        appendSection("chronology", chronology)
        append("[/OPENEDEN_PROMPT_HISTORY_SUMMARY]")
    }

    private fun List<String>.normalizedValues(field: String): List<String> = map(String::trim).also { values ->
        require(values.none(String::isBlank)) { "$field must not contain blank values" }
    }

    private fun StringBuilder.appendSection(name: String, values: List<String>) {
        append(name)
        append(":\n")
        values.forEach { value ->
            append("- ")
            append(value)
            append('\n')
        }
    }
}
