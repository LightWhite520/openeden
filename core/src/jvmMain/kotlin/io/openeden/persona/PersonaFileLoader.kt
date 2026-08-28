package io.openeden.persona

import java.nio.file.Files
import java.nio.file.Path

object PersonaFileLoader {
    fun load(path: Path): PersonaConfig {
        val parsed = parseDefaultPersonaYaml(path)
        return MapPersonaLoader.load(
            values = parsed.values,
            fewShots = parsed.fewShots,
            outputPolicy = parsed.outputPolicy,
        )
    }

    private fun parseDefaultPersonaYaml(path: Path): ParsedPersonaYaml {
        require(Files.exists(path)) { "Missing persona file: $path" }
        val lines = Files.readAllLines(path)
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            when {
                line.startsWith("mode:") -> values["mode"] = line.valueAfterColon()
                line.startsWith("start_sub_state:") -> values["start_sub_state"] = line.valueAfterColon()
                line.startsWith("core_self:") -> {
                    require(line.substringAfter(":").trim() == "|") { "core_self must be a literal block" }
                    val block = mutableListOf<String>()
                    index += 1
                    while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith("  "))) {
                        block += if (lines[index].isBlank()) "" else lines[index].removePrefix("  ")
                        index += 1
                    }
                    index -= 1
                    values["core_self"] = block.joinToString("\n").trim()
                }
                line.startsWith("  ") && line.contains(":") -> {
                    val key = line.substringBefore(":").trim()
                    val rawValue = line.substringAfter(":").trim()
                    if (isPromptSectionKey(key)) {
                        when {
                            rawValue == "|" -> {
                                val block = mutableListOf<String>()
                                index += 1
                                while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith("    "))) {
                                    block += if (lines[index].isBlank()) "" else lines[index].removePrefix("    ")
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
        return ParsedPersonaYaml(
            values = values,
            fewShots = parseFewShots(lines),
            outputPolicy = parseOutputPolicy(lines),
        )
    }

    private fun parseFewShots(lines: List<String>): List<PersonaFewShot> {
        val sectionStart = lines.indexOfFirst { it == "few_shots:" }
        if (sectionStart < 0) return emptyList()

        val shots = mutableListOf<PersonaFewShot>()
        var phase: io.openeden.relationship.RelationshipPhase? = null
        var messages = mutableListOf<PersonaExampleMessage>()
        var role: PersonaExampleRole? = null

        fun finishShot() {
            val currentPhase = phase ?: return
            shots += PersonaFewShot(currentPhase, messages.toList())
            phase = null
            messages = mutableListOf()
        }

        var index = sectionStart + 1
        while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith("  "))) {
            val line = lines[index]
            when {
                line.startsWith("  - phase:") -> {
                    finishShot()
                    phase = line.valueAfterColon().parseRelationshipPhase()
                }
                line.startsWith("      - role:") -> role = line.valueAfterColon().parseExampleRole()
                line.startsWith("        content:") -> {
                    val currentRole = requireNotNull(role) {
                        "Persona example content requires a preceding role"
                    }
                    messages += PersonaExampleMessage(currentRole, line.valueAfterColon())
                    role = null
                }
            }
            index += 1
        }
        finishShot()
        return shots
    }

    private fun parseOutputPolicy(lines: List<String>): PersonaOutputPolicy {
        val sectionStart = lines.indexOfFirst { it == "output_policy:" }
        if (sectionStart < 0) return PersonaOutputPolicy()

        val phrases = linkedSetOf<String>()
        val patterns = linkedSetOf<String>()
        var maximumRepeatedOpening = Int.MAX_VALUE
        var target = OutputPolicyList.NONE
        var index = sectionStart + 1
        while (index < lines.size && (lines[index].isBlank() || lines[index].startsWith("  "))) {
            val line = lines[index]
            when {
                line.startsWith("  prohibited_public_phrases:") -> target = OutputPolicyList.PHRASES
                line.startsWith("  prohibited_public_patterns:") -> target = OutputPolicyList.PATTERNS
                line.startsWith("    - ") -> {
                    val value = line.trimStart().removePrefix("- ").trim().trim('"')
                    when (target) {
                        OutputPolicyList.PHRASES -> phrases += value
                        OutputPolicyList.PATTERNS -> patterns += value
                        OutputPolicyList.NONE -> error("Output policy item requires a list key")
                    }
                }
                line.startsWith("  maximum_repeated_opening:") -> {
                    maximumRepeatedOpening = line.valueAfterColon().toInt()
                }
            }
            index += 1
        }
        return PersonaOutputPolicy(
            prohibitedPublicPhrases = phrases,
            prohibitedPublicPatterns = patterns,
            maximumRepeatedOpening = maximumRepeatedOpening,
        )
    }

    private fun isPromptSectionKey(key: String): Boolean =
        key.startsWith("persona.") || key.startsWith("output.") ||
            key.startsWith("heartbeat.") || key.startsWith("style.") ||
            key.startsWith("diary.") || key.startsWith("internal_logic.")

    private fun String.isSequenceItem(): Boolean = trimStart().startsWith("- ")

    private fun String.valueAfterColon(): String = substringAfter(":").trim().trim('"')

    private fun String.parseRelationshipPhase(): io.openeden.relationship.RelationshipPhase =
        io.openeden.relationship.RelationshipPhase.valueOf(uppercase())

    private fun String.parseExampleRole(): PersonaExampleRole = PersonaExampleRole.valueOf(uppercase())

    private data class ParsedPersonaYaml(
        val values: Map<String, String>,
        val fewShots: List<PersonaFewShot>,
        val outputPolicy: PersonaOutputPolicy,
    )

    private enum class OutputPolicyList {
        NONE,
        PHRASES,
        PATTERNS,
    }
}
