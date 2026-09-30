package io.openeden.client

import kotlinx.serialization.Serializable

@Serializable
data class ModelCatalog(val current: String, val models: List<ModelOption>)
