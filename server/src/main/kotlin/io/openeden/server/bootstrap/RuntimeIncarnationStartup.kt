package io.openeden.server.bootstrap

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.state.VectorWriteService
import io.openeden.transcript.TranscriptStore

internal suspend fun initializeRuntimeIncarnation(
    lifecycle: IncarnationLifecycle,
    transcriptStore: TranscriptStore,
    writer: VectorWriteService,
    personaMode: PersonaMode,
    personaStartSubState: PersonaSubState,
) {
    if (lifecycle != IncarnationLifecycle.ACTIVE && lifecycle != IncarnationLifecycle.CRITICAL) return
    writer.readOrCreateIncarnation(
        transcriptStore.activeIncarnation().id,
        personaMode,
        personaStartSubState,
    )
}
