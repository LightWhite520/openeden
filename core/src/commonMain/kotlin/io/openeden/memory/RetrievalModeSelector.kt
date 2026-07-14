package io.openeden.memory

import io.openeden.bio.InternalBioVector
import io.openeden.runtime.affect.OmegaState
import io.openeden.runtime.affect.ShockState

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
