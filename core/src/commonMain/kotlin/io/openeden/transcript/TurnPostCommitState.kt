package io.openeden.transcript

import kotlinx.serialization.Serializable

@Serializable
data class TurnPostCommitState(
    val plan: TurnPostCommitPlan,
    val completedStages: Set<TurnPostCommitStage> = emptySet(),
) {
    val pendingStages: List<TurnPostCommitStage>
        get() = plan.requiredStages.filterNot(completedStages::contains)
}
