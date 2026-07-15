package io.openeden.persona

import java.nio.file.Files
import java.nio.file.Path

object PersonaFileLoader {
    fun load(path: Path): PersonaConfig = MapPersonaLoader.load(parseDefaultPersonaYaml(path))

    private fun parseDefaultPersonaYaml(path: Path): Map<String, String> {
        require(Files.exists(path)) { "Missing persona file: $path" }
        val lines = Files.readAllLines(path)
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("mode:") -> values["mode"] = line.valueAfterColon()
                line.startsWith("start_sub_state:") -> values["start_sub_state"] = line.valueAfterColon()
                line.startsWith("  ") && line.contains(":") -> {
                    val key = line.substringBefore(":").trim()
                    val rawValue = line.substringAfter(":").trim()
                    if (isPromptSectionKey(key)) {
                        when {
                            rawValue == "|" -> {
                                val block = mutableListOf<String>()
                                index += 1
                                while (index < lines.size && lines[index].startsWith("    ")) {
                                    block += lines[index].removePrefix("    ")
                                    index += 1
                                }
                                index -= 1
                                values[key] = block.joinToString("\n").trim()
                            }
                            rawValue.isEmpty() && lines.getOrNull(index + 1)?.isSequenceItem() == true -> {
                                val items = mutableListOf<String>()
                                index += 1
                                while (index < lines.size && lines[index].isSequenceItem()) {
                                    items += lines[index].trimStart().removePrefix("- ").trim().trim('"')
                                    index += 1
                                }
                                index -= 1
                                values[key] = items.joinToString("\n")
                            }
                            else -> values[key] = rawValue.trim('"')
                        }
                    }
                }
            }
            index += 1
        }
        return values
    }

    private fun isPromptSectionKey(key: String): Boolean =
        key.startsWith("persona.") || key.startsWith("output.") ||
            key.startsWith("heartbeat.") || key.startsWith("style.")
            || key.startsWith("diary.")

    private fun String.isSequenceItem(): Boolean = trimStart().startsWith("- ")

    private fun String.valueAfterColon(): String = substringAfter(":").trim().trim('"')
}
