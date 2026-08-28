package io.openeden.relationship

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `applicable event types apply their continuous evidence effects`() {
        data class ExpectedSignals(
            val trust: Float = 0.5f,
            val familiarity: Float = 0.0f,
            val safety: Float = 0.5f,
            val boundarySensitivity: Float = 0.0f,
            val unresolvedTension: Float = 0.0f,
            val reciprocalInterest: Float = 0.0f,
        )

        val cases = listOf(
            RelationshipEventType.PREFERENCE_RESPECTED to ExpectedSignals(safety = 0.52f),
            RelationshipEventType.BOUNDARY_REQUEST to ExpectedSignals(boundarySensitivity = 0.08f),
            RelationshipEventType.BOUNDARY_VIOLATION to ExpectedSignals(
                safety = 0.42f,
                boundarySensitivity = 0.15f,
                unresolvedTension = 0.15f,
            ),
            RelationshipEventType.ATRI_ACCEPTANCE to ExpectedSignals(reciprocalInterest = 0.1f),
            RelationshipEventType.MUTUAL_COMMITMENT to ExpectedSignals(reciprocalInterest = 0.1f),
            RelationshipEventType.CONFLICT to ExpectedSignals(trust = 0.46f, unresolvedTension = 0.12f),
            RelationshipEventType.REPAIR to ExpectedSignals(trust = 0.52f, safety = 0.52f),
            RelationshipEventType.REPEATED_CONSISTENCY to ExpectedSignals(
                trust = 0.51f,
                familiarity = 0.01f,
                safety = 0.51f,
            ),
        )

        cases.forEachIndexed { index, (type, expected) ->
            val reduced = RelationshipReducer.reduce(
                RelationshipState.neutral("inc-1", "host"),
                listOf(event("effect-$index", type, index.toLong() + 1L)),
            )

            assertEquals(expected.trust, reduced.trust, 0.00001f, type.name)
            assertEquals(expected.familiarity, reduced.familiarity, 0.00001f, type.name)
            assertEquals(expected.safety, reduced.safety, 0.00001f, type.name)
            assertEquals(expected.boundarySensitivity, reduced.boundarySensitivity, 0.00001f, type.name)
            assertEquals(expected.unresolvedTension, reduced.unresolvedTension, 0.00001f, type.name)
            assertEquals(expected.reciprocalInterest, reduced.reciprocalInterest, 0.00001f, type.name)
            assertEquals(1L, reduced.evidenceCount, type.name)
        }
    }

    @Test
    fun `correction replays continuous state without the superseded effect`() {
        val violation = event("violation", RelationshipEventType.BOUNDARY_VIOLATION, 10L)
        val violated = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(violation),
        )
        val correction = event(
            sourceTurnId = "correction",
            type = RelationshipEventType.PREFERENCE_RESPECTED,
            createdAtMs = 20L,
            supersedesEventId = violation.eventId,
        )

        val corrected = RelationshipReducer.reduce(violated, listOf(correction))

        assertEquals(0.5f, corrected.trust, 0.00001f)
        assertEquals(0.52f, corrected.safety, 0.00001f)
        assertEquals(0.0f, corrected.boundarySensitivity, 0.00001f)
        assertEquals(0.0f, corrected.unresolvedTension, 0.00001f)
        assertEquals(1L, corrected.evidenceCount)
    }

    @Test
    fun `correction preserves durable continuous state outside the event ledger`() {
        val baseline = durableBaseline()
        val violation = event("violation", RelationshipEventType.BOUNDARY_VIOLATION, 10L)
        val violated = RelationshipReducer.reduce(baseline, listOf(violation))
        val correction = event(
            sourceTurnId = "correction",
            type = RelationshipEventType.PREFERENCE_RESPECTED,
            createdAtMs = 20L,
            supersedesEventId = violation.eventId,
        )

        val corrected = RelationshipReducer.reduce(violated, listOf(correction))

        assertContinuousStateEquals(
            baseline.copy(safety = 0.82f, evidenceCount = 8L, updatedAtMs = 20L),
            corrected,
        )
    }

    @Test
    fun `correction restores exact trust after the superseded effect clamps at zero`() {
        val baseline = RelationshipState.neutral("inc-1", "host").copy(trust = 0.02f)
        val conflict = event("conflict", RelationshipEventType.CONFLICT, 10L)
        val conflicted = RelationshipReducer.reduce(baseline, listOf(conflict))
        assertEquals(0.0f, conflicted.trust, 0.00001f)
        val correction = event(
            sourceTurnId = "correction",
            type = RelationshipEventType.PREFERENCE_RESPECTED,
            createdAtMs = 20L,
            supersedesEventId = conflict.eventId,
        )

        val corrected = RelationshipReducer.reduce(conflicted, listOf(correction))

        assertEquals(0.02f, corrected.trust, 0.00001f)
        assertEquals(0.52f, corrected.safety, 0.00001f)
    }

    @Test
    fun `correction targeting an opaque baseline event is audit only`() {
        val baselineEvent = event("legacy-conflict", RelationshipEventType.CONFLICT, 10L)
        val baseline = RelationshipState.neutral("inc-1", "host").copy(
            trust = 0.0f,
            unresolvedTension = 0.12f,
            evidenceCount = 1L,
            events = listOf(baselineEvent),
            continuousAccumulator = RelationshipContinuousAccumulator(
                trust = 0.0f,
                familiarity = 0.0f,
                safety = 0.5f,
                boundarySensitivity = 0.0f,
                unresolvedTension = 0.12f,
                reciprocalInterest = 0.0f,
            ),
            continuousAccumulatorVersion = RelationshipContinuousAccumulator.CURRENT_VERSION,
            continuousBaselineEventIds = setOf(baselineEvent.eventId),
        )
        val correction = event(
            sourceTurnId = "legacy-correction",
            type = RelationshipEventType.PREFERENCE_RESPECTED,
            createdAtMs = 20L,
            supersedesEventId = baselineEvent.eventId,
        )

        val corrected = RelationshipReducer.reduce(baseline, listOf(correction))

        assertContinuousStateEquals(baseline.copy(updatedAtMs = 20L), corrected)
        assertEquals(baseline.facts, corrected.facts)
        assertEquals(listOf(baselineEvent.eventId, correction.eventId), corrected.events.map(RelationshipEvent::eventId))
    }

    @Test
    fun `reset replay restores neutral continuous state`() {
        val changed = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(
                event("violation", RelationshipEventType.BOUNDARY_VIOLATION, 10L),
                event("interest", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val reset = RelationshipReducer.reduce(
            changed,
            listOf(event("reset", RelationshipEventType.RESET, 30L)),
        )

        assertEquals(0.5f, reset.trust, 0.00001f)
        assertEquals(0.0f, reset.familiarity, 0.00001f)
        assertEquals(0.5f, reset.safety, 0.00001f)
        assertEquals(0.0f, reset.boundarySensitivity, 0.00001f)
        assertEquals(0.0f, reset.unresolvedTension, 0.00001f)
        assertEquals(0.0f, reset.reciprocalInterest, 0.00001f)
        assertEquals(0L, reset.evidenceCount)
    }

    @Test
    fun `reset preserves durable continuous state outside the event ledger`() {
        val baseline = durableBaseline()
        val changed = RelationshipReducer.reduce(
            baseline,
            listOf(
                event("violation", RelationshipEventType.BOUNDARY_VIOLATION, 10L),
                event("interest", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val reset = RelationshipReducer.reduce(
            changed,
            listOf(event("reset", RelationshipEventType.RESET, 30L)),
        )

        assertContinuousStateEquals(baseline.copy(updatedAtMs = 30L), reset)
    }

    @Test
    fun `reset after opaque baseline restores baseline and removes post migration effects`() {
        val baselineEvent = event("legacy-conflict", RelationshipEventType.CONFLICT, 10L)
        val baseline = RelationshipState.neutral("inc-1", "host").copy(
            trust = 0.0f,
            unresolvedTension = 0.12f,
            evidenceCount = 1L,
            events = listOf(baselineEvent),
            continuousAccumulator = RelationshipContinuousAccumulator(
                trust = 0.0f,
                familiarity = 0.0f,
                safety = 0.5f,
                boundarySensitivity = 0.0f,
                unresolvedTension = 0.12f,
                reciprocalInterest = 0.0f,
            ),
            continuousAccumulatorVersion = RelationshipContinuousAccumulator.CURRENT_VERSION,
            continuousBaselineEventIds = setOf(baselineEvent.eventId),
        )
        val changed = RelationshipReducer.reduce(
            baseline,
            listOf(event("post-migration", RelationshipEventType.PREFERENCE_RESPECTED, 20L)),
        )

        val reset = RelationshipReducer.reduce(
            changed,
            listOf(event("reset", RelationshipEventType.RESET, 30L)),
        )

        assertContinuousStateEquals(baseline.copy(updatedAtMs = 30L), reset)
        assertEquals(3, reset.events.size)
    }

    @Test
    fun `identical persisted snapshot and ledger are stable after restart`() {
        val persisted = RelationshipReducer.reduce(
            durableBaseline(),
            listOf(
                event("consistency", RelationshipEventType.REPEATED_CONSISTENCY, 10L),
                event("interest", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val restarted = RelationshipReducer.reduce(persisted.copy(events = persisted.events.toList()), emptyList())

        assertEquals(persisted, restarted)
    }

    @Test
    fun `correction superseding reset restores events from before that reset`() {
        val conflict = event("conflict", RelationshipEventType.CONFLICT, 10L)
        val reset = event("reset", RelationshipEventType.RESET, 20L)
        val respected = event("respected", RelationshipEventType.PREFERENCE_RESPECTED, 25L)
        val resetState = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(conflict, reset, respected),
        )
        val correction = event(
            sourceTurnId = "correction",
            type = RelationshipEventType.REPAIR,
            createdAtMs = 30L,
            supersedesEventId = reset.eventId,
        )

        val restored = RelationshipReducer.reduce(resetState, listOf(correction))

        assertEquals(0.48f, restored.trust, 0.00001f)
        assertEquals(0.54f, restored.safety, 0.00001f)
        assertEquals(0.04f, restored.unresolvedTension, 0.00001f)
        assertEquals(3L, restored.evidenceCount)
    }

    @Test
    fun `explicit reciprocal acceptance reaches mutual interest before commitment`() {
        val interested = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(atriAcceptance("acceptance")),
        )

        assertEquals(RelationshipPhase.MUTUAL_INTEREST, interested.facts.phase)
        assertTrue(interested.reciprocalInterest > 0.0f)

        val coupled = RelationshipReducer.reduce(interested, listOf(userConfession("confession")))

        assertEquals(RelationshipPhase.COUPLE, coupled.facts.phase)
        assertNotNull(coupled.facts.mutualCommitmentAtMs)
    }

    @Test
    fun `ordinary append preserves pre-ledger continuous state`() {
        val initial = RelationshipState.neutral("inc-1", "host").copy(reciprocalInterest = 0.4f)
        val confessed = RelationshipReducer.reduce(initial, listOf(userConfession("confession")))

        val accepted = RelationshipReducer.reduce(confessed, listOf(atriAcceptance("acceptance")))

        assertEquals(0.5f, accepted.reciprocalInterest, 0.00001f)
    }

    @Test
    fun `sustained consistency establishes a couple without narrative milestones`() {
        val coupled = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(
                event("confession", RelationshipEventType.USER_CONFESSION, 10L),
                event("acceptance", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val established = RelationshipReducer.reduce(coupled, List(8) { index ->
            event("consistency-$index", RelationshipEventType.REPEATED_CONSISTENCY, 30L + index)
        })

        assertEquals(RelationshipPhase.ESTABLISHED_COUPLE, established.facts.phase)
    }

    @Test
    fun `diverse safe continuous evidence provides an alternative established couple path`() {
        val coupled = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(
                event("confession", RelationshipEventType.USER_CONFESSION, 10L),
                event("acceptance", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val safeEvidence = buildList {
            repeat(4) { index ->
                add(event("consistency-$index", RelationshipEventType.REPEATED_CONSISTENCY, 30L + index))
                add(event("preference-$index", RelationshipEventType.PREFERENCE_RESPECTED, 40L + index))
                add(event("ordinary-$index", RelationshipEventType.ACQUAINTANCE, 50L + index))
            }
            repeat(2) { index ->
                add(event("repair-$index", RelationshipEventType.REPAIR, 60L + index))
            }
        }
        val established = RelationshipReducer.reduce(coupled, safeEvidence)

        assertEquals(RelationshipPhase.ESTABLISHED_COUPLE, established.facts.phase)
    }

    @Test
    fun `boundary requests alone never accelerate an established romance`() {
        val coupled = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(
                event("confession", RelationshipEventType.USER_CONFESSION, 10L),
                event("acceptance", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )

        val afterBoundaries = RelationshipReducer.reduce(coupled, List(20) { index ->
            event("boundary-$index", RelationshipEventType.BOUNDARY_REQUEST, 30L + index)
        })

        assertEquals(RelationshipPhase.COUPLE, afterBoundaries.facts.phase)
    }

    @Test
    fun `boundary request cannot provide the final established couple evidence`() {
        val coupled = RelationshipReducer.reduce(
            RelationshipState.neutral("inc-1", "host"),
            listOf(
                event("confession", RelationshipEventType.USER_CONFESSION, 10L),
                event("acceptance", RelationshipEventType.ATRI_ACCEPTANCE, 20L),
            ),
        )
        val sustainedPositive = RelationshipReducer.reduce(
            coupled,
            List(6) { index ->
                event("consistency-$index", RelationshipEventType.REPEATED_CONSISTENCY, 30L + index)
            } + event("repair", RelationshipEventType.REPAIR, 40L),
        )

        assertEquals(RelationshipPhase.COUPLE, sustainedPositive.facts.phase)
        assertTrue(sustainedPositive.trust >= 0.575f)
        assertTrue(sustainedPositive.familiarity >= 0.055f)
        assertTrue(sustainedPositive.safety >= 0.575f)

        val afterBoundary = RelationshipReducer.reduce(
            sustainedPositive,
            listOf(event("boundary", RelationshipEventType.BOUNDARY_REQUEST, 50L)),
        )

        assertEquals(RelationshipPhase.COUPLE, afterBoundary.facts.phase)
        assertEquals(0.08f, afterBoundary.boundarySensitivity, 0.00001f)
        assertEquals(sustainedPositive.evidenceCount + 1L, afterBoundary.evidenceCount)
    }

    private fun durableBaseline(): RelationshipState = RelationshipState.neutral("inc-1", "host").copy(
        trust = 0.7f,
        familiarity = 0.2f,
        safety = 0.8f,
        boundarySensitivity = 0.1f,
        unresolvedTension = 0.05f,
        reciprocalInterest = 0.3f,
        evidenceCount = 7L,
    )

    private fun assertContinuousStateEquals(expected: RelationshipState, actual: RelationshipState) {
        assertEquals(expected.trust, actual.trust, 0.00001f)
        assertEquals(expected.familiarity, actual.familiarity, 0.00001f)
        assertEquals(expected.safety, actual.safety, 0.00001f)
        assertEquals(expected.boundarySensitivity, actual.boundarySensitivity, 0.00001f)
        assertEquals(expected.unresolvedTension, actual.unresolvedTension, 0.00001f)
        assertEquals(expected.reciprocalInterest, actual.reciprocalInterest, 0.00001f)
        assertEquals(expected.evidenceCount, actual.evidenceCount)
        assertEquals(expected.updatedAtMs, actual.updatedAtMs)
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
