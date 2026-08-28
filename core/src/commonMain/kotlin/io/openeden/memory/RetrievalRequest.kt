package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.identity.CanonicalSubjectResolver

data class RetrievalRequest(
    val sessionId: String,
    val userInput: String,
    val currentVector: BioVector,
    val origin: BioVector,
    val mode: RetrievalMode,
    val userId: String = "",
    val exclusionContext: MemoryExclusionContext = MemoryExclusionContext(),
    val incarnationId: String = "",
    val canonicalSubjectId: String = CanonicalSubjectResolver().resolve(
        platform = sessionId.substringBefore(':', sessionId),
        userId = userId,
    ).value,
)
