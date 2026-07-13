package io.openeden.memory

import io.openeden.bio.BioVector

data class RetrievalRequest(
    val sessionId: String,
    val userInput: String,
    val currentVector: BioVector,
    val origin: BioVector,
    val mode: RetrievalMode,
    val userId: String = "",
)
