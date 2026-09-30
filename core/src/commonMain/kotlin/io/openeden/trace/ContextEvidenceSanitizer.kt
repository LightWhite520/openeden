package io.openeden.trace

import kotlinx.serialization.json.*

/** Only bounded identifier/hash schemas receive a larger trace budget; arbitrary text does not. */
internal object ContextEvidenceSanitizer {
    val keys = setOf("history_source_turn_ids", "history_summary_source_turn_ids", "history_sealed_chunks", "retrieved_lineage")
    private val identifier = Regex("[A-Za-z0-9_.:/-]{1,128}")
    private val sha256 = Regex("[a-f0-9]{64}")

    fun sanitize(key: String, value: String): String {
        if (value.length > 65_536) return "{\"evidence_status\":\"TOO_LARGE\"}"
        val valid = runCatching {
            val array = Json.parseToJsonElement(value).jsonArray
            when (key) {
                "history_source_turn_ids", "history_summary_source_turn_ids" -> ids(array)
                "history_sealed_chunks" -> array.all { element ->
                    val entry = element.jsonObject
                    entry.keys == setOf("sha256", "turn_ids", "serializer_version") &&
                        sha256.matches(entry.getValue("sha256").jsonPrimitive.content) &&
                        ids(entry.getValue("turn_ids").jsonArray) &&
                        (entry.getValue("serializer_version").jsonPrimitive.intOrNull ?: 0) > 0
                }
                "retrieved_lineage" -> array.all { element ->
                    val entry = element.jsonObject
                    entry.keys == setOf("memory_id", "source_turn_ids", "source_memory_ids") &&
                        identifier.matches(entry.getValue("memory_id").jsonPrimitive.content) &&
                        ids(entry.getValue("source_turn_ids").jsonArray) &&
                        ids(entry.getValue("source_memory_ids").jsonArray)
                }
                else -> false
            }
        }.getOrDefault(false)
        return if (valid) value else "{\"evidence_status\":\"INVALID\"}"
    }

    private fun ids(array: JsonArray): Boolean = array.all {
        it is JsonPrimitive && it.isString && identifier.matches(it.content)
    }
}
