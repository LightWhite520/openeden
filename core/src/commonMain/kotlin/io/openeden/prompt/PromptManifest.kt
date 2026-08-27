package io.openeden.prompt

import io.openeden.hash.Sha256

data class PromptManifest(val entries: List<PromptManifestEntry>) {
    fun traceAttributes(): Map<String, String> = buildMap {
        put("entry_count", entries.size.toString())
        entries.forEach { entry ->
            put("${entry.id}_utf8_bytes", entry.utf8Bytes.toString())
            put("${entry.id}_fingerprint", entry.fingerprint)
        }
    }

    companion object {
        fun from(prompt: BuiltPrompt): PromptManifest = PromptManifest(
            listOf(
                "system" to prompt.systemText,
                "persona" to prompt.personaText,
                "context" to prompt.contextText,
                "user" to prompt.userText,
            ).filter { it.second.isNotBlank() }.map { (id, text) ->
                val bytes = text.encodeToByteArray()
                PromptManifestEntry(id, bytes.size, Sha256.hex(bytes))
            },
        )
    }
}
