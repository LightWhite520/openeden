package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals

class RelationshipContractsTest {
    @Test
    fun `relationship state changes are bounded and auditable`() {
        val initial = RelationshipState.neutral("QQ:group", "u1")
        val updated = initial.apply(RelationshipEvidence.BOUNDARY_VIOLATION, 42L)

        assertEquals(1L, updated.evidenceCount)
        assertEquals(42L, updated.updatedAtMs)
        assertEquals(0.15f, updated.unresolvedTension)
        assertEquals(0.42f, updated.safety, 0.00001f)
    }

    @Test
    fun `relationship keys keep users and sessions separate`() {
        assertEquals("s\u0000u1", relationshipKey("s", "u1"))
        assertEquals("s\u0000u2", relationshipKey("s", "u2"))
        assertEquals("other\u0000u1", relationshipKey("other", "u1"))
    }
}
