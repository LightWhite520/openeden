package io.openeden.codebook

data class CodebookEntry(
    val nodeId: String,
    val definition: String,
    val tags: List<String>,
    val definitionEn: String = definition,
    val definitionZh: String = "",
)

class CodebookDictionary private constructor(
    private val entries: Map<String, CodebookEntry>,
) {
    fun definitionFor(nodeId: String): String? = entries[nodeId]?.definition

    fun definitionsFor(nodeIds: List<String>): List<String> =
        nodeIds.mapNotNull(::definitionFor)

    companion object {
        fun parseCsv(csv: String): CodebookDictionary {
            val lines = splitCsvRecords(csv)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (lines.isEmpty()) return CodebookDictionary(emptyMap())
            val header = splitCsvRow(lines.first().removePrefix("\uFEFF"))
            val parsed = lines.asSequence()
                .drop(1)
                .map { parseRow(header, it) }
                .toList()
            require(parsed.map { it.nodeId }.toSet().size == parsed.size) {
                "Duplicate codebook node_id"
            }
            val entries = parsed.associateBy { it.nodeId }
            return CodebookDictionary(entries)
        }

        private fun parseRow(header: List<String>, row: String): CodebookEntry {
            val fields = splitCsvRow(row)
            val byName = header.mapIndexed { index, name -> name to fields.getOrElse(index) { "" } }.toMap()
            val nodeId = byName["node_id"] ?: fields.getOrElse(0) { "" }
            val definitionEn = byName["definition_en"]
                ?: byName["definition"]
                ?: fields.getOrElse(1) { "" }
            val definitionZh = byName["definition_zh"].orEmpty()
            val tags = (byName["tags"] ?: fields.getOrElse(if (definitionZh.isBlank()) 2 else 3) { "" })
                .split(';')
                .filter { it.isNotBlank() }
            require(nodeId.isNotBlank() && definitionEn.isNotBlank()) { "Invalid codebook CSV row: $row" }
            return CodebookEntry(
                nodeId = nodeId,
                definition = buildDefinition(definitionEn, definitionZh),
                tags = tags,
                definitionEn = definitionEn,
                definitionZh = definitionZh,
            )
        }

        private fun buildDefinition(definitionEn: String, definitionZh: String): String =
            if (definitionZh.isBlank()) {
                definitionEn
            } else {
                "EN: $definitionEn\nZH: $definitionZh"
            }

        private fun splitCsvRow(row: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var index = 0
            while (index < row.length) {
                val char = row[index]
                when (char) {
                    '"' -> if (inQuotes && row.getOrNull(index + 1) == '"') {
                        current.append(char)
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                    ',' -> if (inQuotes) {
                        current.append(char)
                    } else {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
                index += 1
            }
            fields += current.toString()
            return fields.map { it.trim() }
        }

        private fun splitCsvRecords(csv: String): List<String> {
            val records = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var index = 0
            while (index < csv.length) {
                val char = csv[index]
                when (char) {
                    '"' -> {
                        current.append(char)
                        if (inQuotes && csv.getOrNull(index + 1) == '"') {
                            current.append('"')
                            index += 1
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    '\r' -> if (inQuotes) {
                        current.append(char)
                    }
                    '\n' -> if (inQuotes) {
                        current.append(char)
                    } else {
                        records += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
                index += 1
            }
            if (current.isNotEmpty()) records += current.toString()
            return records
        }
    }
}
