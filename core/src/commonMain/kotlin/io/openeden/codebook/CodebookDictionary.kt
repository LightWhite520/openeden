package io.openeden.codebook

data class CodebookEntry(
    val nodeId: String,
    val definition: String,
    val tags: List<String>,
)

class CodebookDictionary private constructor(
    private val entries: Map<String, CodebookEntry>,
) {
    fun definitionFor(nodeId: String): String? = entries[nodeId]?.definition

    fun definitionsFor(nodeIds: List<String>): List<String> =
        nodeIds.mapNotNull(::definitionFor)

    companion object {
        fun parseCsv(csv: String): CodebookDictionary {
            val entries = csv.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .drop(1)
                .map(::parseRow)
                .associateBy { it.nodeId }
            return CodebookDictionary(entries)
        }

        private fun parseRow(row: String): CodebookEntry {
            val fields = splitCsvRow(row)
            require(fields.size >= 3) { "Invalid codebook CSV row: $row" }
            return CodebookEntry(
                nodeId = fields[0],
                definition = fields[1],
                tags = fields[2].split(';').filter { it.isNotBlank() },
            )
        }

        private fun splitCsvRow(row: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            for (char in row) {
                when (char) {
                    '"' -> inQuotes = !inQuotes
                    ',' -> if (inQuotes) {
                        current.append(char)
                    } else {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
            }
            fields += current.toString()
            return fields.map { it.trim() }
        }
    }
}
