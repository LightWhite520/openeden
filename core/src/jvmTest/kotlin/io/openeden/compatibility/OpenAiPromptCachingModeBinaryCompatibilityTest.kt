package io.openeden.compatibility

import io.openeden.llm.OpenAiPromptCachingMode
import io.openeden.llm.supportsExplicitPromptCaching
import io.openeden.llm.usesCache
import io.openeden.llm.usesExplicitBreakpoint
import io.openeden.llm.usesExplicitCacheOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenAiPromptCachingModeBinaryCompatibilityTest {
    @Test
    fun `legacy enum fields remain Java visible`() {
        listOf("AUTO", "EXPLICIT", "DISABLED").forEach { name ->
            val expected = OpenAiPromptCachingMode.valueOf(name)
            assertSame(expected, OpenAiPromptCachingMode::class.java.getField(name).get(null))
        }
        assertEquals(listOf(0, 1, 2), listOf(
            OpenAiPromptCachingMode.AUTO.ordinal,
            OpenAiPromptCachingMode.EXPLICIT.ordinal,
            OpenAiPromptCachingMode.DISABLED.ordinal,
        ))
        assertTrue(OpenAiPromptCachingMode.AUTO.usesCache())
        assertTrue(OpenAiPromptCachingMode.EXPLICIT.usesExplicitCacheOptions("legacy-model"))
        assertTrue(OpenAiPromptCachingMode.AUTO.usesExplicitBreakpoint("gpt-5.6"))
        assertFalse(OpenAiPromptCachingMode.AUTO.usesExplicitBreakpoint("gpt-5.6", "https://relay.example.test/v1"))
        assertFalse(OpenAiPromptCachingMode.DISABLED.usesCache())
        assertTrue(supportsExplicitPromptCaching("gpt-5.6"))
    }
}
