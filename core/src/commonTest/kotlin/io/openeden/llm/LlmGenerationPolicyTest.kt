package io.openeden.llm

import io.openeden.bio.InternalBioVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmGenerationPolicyTest {
    @Test
    fun `maps focused divergent and centered entropy-logical states to temperature range`() {
        val config = LlmGenerationPolicyConfig(temperatureMin = 0.2f, temperatureMax = 1.0f)

        assertEquals(0.2f, LlmGenerationPolicy.resolve(vector(l = 1.0f, s = -1.0f), config).temperature)
        assertEquals(1.0f, LlmGenerationPolicy.resolve(vector(l = -1.0f, s = 1.0f), config).temperature)
        assertEquals(0.6f, LlmGenerationPolicy.resolve(vector(l = 0.0f, s = 0.0f), config).temperature)
    }

    @Test
    fun `maps vitality thresholds to verbosity`() {
        val config = LlmGenerationPolicyConfig()

        assertEquals(LlmVerbosity.LOW, LlmGenerationPolicy.resolve(vector(v = -0.35f), config).verbosity)
        assertEquals(LlmVerbosity.HIGH, LlmGenerationPolicy.resolve(vector(v = 0.35f), config).verbosity)
        assertEquals(LlmVerbosity.MEDIUM, LlmGenerationPolicy.resolve(vector(v = 0.0f), config).verbosity)
    }

    @Test
    fun `static settings use midpoint medium verbosity and configured token limit`() {
        val config = LlmGenerationPolicyConfig(
            temperatureMin = 0.4f,
            temperatureMax = 0.8f,
            maxOutputTokens = 512,
        )

        assertEquals(
            LlmGenerationSettings(temperature = 0.6f, verbosity = LlmVerbosity.MEDIUM, maxOutputTokens = 512),
            config.staticSettings(),
        )
    }

    @Test
    fun `rejects invalid configuration bounds and token limits`() {
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(temperatureMin = -0.1f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(temperatureMax = 2.1f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(temperatureMin = 1.1f, temperatureMax = 1.0f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(temperatureMin = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(temperatureMax = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(maxOutputTokens = 0) }
    }

    private fun vector(
        l: Float = 0.0f,
        s: Float = 0.0f,
        v: Float = 0.0f,
    ): InternalBioVector = InternalBioVector(
        l = l,
        p = 0.0f,
        e = 0.0f,
        s = s,
        tau = 0.0f,
        v = v,
        m = 0.0f,
        f = 0.0f,
    )
}
