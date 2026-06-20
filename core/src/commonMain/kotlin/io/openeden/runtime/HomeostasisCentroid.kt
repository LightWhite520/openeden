package io.openeden.runtime

import io.openeden.bio.BioVector

fun interface HomeostasisCentroidProvider {
    suspend fun centroidFor(sessionId: String): BioVector
}

class StoredOriginCentroidProvider(
    private val store: SessionStateStore,
) : HomeostasisCentroidProvider {
    override suspend fun centroidFor(sessionId: String): BioVector =
        store.read(sessionId).origin
}
