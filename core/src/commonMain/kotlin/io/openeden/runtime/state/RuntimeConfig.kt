package io.openeden.runtime.state

import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.heartbeat.HeartbeatOwner
import io.openeden.runtime.tick.TickConfig

data class RuntimeConfig(
    val tick: TickConfig = TickConfig(),
    val omega: OmegaAccumulationConfig = OmegaAccumulationConfig(),
    val omegaCriticalThreshold: Float = 0.95f,
    val owner: HeartbeatOwner? = null,
) {
    init {
        require(omegaCriticalThreshold in 0.0f..1.0f) {
            "omegaCriticalThreshold must be in [0, 1]"
        }
    }

    companion object {
        val Default = RuntimeConfig()
    }
}
