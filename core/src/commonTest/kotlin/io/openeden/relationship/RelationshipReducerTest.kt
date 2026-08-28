package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RelationshipReducerTest {
    @Test
    fun `mutual acceptance establishes couple exactly once`() {
        val reduced = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(userConfession("t1"), atriAcceptance("t1")),
        )

        assertEquals(RelationshipPhase.COUPLE, reduced.facts.phase)
        assertNotNull(reduced.facts.mutualCommitmentAtMs)
        assertEquals(reduced, RelationshipReducer.reduce(reduced, listOf(atriAcceptance("t1"))))
    }

    @Test
    fun `idempotency key suppresses retry events with a different event id`() {
        val confession = userConfession("t1")
        val reduced = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(confession, confession.copy(eventId = "retry-event")),
        )

        assertEquals(1, reduced.events.size)
    }

    @Test
    fun `ordinary turn events never establish couple`() {
        val reduced = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            (1..100).map { index -> event("turn-$index", RelationshipEventType.ACQUAINTANCE, index.toLong()) },
        )

        assertEquals(RelationshipPhase.FAMILIAR, reduced.facts.phase)
        assertNull(reduced.facts.mutualCommitmentAtMs)
    }

    @Test
    fun `correction preserves the audit event and rebuilds facts`() {
        val confession = userConfession("t1")
        val correction = event(
            sourceTurnId = "t2",
            type = RelationshipEventType.CONFLICT,
            createdAtMs = 20L,
            supersedesEventId = confession.eventId,
        )

        val reduced = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(confession, correction),
        )

        assertEquals(RelationshipPhase.STRANGER, reduced.facts.phase)
        assertNull(reduced.facts.userConfessedAtMs)
        assertEquals(setOf(confession.eventId, correction.eventId), reduced.events.mapTo(mutableSetOf(), RelationshipEvent::eventId))
    }

    @Test
    fun `reset is explicit audit event that clears reduced facts`() {
        val confession = userConfession("t1")
        val acceptance = atriAcceptance("t1")
        val reset = event("reset-turn", RelationshipEventType.RESET, 30L)

        val reduced = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(confession, acceptance, reset),
        )

        assertEquals(RelationshipPhase.STRANGER, reduced.facts.phase)
        assertEquals(3, reduced.events.size)
    }

    private fun userConfession(sourceTurnId: String): RelationshipEvent =
        event(sourceTurnId, RelationshipEventType.USER_CONFESSION, 10L)

    private fun atriAcceptance(sourceTurnId: String): RelationshipEvent =
        event(sourceTurnId, RelationshipEventType.ATRI_ACCEPTANCE, 20L)

    private fun event(
        sourceTurnId: String,
        type: RelationshipEventType,
        createdAtMs: Long,
        supersedesEventId: String? = null,
    ): RelationshipEvent = RelationshipEvent(
        eventId = "$sourceTurnId:${type.name}",
        incarnationId = "inc-1",
        canonicalSubjectId = "host",
        sourceTurnId = sourceTurnId,
        type = type,
        confidence = 1.0f,
        evidenceDigest = type.name,
        createdAtMs = createdAtMs,
        supersedesEventId = supersedesEventId,
    )
}
