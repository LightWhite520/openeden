package io.openeden.runtime.state

import io.openeden.runtime.affect.OmegaAccumulationConfig
import io.openeden.runtime.heartbeat.HeartbeatOwner
import io.openeden.runtime.tick.TickConfig

data class RuntimeConfig(
    val tick: TickConfig = TickConfig(),
    val omega: OmegaAccumulationConfig = OmegaAccumulationConfig(),
    val owner: HeartbeatOwner? = null,
) {
    companion object {
        val Default = RuntimeConfig()
    }
}
