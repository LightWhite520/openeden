package io.openeden.prompt

import io.openeden.bio.BioVector
import io.openeden.codebook.QuantizationResult
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.runtime.OmegaState
import io.openeden.runtime.ShockState

interface PromptBuilder {
    suspend fun build(input: PromptInput): BuiltPrompt
}

data class PromptInput(
    val personaConfig: PersonaConfig,
    val evolutionIndex: Long,
    val vectorSnapshot: BioVector,
    val derivedDissonance: Float,
    val quantization: QuantizationResult,
    val retrievalResult: RetrievalResult,
    val omegaState: OmegaState,
    val shockState: ShockState?,
    val userInput: String,
)

data class BuiltPrompt(
    val systemText: String,
    val personaText: String,
    val userText: String,
)
