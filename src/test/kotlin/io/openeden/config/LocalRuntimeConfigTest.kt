package io.openeden.config

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalRuntimeConfigTest {
    @Test
    fun `loads conservative defaults from empty environment`() {
        val config = LocalRuntimeConfig.fromEnv(emptyMap())

        assertEquals("local", config.localUserId)
        assertEquals(Path.of("persona", "default.yaml"), config.personaPath)
        assertEquals(Path.of("data", "runtime", "openeden.db"), config.runtimeDbPath)
        assertEquals("openai", config.llm.provider)
        assertEquals("gpt-5-mini", config.llm.model)
    }

    @Test
    fun `requires openai api key for openai provider`() {
        val error = assertFailsWith<IllegalArgumentException> {
            LocalRuntimeConfig.fromEnv(mapOf("OPENEDEN_LLM_PROVIDER" to "openai")).requireProviderCredentials()
        }

        assertEquals("OPENEDEN_OPENAI_API_KEY is required when OPENEDEN_LLM_PROVIDER=openai", error.message)
    }

    @Test
    fun `loads explicit openai settings`() {
        val config = LocalRuntimeConfig.fromEnv(
            mapOf(
                "OPENEDEN_LOCAL_USER_ID" to "owner",
                "OPENEDEN_PERSONA_PATH" to "persona/atri.yaml",
                "OPENEDEN_RUNTIME_DB_PATH" to "build/openeden-test.db",
                "OPENEDEN_OPENAI_API_KEY" to "sk-test",
                "OPENEDEN_OPENAI_MODEL" to "gpt-5.1",
                "OPENEDEN_OPENAI_BASE_URL" to "https://relay.example.com/v1",
            ),
        ).requireProviderCredentials()

        assertEquals("owner", config.localUserId)
        assertEquals(Path.of("persona/atri.yaml"), config.personaPath)
        assertEquals(Path.of("build/openeden-test.db"), config.runtimeDbPath)
        assertEquals("sk-test", config.llm.openAiApiKey)
        assertEquals("gpt-5.1", config.llm.model)
        assertEquals("https://relay.example.com/v1", config.llm.openAiBaseUrl)
    }
}
