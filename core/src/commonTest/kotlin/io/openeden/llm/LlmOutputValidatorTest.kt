package io.openeden.llm

import io.openeden.codebook.QuantizationResult

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmOutputValidatorTest {
    @Test
    fun `accepts full eight key vector delta`() {
        val result = LlmOutputValidator.validate(
            LlmOutput(
                internalLogic = "logic",
                vectorDelta = mapOf(
                    "L" to 0.0f,
                    "P" to 0.1f,
                    "E" to 0.0f,
                    "S" to 0.0f,
                    "tau" to 0.0f,
                    "V" to 0.0f,
                    "M" to 0.0f,
                    "F" to 0.0f,
                ),
                response = "response",
            ),
        )

        assertTrue(result.isValid)
        assertEquals(0.1f, result.delta?.p)
    }

    @Test
    fun `rejects missing key and derived D key`() {
        val result = LlmOutputValidator.validate(
            LlmOutput(
                internalLogic = "logic",
                vectorDelta = mapOf("L" to 0.0f, "D" to 0.5f),
                response = "response",
            ),
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `grounding accepts an exact active node and rejects missing nodes`() {
        val quantization = QuantizationResult(
            activeNodes = listOf("NODE_12", "NODE_45"),
            semanticDefinitions = emptyList(),
            confidence = 0.9f,
        )

        assertTrue(
            LlmGroundingValidation.validate(
                LlmOutput("uses NODE_12", validDelta(), "response"),
                quantization,
            ).isGrounded,
        )
        assertFalse(
            LlmGroundingValidation.validate(
                LlmOutput("logic", validDelta(), "response"),
                quantization,
            ).isGrounded,
        )
    }

    @Test
    fun `grounding does not accept a node identifier embedded in another token`() {
        val result = LlmGroundingValidation.validate(
            LlmOutput("NODE_120 is active", validDelta(), "response"),
            QuantizationResult(listOf("NODE_12"), emptyList(), 1.0f),
        )

        assertFalse(result.isGrounded)
    }

    private fun validDelta(): Map<String, Float> = mapOf(
        "L" to 0.0f,
        "P" to 0.0f,
        "E" to 0.0f,
        "S" to 0.0f,
        "tau" to 0.0f,
        "V" to 0.0f,
        "M" to 0.0f,
        "F" to 0.0f,
    )
}
