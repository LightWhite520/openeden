package io.openeden.llm

import io.openeden.codebook.QuantizationResult

data class LlmGroundingValidationResult(
    val isGrounded: Boolean,
    val errors: List<String>,
)

object LlmGroundingValidation {
    fun validate(
        output: LlmOutput,
        quantization: QuantizationResult,
    ): LlmGroundingValidationResult {
        if (quantization.activeNodes.isEmpty()) {
            return LlmGroundingValidationResult(
                isGrounded = false,
                errors = listOf("quantization produced no active codebook nodes"),
            )
        }
        val grounded = quantization.activeNodes.any { node ->
            Regex("(?<![A-Za-z0-9_])${Regex.escape(node)}(?![A-Za-z0-9_])")
                .containsMatchIn(output.internalLogic)
        }
        return if (grounded) {
            LlmGroundingValidationResult(isGrounded = true, errors = emptyList())
        } else {
            LlmGroundingValidationResult(
                isGrounded = false,
                errors = listOf(
                    "internal_logic must reference an active codebook node: ${quantization.activeNodes.joinToString()}",
                ),
            )
        }
    }
}
