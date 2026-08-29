package io.openeden.server.maintenance

fun interface IncarnationProjectionEraser {
    suspend fun eraseAndVerify(incarnationId: String, modelIds: Set<String>)

    companion object {
        val Disabled = IncarnationProjectionEraser { _, modelIds ->
            if (modelIds.isNotEmpty()) {
                throw IncarnationProjectionConfigurationException(
                    "Persisted projection models cannot be erased while vector projection is disabled: ${modelIds.sorted()}",
                )
            }
        }
    }
}

class IncarnationProjectionConfigurationException(message: String) : IllegalStateException(message)
