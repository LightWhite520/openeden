package io.openeden.codebook


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodebookDictionaryTest {
    @Test
    fun `parses csv node definitions`() {
        val dictionary = CodebookDictionary.parseCsv(
            """
            node_id,definition,tags
            NODE_000,"Baseline neutral state","bootstrap;neutral"
            """.trimIndent(),
        )

        assertEquals("Baseline neutral state", dictionary.definitionFor("NODE_000"))
    }

    @Test
    fun `parses bilingual csv node definitions`() {
        val dictionary = CodebookDictionary.parseCsv(
            """
            node_id,definition_en,definition_zh,tags
            NODE_000,"Baseline neutral state","基线中性状态","bootstrap;neutral"
            """.trimIndent(),
        )

        assertEquals("EN: Baseline neutral state\nZH: 基线中性状态", dictionary.definitionFor("NODE_000"))
    }

    @Test
    fun `parses utf8 bom csv headers`() {
        val dictionary = CodebookDictionary.parseCsv(
            "\uFEFFnode_id,definition_en,definition_zh,tags\n" +
                "NODE_000,\"Baseline neutral state\",\"基线中性状态\",\"bootstrap;neutral\"",
        )

        assertEquals("EN: Baseline neutral state\nZH: 基线中性状态", dictionary.definitionFor("NODE_000"))
    }

    @Test
    fun `parses quoted fields containing escaped quotes and commas`() {
        val dictionary = CodebookDictionary.parseCsv(
            """
            node_id,definition_en,definition_zh,tags
            NODE_000,"Minimal overlap state","“对。先对，再别的。”","overlap;exactness;patched;cold;dialogue;minimal"
            """.trimIndent(),
        )

        assertEquals(
            "EN: Minimal overlap state\nZH: “对。先对，再别的。”",
            dictionary.definitionFor("NODE_000"),
        )
    }

    @Test
    fun `parses quoted fields containing newlines`() {
        val dictionary = CodebookDictionary.parseCsv(
            """
            node_id,definition_en,definition_zh,tags
            NODE_000,"Thin affect dialogue","“所以你只在乎对不对？”
            “对。先对，再别的。”","overlap;exactness;patched"
            """.trimIndent(),
        )

        assertEquals(
            "EN: Thin affect dialogue\nZH: “所以你只在乎对不对？”\n“对。先对，再别的。”",
            dictionary.definitionFor("NODE_000"),
        )
    }

    @Test
    fun `returns null for missing node`() {
        val dictionary = CodebookDictionary.parseCsv("node_id,definition,tags")

        assertNull(dictionary.definitionFor("NODE_999"))
    }
}
