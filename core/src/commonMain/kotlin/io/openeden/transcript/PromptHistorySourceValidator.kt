package io.openeden.transcript

/** Validates evidence references and source order, not the model's semantic interpretation. */
internal object PromptHistorySourceValidator {
    fun canonicalChronology(
        items: List<PromptHistoryItem>,
        priorSourceIds: Set<String>,
        facts: List<String>,
        chronology: List<String>,
    ): List<String> {
        val positions = items.map(PromptHistoryItem::turnId).distinct().withIndex().associate { it.value to it.index }
        val allowed = positions.keys + priorSourceIds
        val covered = chronology.flatMap { references(it, allowed) }.toMutableSet()
        val complete = chronology.toMutableList()
        facts.forEach { fact ->
            val ids = references(fact, allowed)
            if (!covered.containsAll(ids)) {
                // Copy the model's attributed fact verbatim; do not invent an event or its status.
                complete += fact
                covered += ids
            }
        }
        return complete.sortedBy { entry -> references(entry, allowed).minOf { positions[it] ?: -1 } }
    }

    private fun references(entry: String, allowed: Set<String>): Set<String> {
        require(entry.startsWith('[') && ']' in entry) { "Missing compaction source anchor" }
        val ids = entry.substring(1, entry.indexOf(']')).split(',').map(String::trim).toSet()
        require(ids.isNotEmpty() && ids.all { it in allowed }) { "Unknown compaction source anchor" }
        return ids
    }

    fun validate(
        items: List<PromptHistoryItem>,
        priorSourceIds: Set<String>,
        commitments: List<String>,
        relationshipFacts: List<String>,
        chronology: List<String>,
    ) {
        val positions = items.map(PromptHistoryItem::turnId).distinct().withIndex().associate { it.value to it.index }
        val allowed = positions.keys + priorSourceIds
        val chronologicalReferences = chronology.map { references(it, allowed) }
        val chronologyIds = chronologicalReferences.flatten().toSet()
        require((commitments + relationshipFacts).flatMap { references(it, allowed) }.all { it in chronologyIds }) {
            "Compaction facts are missing from chronology"
        }
        // Prior summary source order is unavailable; preserve it before newly sealed turns.
        val order = chronologicalReferences.map { ids -> ids.minOf { positions[it] ?: -1 } }
        require(order.zipWithNext().all { (left, right) -> left <= right }) { "Compaction chronology reordered source events" }
    }
}
