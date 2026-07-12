package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAffectContractsTest {
    @Test
    fun `uncertain state has no effective confidence`() {
        assertEquals(0.0f, UserAffectState.Uncertain.confidence)
        assertEquals(SemanticLevel.UNKNOWN, UserAffectState.Uncertain.semanticLevel(0.9f))
    }

    @Test
    fun `non finite affect values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            UserAffectState(Float.NaN, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        }
    }
}
