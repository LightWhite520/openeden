package io.openeden.memory

import io.openeden.bio.BioVector
import io.openeden.bio.InternalBioVector
import io.openeden.bio.VectorDelta
import io.openeden.runtime.OmegaState
import io.openeden.runtime.ShockState
import kotlinx.serialization.Serializable

enum class RetrievalMode {
    CONGRUENT,
    MIXED,
    CONTRAST,
}

data class RetrievalResult(
    val mode: RetrievalMode,
    val injectionLabel: String,
    val memories: List<MemorySnippet>,
    val recentMemories: List<MemorySnippet> = emptyList(),
    val traceTags: Set<String> = emptySet(),
)

data class MemorySnippet(
    val id: String = "",
    val content: String,
    val metadata: MemoryMetadata,
    val score: Float = 0.0f,
)

@Serializable
data class MemoryMetadata(
    val snapshot8D: BioVector,
    val omegaState: Float,
    val deltaVec: VectorDelta,
    val snapshotOrigin: BioVector,
    val userId: String,
)

interface MemoryRetriever {
    suspend fun retrieve(request: RetrievalRequest): RetrievalResult
}

interface MemoryStore : MemoryRetriever {
    suspend fun write(entry: MemoryEntry): Set<String>
    suspend fun stableVectors(sessionId: String, limit: Int): List<BioVector>
    suspend fun recent(sessionId: String, limit: Int): List<MemorySnippet> = emptyList()
}

data class MemoryEntry(
    val id: String,
    val sessionId: String,
    val content: String,
    val room: MemoryRoom,
    val kind: MemoryKind,
    val tags: Set<String> = emptySet(),
    val semanticEmbedding: List<Float>,
    val emotionalEmbedding: List<Float>,
    val metadata: MemoryMetadata,
)

enum class MemoryRoom {
    TECH_ROOM,
    PROJECT_ROOM,
    PROFILE_ROOM,
    EVENT_ROOM,
    KNOWLEDGE_ROOM,
    NOISE_ROOM,
}

enum class MemoryKind {
    RAW,
    NARRATIVE,
}

data class RetrievalRequest(
    val sessionId: String,
    val userInput: String,
    val currentVector: BioVector,
    val origin: BioVector,
    val mode: RetrievalMode,
    val userId: String = "",
)

object RetrievalModeSelector {
    fun select(
        internalVector: InternalBioVector,
        omegaState: OmegaState,
        shockState: ShockState?,
    ): RetrievalMode = when {
        shockState?.active == true && shockState.intensity >= 0.6f -> RetrievalMode.CONTRAST
        omegaState.value >= 0.75f -> RetrievalMode.CONTRAST
        internalVector.p < -0.3f && internalVector.v < -0.2f -> RetrievalMode.MIXED
        else -> RetrievalMode.CONGRUENT
    }

    fun injectionLabel(mode: RetrievalMode): String = when (mode) {
        RetrievalMode.CONGRUENT -> "[相关记忆]"
        RetrievalMode.MIXED -> "[相关记忆 - 尝试寻找平静]"
        RetrievalMode.CONTRAST -> "[记忆涌现 - 非主动检索]"
    }
}
