package io.openeden.runtime

data class RuntimeConfig(
    val tick: TickConfig = TickConfig(),
    val omega: OmegaAccumulationConfig = OmegaAccumulationConfig(),
    val owner: HeartbeatOwner? = null,
) {
    companion object {
        val Default = RuntimeConfig()
    }
}
