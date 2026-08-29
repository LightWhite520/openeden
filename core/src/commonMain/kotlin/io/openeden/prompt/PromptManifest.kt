package io.openeden.prompt

data class PromptManifest(val entries: List<PromptManifestEntry>) {
    fun traceAttributes(): Map<String, String> = buildMap {
        put("entry_count", this@PromptManifest.entries.size.toString())
        this@PromptManifest.entries.forEach { entry ->
            put("${entry.id}_utf8_bytes", entry.utf8Bytes.toString())
            put("${entry.id}_fingerprint", entry.fingerprint)
        }
    }

    companion object {
        fun from(prompt: BuiltPrompt): PromptManifest = PromptManifest(
            prompt.cachePrefixSegments().map { segment ->
                val utf8Bytes = if (segment.kind == PromptSegmentKind.HISTORY) {
                    segment.wireItems.sumOf { it.text.encodeToByteArray().size }
                } else {
                    segment.text.encodeToByteArray().size
                }
                PromptManifestEntry(segment.id, utf8Bytes, segment.fingerprint)
            },
        )
    }
}
