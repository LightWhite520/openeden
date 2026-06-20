package io.openeden.runtime

import io.openeden.bio.BioVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class HomeostasisCentroidProviderTest {
    @Test
    fun `bootstrap centroid provider returns persisted origin`() = runTest {
        val store = MutableSessionStateStore()
        val origin = BioVector.Neutral.copy(p = 0.35f, v = 0.4f)
        store.write(SessionStateStore.neutral("QQ:centroid").copy(origin = origin))
        val provider = StoredOriginCentroidProvider(store)

        assertEquals(origin, provider.centroidFor("QQ:centroid"))
    }
}
