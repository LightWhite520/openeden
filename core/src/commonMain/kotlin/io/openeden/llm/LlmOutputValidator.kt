package io.openeden.llm

import io.openeden.bio.VectorDelta
import io.openeden.persona.PersonaOutputPolicy

object LlmOutputValidator {
    private val requiredKeys = setOf("L", "P", "E", "S", "tau", "V", "M", "F")

    fun validate(
        output: LlmOutput,
        emotionConfidence: Float? = null,
    ): LlmValidationResult = validateWithPolicy(output, null, emotionConfidence = emotionConfidence)

    fun validate(
        output: LlmOutput,
        policy: PersonaOutputPolicy,
        recentAssistantResponses: List<String> = emptyList(),
        emotionConfidence: Float? = null,
    ): LlmValidationResult = validateWithPolicy(
        output,
        policy,
        recentAssistantResponses,
        emotionConfidence,
    )

    private fun validateWithPolicy(
        output: LlmOutput,
        policy: PersonaOutputPolicy?,
        recentAssistantResponses: List<String> = emptyList(),
        emotionConfidence: Float? = null,
    ): LlmValidationResult {
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
        output.vectorDelta.forEach { (key, value) ->
            if (!value.isFinite() || value !in -1.0f..1.0f) {
                errors += "vector_delta.$key must be finite and within [-1.0, 1.0]"
            }
        }
        if (emotionConfidence != null &&
            (!emotionConfidence.isFinite() || emotionConfidence !in 0.0f..1.0f)
        ) {
            errors += "emotion_confidence must be finite and within [0.0, 1.0]"
        }
        if (policy != null && output.response.isNotBlank()) {
            if (policy.prohibitedPublicPhrases.any(output.response::contains)) {
                errors += "response contains persona-prohibited public language"
            }
            if (policy.prohibitedPublicPatterns.any { pattern -> Regex(pattern).containsMatchIn(output.response) }) {
                errors += "response matches a persona-prohibited public pattern"
            }
            val opening = output.response.normalizedOpening()
            if (opening != null) {
                val repeated = recentAssistantResponses.count { previous ->
                    previous.normalizedOpening() == opening
                } + 1
                if (repeated > policy.maximumRepeatedOpening) {
                    errors += "response repeats a recent assistant opening"
                }
            }
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

    private fun String.normalizedOpening(): String? {
        val normalized = trim()
            .trimStart('"', '\'', '“', '‘', '（', '(', '【', '[')
            .lowercase()
        if (normalized.isEmpty()) return null
        val boundary = OPENING_BOUNDARY.find(normalized)?.range?.first ?: normalized.length
        return normalized.substring(0, boundary)
            .filterNot(Char::isWhitespace)
            .takeIf(String::isNotEmpty)
    }

    private val OPENING_BOUNDARY = Regex("[，,。.!！?？；;：:\\n]")

}
