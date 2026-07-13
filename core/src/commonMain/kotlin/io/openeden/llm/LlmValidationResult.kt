package io.openeden.llm

import io.openeden.bio.VectorDelta

data class LlmValidationResult(
    val isValid: Boolean,
    val output: LlmOutput?,
    val delta: VectorDelta?,
    val errors: List<String>,
)
