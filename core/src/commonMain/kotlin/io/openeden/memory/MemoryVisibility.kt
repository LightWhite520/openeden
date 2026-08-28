package io.openeden.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MemoryVisibility {
    fun permits(canonicalSubjectId: String, sessionId: String): Boolean

    fun permits(
        canonicalSubjectId: String,
        sessionId: String,
        operatorAuthorized: Boolean,
    ): Boolean = if (this is OperatorOnly) operatorAuthorized else permits(canonicalSubjectId, sessionId)

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
    return isVisibleTo(
        request.sessionId,
        request.canonicalSubjectId,
        request.incarnationId,
        request.operatorAuthorized,
    )
}

fun MemoryEntry.isVisibleTo(request: VectorSearchRequest): Boolean {
    return isVisibleTo(
        request.sessionId,
        request.canonicalSubjectId,
        request.incarnationId,
        request.operatorAuthorized,
    )
}

private fun MemoryEntry.isVisibleTo(
    requestSessionId: String,
    canonicalSubjectId: String,
    incarnationId: String,
    operatorAuthorized: Boolean,
): Boolean {
    val metadata = metadata
    if (incarnationId.isBlank() || metadata.incarnationId.isBlank() || metadata.incarnationId != incarnationId) {
        return false
    }
    return metadata.visibility.permits(canonicalSubjectId, requestSessionId, operatorAuthorized)
}
