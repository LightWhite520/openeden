package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.OpenAiCachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmGenerationConfigTest {
    @Test
    fun `custom provider defaults to relay append only cache policy`() {
        val config = MapApplicationConfig(
            "openeden.llm.baseUrl" to "https://relay.example.com/v1",
        )

        assertEquals(OpenAiCachePolicy.RELAY_APPEND_ONLY, loadOpenAiCacheBootstrapConfig(config).policy)
    }

    @Test
    fun `cache policy is explicitly operator overridable`() {
        val config = MapApplicationConfig(
            "openeden.llm.promptCachingPolicy" to "official_explicit",
            "openeden.llm.capabilityProbe.enabled" to "true",
        )

        assertEquals(OpenAiCachePolicy.OFFICIAL_EXPLICIT, loadOpenAiCacheBootstrapConfig(config).policy)
    }

    @Test
    fun `official explicit cache policy requires capability probing`() {
        val error = assertFailsWith<IllegalArgumentException> {
            loadOpenAiCacheBootstrapConfig(
                MapApplicationConfig(
                    "openeden.llm.promptCachingPolicy" to "official_explicit",
                    "openeden.llm.capabilityProbe.enabled" to "false",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("OPENEDEN_OPENAI_PROMPT_CACHING_POLICY=official_explicit"))
        assertTrue(error.message.orEmpty().contains("OPENEDEN_OPENAI_CAPABILITY_PROBE_ENABLED=true"))
    }

    @Test
    fun `official explicit cache policy loads when capability probing is enabled`() {
        val loaded = loadOpenAiCacheBootstrapConfig(
            MapApplicationConfig(
                "openeden.llm.promptCachingPolicy" to "official_explicit",
                "openeden.llm.capabilityProbe.enabled" to "true",
            ),
        )

        assertEquals(OpenAiCachePolicy.OFFICIAL_EXPLICIT, loaded.policy)
        assertEquals(true, loaded.capabilityProbeEnabled)
    }

    @Test
    fun `relay append only remains valid with capability probing disabled`() {
        val loaded = loadOpenAiCacheBootstrapConfig(MapApplicationConfig())

        assertEquals(OpenAiCachePolicy.RELAY_APPEND_ONLY, loaded.policy)
        assertEquals(false, loaded.capabilityProbeEnabled)
    }

    @Test
    fun `application config defaults to relay append only production mode`() {
        val configText = assertNotNull(
            javaClass.classLoader.getResourceAsStream("application.yaml"),
        ).bufferedReader().use { it.readText() }
        val configuredValue = assertNotNull(
            Regex("""(?m)^\s*promptCachingPolicy:\s*"\${'$'}OPENEDEN_OPENAI_PROMPT_CACHING_POLICY:([^\"]+)"\s*$""")
                .find(configText)
                ?.groupValues
                ?.get(1),
        )

        assertEquals("relay_append_only", configuredValue)
    }

    @Test
    fun `application config documents official explicit probe requirement and env vars`() {
        val configText = assertNotNull(
            javaClass.classLoader.getResourceAsStream("application.yaml"),
        ).bufferedReader().use { it.readText() }

        assertTrue(configText.contains("OFFICIAL_EXPLICIT requires capability probing"))
        assertTrue(configText.contains("OPENEDEN_OPENAI_PROMPT_CACHING_POLICY=official_explicit"))
        assertTrue(configText.contains("OPENEDEN_OPENAI_CAPABILITY_PROBE_ENABLED=true"))
    }

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
    fun `application config defaults to the production ATRI persona`() {
        val configText = assertNotNull(
            javaClass.classLoader.getResourceAsStream("application.yaml"),
        ).bufferedReader().use { it.readText() }
        val configuredValue = assertNotNull(
            Regex("""(?m)^\s*personaPath:\s*"\${'$'}OPENEDEN_PERSONA_PATH:([^\"]+)"\s*$""")
                .find(configText)
                ?.groupValues
                ?.get(1),
        )

        assertEquals("persona/atri.yaml", configuredValue)
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
