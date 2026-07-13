package io.openeden.config

import kotlinx.serialization.Serializable

@Serializable
data class CliConfig(
    val serverUrl: String,
    val userId: String,
)
