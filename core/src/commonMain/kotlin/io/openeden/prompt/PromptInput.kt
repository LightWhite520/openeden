package io.openeden.prompt

import io.openeden.bio.BioVector
import io.openeden.codebook.QuantizationResult
import io.openeden.memory.RetrievalResult
import io.openeden.persona.PersonaConfig
import io.openeden.relationship.RelationshipState
import io.openeden.relationship.RelationshipRole
import io.openeden.relationship.UserAffectState
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.runtime.time.TemporalContext
import io.openeden.transcript.PromptHistorySnapshot

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
    val temporalContext: TemporalContext = TemporalContext(),
    val userAffect: UserAffectState = UserAffectState.Uncertain,
    val relationshipRole: RelationshipRole = RelationshipRole.INTERLOCUTOR,
    val relationshipAddress: String? = null,
    val relationshipState: RelationshipState? = null,
    val promptHistory: PromptHistorySnapshot = PromptHistorySnapshot(),
) {
    init {
        require(relationshipRole == RelationshipRole.HOST || relationshipAddress == null) {
            "Relationship address requires HOST role"
        }
        require(relationshipAddress == null || relationshipAddress.isNotBlank()) {
            "Relationship address must not be blank"
        }
    }
}
