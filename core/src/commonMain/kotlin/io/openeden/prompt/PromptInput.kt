package io.openeden.prompt

import io.openeden.bio.BioVector
import io.openeden.codebook.QuantizationResult
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.UserAffectState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState

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
    val userAffect: UserAffectState = UserAffectState.Uncertain,
    val relationshipState: RelationshipState? = null,
)
