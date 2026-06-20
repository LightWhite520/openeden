package io.openeden.llm

import io.openeden.bio.VectorDelta
import io.openeden.prompt.BuiltPrompt

interface LlmClient {
    suspend fun complete(prompt: BuiltPrompt): LlmOutput
}

data class LlmOutput(
    val internalLogic: String,
    val vectorDelta: Map<String, Float>,
    val response: String,
)

data class LlmValidationResult(
    val isValid: Boolean,
    val output: LlmOutput?,
    val delta: VectorDelta?,
    val errors: List<String>,
)

object LlmOutputValidator {
    private val requiredKeys = setOf("L", "P", "E", "S", "tau", "V", "M", "F")

    fun validate(output: LlmOutput): LlmValidationResult {
        val errors = mutableListOf<String>()
        if (output.internalLogic.isBlank()) {
            errors += "internal_logic is required"
        }
        if (output.response.isBlank()) {
            errors += "response is required"
        }
        if (output.vectorDelta.keys != requiredKeys) {
            errors += "vector_delta must contain exactly L, P, E, S, tau, V, M, F"
        }
        if ("D" in output.vectorDelta.keys) {
            errors += "D must not appear in vector_delta"
        }

        val delta = if (errors.isEmpty()) {
            VectorDelta(
                l = output.vectorDelta.getValue("L"),
                p = output.vectorDelta.getValue("P"),
                e = output.vectorDelta.getValue("E"),
                s = output.vectorDelta.getValue("S"),
                tau = output.vectorDelta.getValue("tau"),
                v = output.vectorDelta.getValue("V"),
                m = output.vectorDelta.getValue("M"),
                f = output.vectorDelta.getValue("F"),
            )
        } else {
            null
        }

        return LlmValidationResult(
            isValid = errors.isEmpty(),
            output = output.takeIf { errors.isEmpty() },
            delta = delta,
            errors = errors,
        )
    }
}

class DevelopmentLlmStub : LlmClient {
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
        internalLogic = "Development stub response based on injected codebook state.",
        vectorDelta = mapOf(
            "L" to 0.0f,
            "P" to 0.0f,
            "E" to 0.0f,
            "S" to 0.0f,
            "tau" to 0.0f,
            "V" to 0.0f,
            "M" to 0.0f,
            "F" to 0.0f,
        ),
        response = "OpenEden development response.",
    )
}
