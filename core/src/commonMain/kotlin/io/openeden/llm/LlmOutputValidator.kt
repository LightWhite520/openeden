package io.openeden.llm

import io.openeden.bio.VectorDelta

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
