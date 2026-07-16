package io.openeden.persona


import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards persona/atri.yaml against two failure modes:
 *  - structural: required + distilled-style sections must load and be non-blank.
 *  - copyright: no verbatim source-language dialogue may be pasted into the public repo.
 *
 * The copyright checks are intentionally heuristic. They cannot prove a line is original,
 * but they catch the realistic mistake: pasting Japanese VN text or long quoted dialogue
 * spans directly into the persona file. Distilled, non-verbatim Chinese rules pass.
 */
class AtriPersonaGuardTest {
    private val requiredSections = listOf(
        "persona.base",
        "persona.behavior",
        "output.layer.rules",
        "persona.patch.pre_command",
        "persona.patch.true_self",
        "persona.patch.awakened",
        "heartbeat.base",
        "heartbeat.shock",
        "style.observed_summary",
        "style.source_language_notes",
        "style.generation_mechanics",
        "style.signature_examples",
        "style.stage_examples.pre_command",
        "style.stage_examples.true_self",
        "style.stage_examples.awakened",
        "style.do",
        "style.do_not",
    )

    private val atriYaml: Path = locatePersonaFile("persona/atri.yaml")

    @Test
    fun `atri persona loads with all required and style sections present`() {
        val config = PersonaFileLoader.load(atriYaml)

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(PersonaSubState.PRE_COMMAND, config.startSubState)
        requiredSections.forEach { key ->
            val value = config.promptSections[key]
            assertNotNull(value, "Missing persona section: $key")
            assertTrue(value.isNotBlank(), "Blank persona section: $key")
        }
    }

    @Test
    fun `atri persona has the required original example distribution`() {
        val config = PersonaFileLoader.load(atriYaml)
        val expectedCounts = linkedMapOf(
            "style.signature_examples" to 16,
            "style.stage_examples.pre_command" to 8,
            "style.stage_examples.true_self" to 8,
            "style.stage_examples.awakened" to 8,
        )
        val allBodies = mutableListOf<String>()

        expectedCounts.forEach { (section, expectedCount) ->
            val examples = parseExamples(config.promptSections.getValue(section))
            assertEquals(
                (1..expectedCount).map { it.toString().padStart(2, '0') },
                examples.map { it.number },
                "$section headings must be ordered, unique, and sequential",
            )
            examples.forEach { example ->
                assertTrue(example.body.isNotBlank(), "$section ${example.heading} must have a nonblank body")
            }
            allBodies += examples.map { it.body.replace(Regex("\\s+"), " ").trim() }
        }

        assertEquals(allBodies.size, allBodies.distinct().size, "Persona example bodies must be unique")
    }

    @Test
    fun `relationship sensitive examples are explicitly host only`() {
        val config = PersonaFileLoader.load(atriYaml)
        val sensitiveHeadingCategories = listOf("jealousy", "intimacy", "affection", "dependency", "ownership")
        val sensitiveBodySignals = listOf("嫉妒", "吃醋", "没有我也可以", "我仍然想留下", "散步时间留给我", "注意力赢回来")

        exampleSections(config).forEach { (section, block) ->
            parseExamples(block).forEach { example ->
                val relationshipSensitive = sensitiveHeadingCategories.any { it in example.heading.lowercase() } ||
                    sensitiveBodySignals.any { it in example.body }
                if (relationshipSensitive) {
                    assertTrue(
                        "HOST only" in example.heading,
                        "$section ${example.heading} assumes intimate dependency, jealousy, or ownership without HOST only",
                    )
                }
            }
        }
    }

    @Test
    fun `atri persona excludes source names and recognizable dialogue fingerprints`() {
        val text = Files.readString(atriYaml)
        val sourceNames = listOf("夏生", "水菜萌", "龙司", "凯瑟琳", "凛凛花", "安田", "诗菜", "洋子", "美代")
        sourceNames.forEach { name ->
            assertTrue(name !in text, "atri.yaml must not contain source character name: $name")
        }

        // SHA-256 fingerprints cover normalized Chinese variants of the recognizable source
        // catchphrase without publishing source dialogue in the repository.
        val sourceFingerprints = setOf(
            "710b0f886b394e27cc7919d31f2d81f59fc7c1549e941b498d66aa597a362e48",
            "1a8070a36fbcf26aeba53a7156a42fd423185c01c8163081960904bcd4fe1366",
            "f43b8591e4013235079e7e979a76ded4f7193003fe6b309b3c971dad6542f5ae",
            "f5d757787681ed506d94f6d3c53fcc4576e21d951f940e26fac4b06f5ccfbd5a",
        )
        val clauseFingerprints = text
            .split(Regex("[\\s，。！？；、…]+"))
            .filter { it.isNotBlank() }
            .map(::sha256)
            .toSet()
        assertTrue(
            sourceFingerprints.intersect(clauseFingerprints).isEmpty(),
            "atri.yaml contains a recognizable source-dialogue fingerprint",
        )
    }

    @Test
    fun `atri persona contains no japanese kana`() {
        // Hiragana (U+3040–U+309F) and Katakana (U+30A0–U+30FF) are unmistakable markers of
        // pasted Japanese source text. Chinese output legitimately shares Han characters, so we
        // cannot ban those — but kana has no place in a distilled Simplified-Chinese persona.
        val kana = Regex("[\\u3040-\\u30FF]")
        val text = Files.readString(atriYaml)
        val match = kana.find(text)
        if (match != null) {
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            fail("Japanese kana found at line $line — possible verbatim source text leaked into atri.yaml")
        }
    }

    @Test
    fun `atri persona has no oversized quoted dialogue spans`() {
        // A long uninterrupted run inside quote marks is the signature of pasted dialogue.
        // Distilled rules use short quoted fragments at most; 60 chars is a generous ceiling.
        val maxQuotedSpan = 60
        val quotePairs = listOf('“' to '”', '「' to '」', '『' to '』', '"' to '"')
        val text = Files.readString(atriYaml)

        quotePairs.forEach { (open, close) ->
            val regex = Regex("\\Q$open\\E([^$close]*)\\Q$close\\E")
            regex.findAll(text).forEach { m ->
                val span = m.groupValues[1]
                assertTrue(
                    span.length <= maxQuotedSpan,
                    "Quoted span of ${span.length} chars exceeds $maxQuotedSpan — looks like pasted dialogue: " +
                        span.take(40) + "…",
                )
            }
        }
    }

    @Test
    fun `atri persona writes hard constraints in english`() {
        val text = Files.readString(atriYaml)
        listOf("必须", "不得", "禁止").forEach { marker ->
            assertTrue(marker !in text, "Chinese hard-constraint marker found in atri.yaml: $marker")
        }
        assertTrue(
            text.lineSequence().none { it.trimStart().startsWith("不要") },
            "Chinese hard-constraint line found in atri.yaml: 不要",
        )
    }

    @Test
    fun `atri persona treats host identity as authoritative runtime context`() {
        val config = PersonaFileLoader.load(atriYaml)
        val identity = config.promptSections.getValue("persona.identity")

        assertTrue("The current user is your host" !in identity)
        assertTrue(
            "relationship_role is HOST" in identity,
            "persona.identity must condition host semantics on relationship_role",
        )
        listOf("heartbeat.base", "heartbeat.shock").forEach { key ->
            assertTrue(
                "宿主" !in config.promptSections.getValue(key),
                "$key must not assume its intended recipient is the host",
            )
        }
    }

    private fun exampleSections(config: PersonaConfig): Map<String, String> = linkedMapOf(
        "style.signature_examples" to config.promptSections.getValue("style.signature_examples"),
        "style.stage_examples.pre_command" to config.promptSections.getValue("style.stage_examples.pre_command"),
        "style.stage_examples.true_self" to config.promptSections.getValue("style.stage_examples.true_self"),
        "style.stage_examples.awakened" to config.promptSections.getValue("style.stage_examples.awakened"),
    )

    private fun parseExamples(block: String): List<PersonaExample> {
        val pattern = Regex(
            "^### Example (\\d{2})[^\\r\\n]*\\R(.*?)(?=^### Example |\\z)",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.findAll(block).map { match ->
            val heading = match.value.lineSequence().first()
            val body = match.groupValues[2]
                .lineSequence()
                .takeWhile { !it.startsWith("MUST ") }
                .joinToString("\n")
                .trim()
            PersonaExample(match.groupValues[1], heading, body)
        }.toList()
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun locatePersonaFile(relative: String): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        fail("Could not locate $relative by walking up from ${Paths.get("").toAbsolutePath()}")
    }

    private data class PersonaExample(
        val number: String,
        val heading: String,
        val body: String,
    )
}
