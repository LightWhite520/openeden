package io.openeden.onebot.config

data class OneBotConfig(
    val enabled: Boolean,
    val path: String = "/onebot/v11",
    val accessToken: String = "",
    val botSelfId: String = "",
    val groupPolicy: OneBotGroupPolicy = OneBotGroupPolicy.MENTION_ONLY,
    val eventQueueCapacity: Int = 64,
    val eventWorkers: Int = 4,
    val actionTimeoutMs: Long = 10_000L,
    val maxActionRetries: Int = 2,
) {
    init {
        require(path.startsWith('/') && !path.contains("?") && !path.contains('#')) {
            "OneBot path must be an absolute route path"
        }
        require(eventQueueCapacity in 1..4096) { "OneBot event queue capacity must be in 1..4096" }
        require(eventWorkers in 1..64) { "OneBot event worker count must be in 1..64" }
        require(actionTimeoutMs in 100L..120_000L) { "OneBot action timeout must be in 100..120000 ms" }
        require(maxActionRetries in 0..5) { "OneBot action retries must be in 0..5" }
        if (enabled) {
            require(accessToken.isNotBlank()) { "OneBot access token is required when enabled" }
            require(botSelfId.isNotBlank()) { "OneBot bot self ID is required when enabled" }
        }
    }
}
