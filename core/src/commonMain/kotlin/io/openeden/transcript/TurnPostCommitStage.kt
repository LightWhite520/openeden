package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
enum class TurnPostCommitStage {
    RELATIONSHIP,
    RAW_MEMORY,
    DIARY,
    CENTROID,
}
