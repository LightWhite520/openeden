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
    fun `returns null for missing node`() {
        val dictionary = CodebookDictionary.parseCsv("node_id,definition,tags")

        assertNull(dictionary.definitionFor("NODE_999"))
    }
}
