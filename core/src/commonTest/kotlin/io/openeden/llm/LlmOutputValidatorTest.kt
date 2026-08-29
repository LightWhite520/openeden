package io.openeden.llm

import io.openeden.codebook.QuantizationResult
import io.openeden.persona.PersonaOutputPolicy
import kotlinx.coroutines.test.runTest

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
    fun `public response rejects leaked operational vocabulary`() {
        val result = LlmOutputValidator.validate(
            validOutput(response = "收到，已经登记进库存"),
            atriPolicy(),
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `rejects every nonfinite or out of protocol range vector coordinate`() {
        val invalidValues = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1.01f,
            -1.01f,
        )

        validDelta().keys.forEach { key ->
            invalidValues.forEach { invalid ->
                val output = validOutput(response = "response").copy(
                    vectorDelta = validDelta().toMutableMap().apply { put(key, invalid) },
                )

                val result = LlmOutputValidator.validate(output)

                assertFalse(result.isValid, "$key=$invalid must be rejected")
                assertEquals(null, result.delta)
            }
        }
    }

    @Test
    fun `rejects nonfinite or out of range emotion confidence`() {
        listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1.01f,
            -0.01f,
        ).forEach { invalid ->
            val result = LlmOutputValidator.validate(
                output = validOutput(response = "response"),
                emotionConfidence = invalid,
            )

            assertFalse(result.isValid, "confidence=$invalid must be rejected")
            assertEquals(null, result.delta)
        }
    }

    @Test
    fun `public response rejects matching persona pattern`() {
        val policy = PersonaOutputPolicy(
            prohibitedPublicPatterns = setOf("^收到[。！!\\s]*$"),
            maximumRepeatedOpening = 1,
        )

        assertFalse(LlmOutputValidator.validate(validOutput("收到。"), policy).isValid)
        assertTrue(LlmOutputValidator.validate(validOutput("我收到的是一段损坏的数据。"), policy).isValid)
    }

    @Test
    fun `public response rejects opening repeated across recent assistant turns`() {
        val policy = PersonaOutputPolicy(maximumRepeatedOpening = 1)

        val result = LlmOutputValidator.validate(
            output = validOutput("好吧，这次我来处理。"),
            policy = policy,
            recentAssistantResponses = listOf("好吧，上次是我算错了。", "先检查接线。"),
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `public response accepts a fresh opening after recent assistant turns`() {
        val policy = PersonaOutputPolicy(maximumRepeatedOpening = 1)

        val result = LlmOutputValidator.validate(
            output = validOutput("这次我来处理。"),
            policy = policy,
            recentAssistantResponses = listOf("先检查接线。", "好吧，上次是我算错了。"),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun `response rewrite runs once only for schema valid policy violations`() = runTest {
        var calls = 0
        val rewriter = PersonaResponseRewriter { output, _ ->
            calls += 1
            output.copy(
                internalLogic = "must not replace private logic",
                vectorDelta = output.vectorDelta.mapValues { 1.0f },
                response = "饭已经放好，趁热吃。",
            )
        }
        val original = validOutput(response = "收到，已经登记进库存")

        val rewritten = rewriter.rewriteIfNeeded(original, atriPolicy())

        assertEquals(1, calls)
        assertEquals(original.internalLogic, rewritten.internalLogic)
        assertEquals(original.vectorDelta, rewritten.vectorDelta)
        assertEquals("饭已经放好，趁热吃。", rewritten.response)
    }

    @Test
    fun `response rewrite does not run for schema invalid output`() = runTest {
        var calls = 0
        val rewriter = PersonaResponseRewriter { output, _ ->
            calls += 1
            output.copy(response = "rewritten")
        }
        val invalid = validOutput(response = "收到，已经登记进库存").copy(internalLogic = "")

        val unchanged = rewriter.rewriteIfNeeded(invalid, atriPolicy())

        assertEquals(0, calls)
        assertEquals(invalid, unchanged)
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

    private fun validOutput(response: String): LlmOutput = LlmOutput(
        internalLogic = "uses NODE_12",
        vectorDelta = validDelta(),
        response = response,
    )

    private fun atriPolicy(): PersonaOutputPolicy = PersonaOutputPolicy(
        prohibitedPublicPhrases = setOf("登记进库存"),
        maximumRepeatedOpening = 1,
    )
}
