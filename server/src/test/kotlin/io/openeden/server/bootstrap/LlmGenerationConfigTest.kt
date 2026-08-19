package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import io.openeden.llm.LlmGenerationPolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LlmGenerationConfigTest {
    @Test
    fun `missing generation settings use policy defaults`() {
        assertEquals(
            LlmGenerationPolicyConfig.Default,
            loadLlmGenerationPolicyConfig(MapApplicationConfig()),
        )
    }

    @Test
    fun `configured generation settings load from application config`() {
        val config = MapApplicationConfig(
            "openeden.llm.temperatureMin" to "0.1",
            "openeden.llm.temperatureMax" to "1.4",
            "openeden.llm.maxOutputTokens" to "32000",
        )

        assertEquals(
            LlmGenerationPolicyConfig(
                temperatureMin = 0.1f,
                temperatureMax = 1.4f,
                maxOutputTokens = 32000,
            ),
            loadLlmGenerationPolicyConfig(config),
        )
    }

    @Test
    fun `application config uses an optional max output tokens environment variable without a default`() {
        val configText = assertNotNull(
            javaClass.classLoader.getResourceAsStream("application.yaml"),
        ).bufferedReader().use { it.readText() }
        val configuredValue = assertNotNull(
            Regex("""(?m)^\s*maxOutputTokens:\s*"([^"]*)"\s*$""")
                .find(configText)
                ?.groupValues
                ?.get(1),
        )

        assertEquals("\$?OPENEDEN_LLM_MAX_OUTPUT_TOKENS", configuredValue)
    }

    @Test
    fun `malformed temperature is rejected`() {
        val config = MapApplicationConfig("openeden.llm.temperatureMin" to "warm")

        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(config)
        }
    }

    @Test
    fun `non-positive max output tokens are rejected`() {
        val config = MapApplicationConfig("openeden.llm.maxOutputTokens" to "0")

        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(config)
        }
    }

    @Test
    fun `policy validation rejects reversed or non-finite temperature bounds`() {
        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(
                MapApplicationConfig(
                    "openeden.llm.temperatureMin" to "1.1",
                    "openeden.llm.temperatureMax" to "0.2",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(
                MapApplicationConfig("openeden.llm.temperatureMax" to "NaN"),
            )
        }
    }
}
