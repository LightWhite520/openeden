package io.openeden.server.maintenance

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState

enum class IncarnationResetPhase {
    PREPARED,
    PROJECTIONS_VERIFIED,
    COMPLETED,
}

data class PreparedIncarnationReset(
    val requestId: String,
    val previousIncarnationId: String,
    val freshIncarnationId: String,
    val manifestSha256: String,
    val manifestPath: String,
    val payloadSha256: String,
    val personaMode: PersonaMode,
    val personaStartSubState: PersonaSubState,
    val confirmed: Boolean,
    val phase: IncarnationResetPhase,
    val projectionModelIds: Set<String>,
    val preparedAtMs: Long,
    val completedAtMs: Long?,
)
