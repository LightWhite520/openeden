package io.openeden.runtime.session

import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState
import io.openeden.persona.PersonaSubState
import io.openeden.persona.PersonaMode

import io.openeden.bio.BioVector

data class SessionState(
    val sessionId: String,
    val vector: BioVector,
    val origin: BioVector,
    val omega: OmegaState,
    val shockState: ShockState?,
    val evolutionIndex: Long,
    val personaMode: PersonaMode = PersonaMode.GROWTH,
    val personaStartSubState: PersonaSubState = PersonaSubState.PRE_COMMAND,
    // Epoch-millis of the last USER-initiated turn. Drives the heartbeat silence gates (§9.3).
    // Null = no user turn observed yet. Heartbeat turns MUST NOT update it.
    val lastUserActivityMs: Long? = null,
) {
    init {
        require(personaMode != PersonaMode.LEGACY || personaStartSubState == PersonaSubState.AWAKENED) {
            "Legacy session mode only supports the awakened starting point"
        }
    }
}
