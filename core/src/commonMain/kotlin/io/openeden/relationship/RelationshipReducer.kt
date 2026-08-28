package io.openeden.relationship

object RelationshipReducer {
    fun reduce(initial: RelationshipState, newEvents: List<RelationshipEvent>): RelationshipState {
        val allEvents = (initial.events + newEvents)
            .asSequence()
            .filter { event ->
                event.incarnationId == initial.incarnationId &&
                    event.canonicalSubjectId == initial.canonicalSubjectId
            }
            .distinctBy(RelationshipEvent::idempotencyKey)
            .sortedWith(compareBy(RelationshipEvent::createdAtMs, RelationshipEvent::eventId))
            .toList()
        val facts = allEvents.effectiveEvents().fold(RelationshipFacts(), ::apply)
        val eventEffects = newEvents
            .filter { event ->
                event.incarnationId == initial.incarnationId &&
                    event.canonicalSubjectId == initial.canonicalSubjectId &&
                    initial.events.none { it.idempotencyKey() == event.idempotencyKey() }
            }
            .mapNotNull { event -> event.toEvidence()?.let { evidence -> evidence to event.createdAtMs } }
            .fold(initial) { state, (evidence, createdAtMs) -> state.apply(evidence, createdAtMs) }
        val updatedAtMs = maxOf(initial.updatedAtMs, allEvents.maxOfOrNull(RelationshipEvent::createdAtMs) ?: 0L)
        return eventEffects.copy(facts = facts, events = allEvents, updatedAtMs = updatedAtMs)
    }

    private fun List<RelationshipEvent>.effectiveEvents(): List<RelationshipEvent> {
        val afterReset = drop(indexOfLast { it.type == RelationshipEventType.RESET } + 1)
        val superseded = afterReset.mapNotNull(RelationshipEvent::supersedesEventId).toSet()
        return afterReset.filterNot { it.eventId in superseded }
    }

    private fun apply(facts: RelationshipFacts, event: RelationshipEvent): RelationshipFacts = when (event.type) {
        RelationshipEventType.ACQUAINTANCE -> facts.withPhaseAtLeast(RelationshipPhase.FAMILIAR)
        RelationshipEventType.USER_CONFESSION -> facts.copy(
            phase = if (facts.phase.ordinal >= RelationshipPhase.FAMILIAR.ordinal) facts.phase else RelationshipPhase.FAMILIAR,
            userConfessedAtMs = facts.userConfessedAtMs ?: event.createdAtMs,
        ).withMutualCommitment()
        RelationshipEventType.ATRI_ACCEPTANCE -> facts.copy(
            atriAcceptedAtMs = facts.atriAcceptedAtMs ?: event.createdAtMs)
        RelationshipEventType.MUTUAL_COMMITMENT -> facts.copy(
            mutualCommitmentAtMs = facts.mutualCommitmentAtMs ?: event.createdAtMs,
            phase = RelationshipPhase.COUPLE,
        )
        RelationshipEventType.ADDRESS_PREFERENCE -> event.preferredAddress?.let { address ->
            facts.copy(preferredAddresses = facts.preferredAddresses + address)
        } ?: facts
        RelationshipEventType.RELATIONSHIP_ENDED -> facts.copy(
            phase = RelationshipPhase.STRANGER,
            userConfessedAtMs = null,
            atriAcceptedAtMs = null,
            mutualCommitmentAtMs = null,
        )
        RelationshipEventType.RESET -> facts
        else -> facts
    }.withMutualCommitment()

    private fun RelationshipEvent.toEvidence(): RelationshipEvidence? = when (type) {
        RelationshipEventType.REPAIR -> RelationshipEvidence.REPAIR
        RelationshipEventType.REPEATED_CONSISTENCY -> RelationshipEvidence.REPEATED_CONSISTENCY
        else -> null
    }

    private fun RelationshipFacts.withMutualCommitment(): RelationshipFacts {
        val commitmentAtMs = when {
            mutualCommitmentAtMs != null -> mutualCommitmentAtMs
            userConfessedAtMs != null && atriAcceptedAtMs != null -> maxOf(userConfessedAtMs, atriAcceptedAtMs)
            else -> null
        }
        return if (commitmentAtMs == null) this else copy(phase = RelationshipPhase.COUPLE, mutualCommitmentAtMs = commitmentAtMs)
    }

    private fun RelationshipFacts.withPhaseAtLeast(phase: RelationshipPhase): RelationshipFacts =
        if (this.phase.ordinal >= phase.ordinal) this else copy(phase = phase)
}
