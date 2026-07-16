package io.openeden.persona


import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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

    private fun locatePersonaFile(relative: String): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return candidate
            dir = dir.parent
        }
        fail("Could not locate $relative by walking up from ${Paths.get("").toAbsolutePath()}")
    }
}
