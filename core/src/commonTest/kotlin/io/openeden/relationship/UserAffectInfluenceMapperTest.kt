package io.openeden.relationship


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserAffectInfluenceMapperTest {
    @Test
    fun `mapper preserves five affect inputs and confidence`() {
        val mapper = UserAffectInfluenceMapper(
            toL = listOf(1f, 0f, 0f, 0f, 0f),
            toP = listOf(0f, 1f, 0f, 0f, 0f),
            toE = listOf(0f, 0f, 1f, 0f, 0f),
            toS = listOf(0f, 0f, 0f, 1f, 0f),
            toTau = listOf(0f, 0f, 0f, 0f, 1f),
            toV = List(5) { 0f }, toM = List(5) { 0f }, toF = List(5) { 0f },
        )
        val signal = mapper.map(UserAffectState(1f, 0f, 0.75f, 0.25f, 0.6f, 0.8f))

        assertEquals(0.5f, signal.delta.l)
        assertEquals(-0.5f, signal.delta.p)
        assertEquals(0.25f, signal.delta.e)
        assertEquals(-0.25f, signal.delta.s)
        assertEquals(0.1f, signal.delta.tau, 0.00001f)
        assertEquals(0.8f, signal.confidence)
    }

    @Test
    fun `neutral mapper emits zero influence`() {
        val signal = UserAffectState(1f, 0f, 1f, 0f, 1f, 1f).toEmotionSignal()
        assertTrue(signal.delta.toList().all { it == 0.0f })
    }
}
