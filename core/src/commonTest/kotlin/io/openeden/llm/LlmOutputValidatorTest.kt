package io.openeden.llm

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
}
