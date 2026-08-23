package io.openeden.server.persistence.sqldelight

import io.openeden.memory.MemoryContentFingerprint
import io.openeden.memory.MemoryLineage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object PersistedMemoryLineage {
    const val MAX_EXACT_IDS: Int = 256

    suspend fun encode(lineage: MemoryLineage, json: Json): EncodedLineage {
        return EncodedLineage(
            sourceTurnIdsJson = encodeIds(lineage.sourceTurnIds, allowRange = true, json = json),
            sourceMemoryIdsJson = encodeIds(lineage.sourceMemoryIds, allowRange = false, json = json),
            lineageVersion = lineage.lineageVersion,
        )
    }

    fun decode(
        sourceTurnIdsJson: String?,
        sourceMemoryIdsJson: String?,
        lineageVersion: Long?,
        json: Json,
    ): MemoryLineage {
        val version = (lineageVersion ?: MemoryLineage.CURRENT_VERSION.toLong()).toInt()
        return MemoryLineage(
            sourceTurnIds = decodeIds(sourceTurnIdsJson, json).exactIds,
            sourceMemoryIds = decodeIds(sourceMemoryIdsJson, json).exactIds,
            lineageVersion = version,
        )
    }

    fun overlaps(
        persistedJson: String,
        candidateIds: Iterable<String>,
        sourceTurns: Boolean,
        json: Json = Json,
    ): Boolean {
        val persisted = decodeIds(persistedJson, json)
        val candidates = candidateIds.toSet()
        return persisted.exactIds.any { it in candidates } ||
            (sourceTurns && persisted.range?.containsAny(candidates) == true)
    }

    private suspend fun encodeIds(ids: List<String>, allowRange: Boolean, json: Json): String {
        if (ids.size <= MAX_EXACT_IDS) return json.encodeToString(ids)

        val range = if (allowRange) verifiedRange(ids) else null
        val digest = MemoryContentFingerprint.of(ids.joinToString("\n"))
        return buildJsonObject {
            put("ids", JsonArray(ids.take(MAX_EXACT_IDS).map(::JsonPrimitive)))
            put("overflowCount", ids.size)
            put("completeSourceDigest", digest)
            range?.let {
                put(
                    "rangeStart",
                    it.start,
                )
                put(
                    "rangeEnd",
                    it.end,
                )
            }
        }.toString()
    }

    private fun decodeIds(raw: String?, json: Json): PersistedIds {
        if (raw.isNullOrBlank()) return PersistedIds.Empty
        return runCatching {
            when (val element = json.parseToJsonElement(raw)) {
                is JsonArray -> PersistedIds(
                    exactIds = json.decodeFromJsonElement(element),
                    totalCount = element.size,
                    range = null,
                )
                is JsonObject -> {
                    val exactIds = element["ids"]?.jsonArray?.let { json.decodeFromJsonElement<List<String>>(it) }
                        ?: emptyList()
                    val totalCount = element["overflowCount"]?.jsonPrimitive?.intOrNull
                        ?: exactIds.size
                    val range = SourceTurnRange.parse(
                        element["rangeStart"]?.jsonPrimitive?.contentOrNull,
                        element["rangeEnd"]?.jsonPrimitive?.contentOrNull,
                    )
                    PersistedIds(exactIds, totalCount.coerceAtLeast(exactIds.size), range)
                }
                else -> PersistedIds.Empty
            }
        }.getOrDefault(PersistedIds.Empty)
    }

    private fun verifiedRange(ids: List<String>): SourceTurnRange? {
        val parsed = ids.map { SourceTurnRange.parseId(it) ?: return null }
        if (parsed.map { it.first }.distinct().size != 1) return null
        val numbers = parsed.map { it.second }.sorted()
        if (numbers.zipWithNext().any { (left, right) -> right != left + 1L }) return null
        val startNumber = numbers.firstOrNull() ?: return null
        val endNumber = numbers.lastOrNull() ?: return null
        val prefix = parsed.first().first
        val start = ids.firstOrNull { id ->
            SourceTurnRange.parseId(id) == (prefix to startNumber)
        } ?: return null
        val end = ids.firstOrNull { id ->
            SourceTurnRange.parseId(id) == (prefix to endNumber)
        } ?: return null
        return SourceTurnRange(
            prefix = prefix,
            startNumber = startNumber,
            endNumber = endNumber,
            start = start,
            end = end,
        )
    }

    data class EncodedLineage(
        val sourceTurnIdsJson: String,
        val sourceMemoryIdsJson: String,
        val lineageVersion: Int,
    )

    private data class PersistedIds(
        val exactIds: List<String>,
        val totalCount: Int,
        val range: SourceTurnRange?,
    ) {
        companion object {
            val Empty = PersistedIds(emptyList(), 0, null)
        }
    }

    private data class SourceTurnRange(
        val prefix: String,
        val startNumber: Long,
        val endNumber: Long,
        val start: String,
        val end: String,
    ) {
        fun containsAny(ids: Set<String>): Boolean = ids.any { id ->
            parseId(id)?.let { (candidatePrefix, number) ->
                candidatePrefix == prefix && number in startNumber..endNumber
            } == true
        }

        companion object {
            fun parse(start: String?, end: String?): SourceTurnRange? {
                if (start == null || end == null) return null
                val startParts = parseId(start) ?: return null
                val endParts = parseId(end) ?: return null
                if (startParts.first != endParts.first || startParts.second > endParts.second) return null
                return SourceTurnRange(startParts.first, startParts.second, endParts.second, start, end)
            }

            fun parseId(id: String): Pair<String, Long>? {
                val separator = id.lastIndexOf('-')
                if (separator <= 0 || separator == id.lastIndex) return null
                return id.substring(separator + 1).toLongOrNull()?.let { number ->
                    id.substring(0, separator) to number
                }
            }
        }
    }
}
