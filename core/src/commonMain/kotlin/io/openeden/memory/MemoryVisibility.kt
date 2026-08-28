package io.openeden.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MemoryVisibility {
    fun permits(canonicalSubjectId: String, sessionId: String): Boolean

    @Serializable
    @SerialName("private_subject")
    data class PrivateSubject(
        val subjectId: String,
    ) : MemoryVisibility {
        override fun permits(canonicalSubjectId: String, sessionId: String): Boolean =
            subjectId == canonicalSubjectId
    }

    @Serializable
    @SerialName("scope_shared")
    data class ScopeShared(
        val sessionId: String,
    ) : MemoryVisibility {
        override fun permits(canonicalSubjectId: String, sessionId: String): Boolean =
            this.sessionId == sessionId
    }

    @Serializable
    @SerialName("incarnation_shared")
    data object IncarnationShared : MemoryVisibility {
        override fun permits(canonicalSubjectId: String, sessionId: String): Boolean = true
    }

    @Serializable
    @SerialName("operator_only")
    data object OperatorOnly : MemoryVisibility {
        override fun permits(canonicalSubjectId: String, sessionId: String): Boolean = false
    }
}

fun MemoryEntry.isVisibleTo(request: RetrievalRequest): Boolean {
    val metadata = metadata
    if (metadata.incarnationId.isNotBlank() && request.incarnationId.isNotBlank() &&
        metadata.incarnationId != request.incarnationId
    ) {
        return false
    }
    val visibility = when (val configured = metadata.visibility) {
        is MemoryVisibility.ScopeShared -> configured.takeIf { it.sessionId.isNotBlank() }
            ?: MemoryVisibility.ScopeShared(sessionId)
        else -> configured
    }
    return visibility.permits(request.canonicalSubjectId, request.sessionId)
}
