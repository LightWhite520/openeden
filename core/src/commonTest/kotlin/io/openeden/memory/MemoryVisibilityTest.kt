package io.openeden.memory

import io.openeden.identity.CanonicalSubjectResolver
import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryVisibilityTest {
    @Test
    fun `private memory is visible only to the bound canonical subject`() {
        assertTrue(MemoryVisibility.PrivateSubject("host").permits("host", "QQ:private"))
        assertFalse(MemoryVisibility.PrivateSubject("host").permits("guest", "QQ:group"))
    }

    @Test
    fun `unbound users retain a stable platform local subject`() {
        val resolver = CanonicalSubjectResolver()

        assertEquals("QQ:user", resolver.resolve("QQ", "user").value)
    }

    @Test
    fun `configured bindings join platform identities to one canonical subject`() {
        val resolver = CanonicalSubjectResolver(
            mapOf(
                CanonicalSubjectResolver.PlatformUser("QQ", "owner") to io.openeden.identity.CanonicalSubjectId("identity:owner"),
                CanonicalSubjectResolver.PlatformUser("WEB", "owner") to io.openeden.identity.CanonicalSubjectId("identity:owner"),
            ),
        )

        assertEquals("identity:owner", resolver.resolve("QQ", "owner").value)
        assertEquals("identity:owner", resolver.resolve("WEB", "owner").value)
    }

    @Test
    fun `incarnation shared memory rejects a request without an incarnation`() {
        val entry = MemoryEntry(
            id = "shared",
            sessionId = "QQ:group",
            content = "shared",
            room = MemoryRoom.EVENT_ROOM,
            kind = MemoryKind.RAW,
            semanticEmbedding = listOf(1.0f),
            emotionalEmbedding = listOf(1.0f),
            metadata = MemoryMetadata(
                snapshot8D = BioVector.Neutral,
                omegaState = 0.0f,
                deltaVec = VectorDelta.Zero,
                snapshotOrigin = BioVector.Neutral,
                userId = "user",
                incarnationId = "incarnation-1",
                canonicalSubjectId = "QQ:user",
                visibility = MemoryVisibility.IncarnationShared,
            ),
        )

        assertFalse(entry.isVisibleTo(
            RetrievalRequest(
                sessionId = "QQ:group",
                userId = "user",
                canonicalSubjectId = "QQ:user",
                userInput = "query",
                currentVector = BioVector.Neutral,
                origin = BioVector.Neutral,
                mode = RetrievalMode.CONGRUENT,
            ),
        ))
    }
}
