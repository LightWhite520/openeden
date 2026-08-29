package io.openeden.server.maintenance

import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import java.nio.file.Path

data class IncarnationResetRequest(
    val incarnationId: String,
    val requestId: String,
    val manifestPath: Path,
    val confirmed: Boolean,
    val personaMode: PersonaMode,
    val personaStartSubState: PersonaSubState,
)

enum class IncarnationResetRejection {
    BLANK_REQUEST_ID,
    CONFIRMATION_REQUIRED,
    STALE_INCARNATION_ID,
    EXPORT_INCARNATION_MISMATCH,
    EXPORT_INCOMPLETE,
    EXPORT_HASH_INVALID,
    EXPORTED_STATE_CHANGED,
    REQUEST_ID_CONFLICT,
}

class IncarnationResetRejectedException(
    val reason: IncarnationResetRejection,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
