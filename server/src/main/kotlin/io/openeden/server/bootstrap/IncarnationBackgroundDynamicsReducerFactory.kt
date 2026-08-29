package io.openeden.server.bootstrap

import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.state.BackgroundDynamicsReducer
import io.openeden.runtime.tick.IncarnationSineWaveFluctuation
import io.openeden.runtime.tick.SineWaveFluctuationEngine

internal class IncarnationBackgroundDynamicsReducerFactory(
    private val omegaConfig: OmegaAccumulationConfig,
) {
    fun create(incarnationId: String): BackgroundDynamicsReducer = BackgroundDynamicsReducer(
        fluctuation = SineWaveFluctuationEngine(
            IncarnationSineWaveFluctuation.profile(incarnationId),
        ),
        omegaConfig = omegaConfig,
        startedAtMs = ABSOLUTE_DYNAMICS_EPOCH_MS,
    )

    private companion object {
        const val ABSOLUTE_DYNAMICS_EPOCH_MS = 0L
    }
}
