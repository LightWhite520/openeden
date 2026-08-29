package io.openeden.server.maintenance

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.lifecycle.IncarnationLifecycle

data class IncarnationResetResult(
    val requestId: String,
    val previousIncarnationId: String,
    val activeIncarnationId: String,
    val lifecycle: IncarnationLifecycle,
    val personaMode: PersonaMode,
    val personaStartSubState: PersonaSubState,
    val completedAtMs: Long,
)
