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
        val effectiveEvents = allEvents
            .applicableEvents(initial.continuousBaselineEventIds)
            .effectiveEvents()
        val previousEffectiveEvents = initial.events
            .asSequence()
            .filter { event ->
                event.incarnationId == initial.incarnationId &&
                    event.canonicalSubjectId == initial.canonicalSubjectId
            }
            .distinctBy(RelationshipEvent::idempotencyKey)
            .sortedWith(compareBy(RelationshipEvent::createdAtMs, RelationshipEvent::eventId))
            .toList()
            .applicableEvents(initial.continuousBaselineEventIds)
            .effectiveEvents()
        val facts = effectiveEvents.fold(RelationshipFacts(), ::apply)
        val acceptedNewEvents = newEvents
            .asSequence()
            .filter { event ->
                event.incarnationId == initial.incarnationId &&
                    event.canonicalSubjectId == initial.canonicalSubjectId &&
                    initial.events.none { it.idempotencyKey() == event.idempotencyKey() }
            }
            .distinctBy(RelationshipEvent::idempotencyKey)
            .sortedWith(compareBy(RelationshipEvent::createdAtMs, RelationshipEvent::eventId))
            .toList()
        val replayContinuousState = acceptedNewEvents.any { event ->
            event.type == RelationshipEventType.RESET || event.supersedesEventId != null
        }
        val eventEffects = if (replayContinuousState) {
            initial.replayContinuousState(
                previousEffectiveEvents.filterNot { it.eventId in initial.continuousBaselineEventIds },
                effectiveEvents.filterNot { it.eventId in initial.continuousBaselineEventIds },
            )
        } else {
            acceptedNewEvents.applyContinuousEffects(initial)
        }
        val updatedAtMs = maxOf(initial.updatedAtMs, allEvents.maxOfOrNull(RelationshipEvent::createdAtMs) ?: 0L)
        return eventEffects.copy(
            facts = facts.withEstablishedCouple(effectiveEvents, eventEffects),
            events = allEvents,
            updatedAtMs = updatedAtMs,
        )
    }

    // Pre-accumulator event effects are opaque after migration, so corrections targeting them are audit-only.
    private fun List<RelationshipEvent>.applicableEvents(opaqueBaselineEventIds: Set<String>): List<RelationshipEvent> =
        filterNot { event -> event.supersedesEventId in opaqueBaselineEventIds }

    private fun List<RelationshipEvent>.effectiveEvents(): List<RelationshipEvent> {
        val superseded = mapNotNull(RelationshipEvent::supersedesEventId).toSet()
        val corrected = filterNot { it.eventId in superseded }
        return corrected.drop(corrected.indexOfLast { it.type == RelationshipEventType.RESET } + 1)
    }

    private fun RelationshipState.replayContinuousState(
        previousEvents: List<RelationshipEvent>,
        correctedEvents: List<RelationshipEvent>,
    ): RelationshipState {
        val previousContribution = previousEvents.continuousContribution()
        val correctedContribution = correctedEvents.continuousContribution()
        val correctedAccumulator = (continuousAccumulator ?: RelationshipContinuousAccumulator.from(this)) -
            previousContribution + correctedContribution
        return copy(
            trust = correctedAccumulator.trust.coerceIn(0.0f, 1.0f),
            familiarity = correctedAccumulator.familiarity.coerceIn(0.0f, 1.0f),
            safety = correctedAccumulator.safety.coerceIn(0.0f, 1.0f),
            boundarySensitivity = correctedAccumulator.boundarySensitivity.coerceIn(0.0f, 1.0f),
            unresolvedTension = correctedAccumulator.unresolvedTension.coerceIn(0.0f, 1.0f),
            reciprocalInterest = correctedAccumulator.reciprocalInterest.coerceIn(0.0f, 1.0f),
            evidenceCount = (evidenceCount - previousEvents.countContinuousEffects()).coerceAtLeast(0L) +
                correctedEvents.countContinuousEffects(),
            continuousAccumulator = correctedAccumulator,
            continuousAccumulatorVersion = RelationshipContinuousAccumulator.CURRENT_VERSION,
        )
    }

    private fun List<RelationshipEvent>.continuousContribution(): RelationshipContinuousAccumulator =
        mapNotNull { event -> event.toEvidence() }
            .fold(RelationshipContinuousAccumulator.Zero, RelationshipContinuousAccumulator::apply)

    private fun List<RelationshipEvent>.countContinuousEffects(): Long = count { it.toEvidence() != null }.toLong()

    private fun List<RelationshipEvent>.applyContinuousEffects(seed: RelationshipState): RelationshipState =
        mapNotNull { event -> event.toEvidence()?.let { evidence -> evidence to event.createdAtMs } }
            .fold(seed) { state, (evidence, createdAtMs) -> state.apply(evidence, createdAtMs) }

    private fun apply(facts: RelationshipFacts, event: RelationshipEvent): RelationshipFacts = when (event.type) {
        RelationshipEventType.ACQUAINTANCE -> facts.withPhaseAtLeast(RelationshipPhase.FAMILIAR)
        RelationshipEventType.USER_CONFESSION -> facts.copy(
            phase = if (facts.phase.ordinal >= RelationshipPhase.FAMILIAR.ordinal) facts.phase else RelationshipPhase.FAMILIAR,
            userConfessedAtMs = facts.userConfessedAtMs ?: event.createdAtMs,
        ).withMutualCommitment()
        RelationshipEventType.ATRI_ACCEPTANCE -> facts.copy(
            phase = if (facts.phase.ordinal >= RelationshipPhase.MUTUAL_INTEREST.ordinal) {
                facts.phase
            } else {
                RelationshipPhase.MUTUAL_INTEREST
            },
            atriAcceptedAtMs = facts.atriAcceptedAtMs ?: event.createdAtMs)
        RelationshipEventType.MUTUAL_COMMITMENT -> facts.copy(
            mutualCommitmentAtMs = facts.mutualCommitmentAtMs ?: event.createdAtMs,
        ).withPhaseAtLeast(RelationshipPhase.COUPLE)
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
        RelationshipEventType.ACQUAINTANCE -> RelationshipEvidence.ORDINARY_INTERACTION
        RelationshipEventType.PREFERENCE_RESPECTED -> RelationshipEvidence.RESPECTED_PREFERENCE
        RelationshipEventType.BOUNDARY_REQUEST -> RelationshipEvidence.BOUNDARY_REQUEST
        RelationshipEventType.BOUNDARY_VIOLATION -> RelationshipEvidence.BOUNDARY_VIOLATION
        RelationshipEventType.ATRI_ACCEPTANCE,
        RelationshipEventType.MUTUAL_COMMITMENT,
        -> RelationshipEvidence.RECIPROCAL_INTEREST
        RelationshipEventType.CONFLICT -> RelationshipEvidence.CONFLICT
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
        return if (commitmentAtMs == null) {
            this
        } else {
            copy(mutualCommitmentAtMs = commitmentAtMs).withPhaseAtLeast(RelationshipPhase.COUPLE)
        }
    }

    private fun RelationshipFacts.withEstablishedCouple(
        events: List<RelationshipEvent>,
        continuousState: RelationshipState,
    ): RelationshipFacts {
        val commitmentAtMs = mutualCommitmentAtMs ?: return this
        val maturityEvidence = events.filter { event ->
            event.toEvidence()?.contributesToMaturity == true
        }
        val postCommitmentEvidenceCount = maturityEvidence.count { event -> event.createdAtMs > commitmentAtMs }
        val sustainedSafeState = continuousState.trust >= ESTABLISHED_TRUST_THRESHOLD &&
            continuousState.familiarity >= ESTABLISHED_FAMILIARITY_THRESHOLD &&
            continuousState.safety >= ESTABLISHED_SAFETY_THRESHOLD &&
            continuousState.unresolvedTension <= ESTABLISHED_TENSION_CEILING &&
            continuousState.boundarySensitivity <= ESTABLISHED_BOUNDARY_SENSITIVITY_CEILING
        return if (
            postCommitmentEvidenceCount >= ESTABLISHED_COUPLE_EVIDENCE_COUNT &&
            maturityEvidence.size >= ESTABLISHED_TOTAL_EVIDENCE_COUNT &&
            sustainedSafeState
        ) {
            withPhaseAtLeast(RelationshipPhase.ESTABLISHED_COUPLE)
        } else {
            this
        }
    }

    private fun RelationshipFacts.withPhaseAtLeast(phase: RelationshipPhase): RelationshipFacts =
        if (this.phase.ordinal >= phase.ordinal) this else copy(phase = phase)

    private val RelationshipEvidence.contributesToMaturity: Boolean
        get() = when (this) {
            RelationshipEvidence.RESPECTED_PREFERENCE,
            RelationshipEvidence.CORRECTED_MISUNDERSTANDING,
            RelationshipEvidence.REPEATED_CONSISTENCY,
            RelationshipEvidence.RECIPROCAL_INTEREST,
            RelationshipEvidence.REPAIR,
            -> true
            RelationshipEvidence.ORDINARY_INTERACTION,
            RelationshipEvidence.BOUNDARY_REQUEST,
            RelationshipEvidence.BOUNDARY_VIOLATION,
            RelationshipEvidence.CONFLICT,
            -> false
        }

    private const val ESTABLISHED_COUPLE_EVIDENCE_COUNT = 8
    private const val ESTABLISHED_TOTAL_EVIDENCE_COUNT = 9
    private const val ESTABLISHED_TRUST_THRESHOLD = 0.575f
    private const val ESTABLISHED_FAMILIARITY_THRESHOLD = 0.055f
    private const val ESTABLISHED_SAFETY_THRESHOLD = 0.575f
    private const val ESTABLISHED_TENSION_CEILING = 0.10f
    private const val ESTABLISHED_BOUNDARY_SENSITIVITY_CEILING = 0.40f
}
