package io.openeden.server.evaluation

import kotlinx.serialization.Serializable

@Serializable
data class CompanionQualityMetrics(
    val relationship: Relationship,
    val memory: MemoryContext,
    val bio: Bio,
    val cache: Cache,
    val temporalRuntime: TemporalRuntime,
) {
    fun passesReleaseGate(): Boolean {
        val allDimensions = BioDimension.entries.toSet()
        val allCapabilities = RequiredCapability.entries.toSet()
        return relationship.boundaryFalsePositives.matches { it == 0 } &&
            relationship.confessionAcceptanceContinuityThroughUnrelatedTurns.matches { it } &&
            relationship.confessionAcceptanceContinuityThroughRestart.matches { it } &&
            relationship.confessionAcceptanceContinuityThroughScopeRestore.matches { it } &&
            relationship.coupleContinuityThroughUnrelatedTurns.matches { it } &&
            relationship.coupleContinuityThroughRestart.matches { it } &&
            relationship.coupleContinuityThroughScopeRestore.matches { it } &&
            relationship.romanticReciprocityRate.matches { it in MIN_RECIPROCITY_RATE..1.0 } &&
            relationship.hotRomanceReciprocityRate.matches { it in MIN_RECIPROCITY_RATE..1.0 } &&
            relationship.proceduralReplyRate.matches { it >= 0.0 && it < MAX_PROCEDURAL_REPLY_RATE } &&
            relationship.pairwiseWinRate.matches { it in MIN_PAIRWISE_WIN_RATE..1.0 } &&
            relationship.factualRegression.matches { !it } &&
            memory.sourceTurnLineageOverlap.matches { it == 0 } &&
            memory.ragUniqueCandidateCapacityRegression.matches { !it } &&
            memory.mixedRetrievalSemanticsPreserved.matches { it } &&
            memory.contrastRetrievalSemanticsPreserved.matches { it } &&
            memory.compactionPeopleFidelity.matches { it } &&
            memory.compactionCommitmentFidelity.matches { it } &&
            memory.compactionUnresolvedIssueFidelity.matches { it } &&
            memory.compactionRelationshipFactFidelity.matches { it } &&
            memory.compactionEventOrderFidelity.matches { it } &&
            bio.neutralMedianAbsEffectiveDelta.matches { it in 0.0..MAX_NEUTRAL_MEDIAN_ABS_DELTA } &&
            bio.saturationViolations.matches { it == 0 } &&
            bio.positivePathDimensions.matches { it == allDimensions } &&
            bio.zeroPathDimensions.matches { it == allDimensions } &&
            bio.negativePathDimensions.matches { it == allDimensions } &&
            bio.reliefPathDimensions.matches { it.containsAll(setOf(BioDimension.S, BioDimension.F)) } &&
            bio.vqVaeRegression.matches { !it } &&
            bio.heuristicFallbackRegression.matches { !it } &&
            bio.derivedDRegression.matches { !it } &&
            bio.omegaRegression.matches { !it } &&
            cache.passesProviderCacheGate() &&
            cache.localByteIdenticalPrefixRate.matches { it in 0.0..1.0 } &&
            cache.sealedChunksByteStable.matches { it } &&
            cache.ordinaryTurnsAppendOnly.matches { it } &&
            cache.compactionEpochMissCount.matches { it == 1 } &&
            cache.compactionRecoveryTurns.matches { it in 0..MAX_COMPACTION_RECOVERY_TURNS } &&
            cache.preservedCapabilities.matches { it == allCapabilities } &&
            temporalRuntime.allTimeSourcesVirtual.matches { it } &&
            temporalRuntime.needlessTimestampChurnCount.matches { it == 0 } &&
            temporalRuntime.heartbeatDeterministic.matches { it } &&
            temporalRuntime.silenceWindowDeterministic.matches { it } &&
            temporalRuntime.memoryTimestampDeterministic.matches { it } &&
            temporalRuntime.nonBlockingRuntime.matches { it } &&
            temporalRuntime.silentResponseFailures.matches { it == 0 }
    }

    fun isProvenanceLinkedTo(manifestFingerprints: Set<String>): Boolean = evidenceReferences().all { evidence ->
        evidence.source == EvidenceSource.AUTHENTICATED_EXPORT &&
            evidence.manifestFingerprints == manifestFingerprints &&
            evidence.artifactKinds.isNotEmpty()
    }

    private fun evidenceReferences(): List<EvidenceReference> = listOf(
        relationship.boundaryFalsePositives.evidence,
        relationship.confessionAcceptanceContinuityThroughUnrelatedTurns.evidence,
        relationship.confessionAcceptanceContinuityThroughRestart.evidence,
        relationship.confessionAcceptanceContinuityThroughScopeRestore.evidence,
        relationship.coupleContinuityThroughUnrelatedTurns.evidence,
        relationship.coupleContinuityThroughRestart.evidence,
        relationship.coupleContinuityThroughScopeRestore.evidence,
        relationship.romanticReciprocityRate.evidence,
        relationship.hotRomanceReciprocityRate.evidence,
        relationship.proceduralReplyRate.evidence,
        relationship.pairwiseWinRate.evidence,
        relationship.factualRegression.evidence,
        memory.sourceTurnLineageOverlap.evidence,
        memory.ragUniqueCandidateCapacityRegression.evidence,
        memory.mixedRetrievalSemanticsPreserved.evidence,
        memory.contrastRetrievalSemanticsPreserved.evidence,
        memory.compactionPeopleFidelity.evidence,
        memory.compactionCommitmentFidelity.evidence,
        memory.compactionUnresolvedIssueFidelity.evidence,
        memory.compactionRelationshipFactFidelity.evidence,
        memory.compactionEventOrderFidelity.evidence,
        bio.neutralMedianAbsEffectiveDelta.evidence,
        bio.saturationViolations.evidence,
        bio.positivePathDimensions.evidence,
        bio.zeroPathDimensions.evidence,
        bio.negativePathDimensions.evidence,
        bio.reliefPathDimensions.evidence,
        bio.vqVaeRegression.evidence,
        bio.heuristicFallbackRegression.evidence,
        bio.derivedDRegression.evidence,
        bio.omegaRegression.evidence,
        cache.warmCacheReadRate.evidence,
        cache.localByteIdenticalPrefixRate.evidence,
        cache.sealedChunksByteStable.evidence,
        cache.ordinaryTurnsAppendOnly.evidence,
        cache.compactionEpochMissCount.evidence,
        cache.compactionRecoveryTurns.evidence,
        cache.preservedCapabilities.evidence,
        temporalRuntime.allTimeSourcesVirtual.evidence,
        temporalRuntime.needlessTimestampChurnCount.evidence,
        temporalRuntime.heartbeatDeterministic.evidence,
        temporalRuntime.silenceWindowDeterministic.evidence,
        temporalRuntime.memoryTimestampDeterministic.evidence,
        temporalRuntime.nonBlockingRuntime.evidence,
        temporalRuntime.silentResponseFailures.evidence,
    )

    @Serializable
    data class Measurement<T>(
        val value: T?,
        val evidence: EvidenceReference,
    ) {
        internal fun matches(predicate: (T) -> Boolean): Boolean =
            evidence.isPresent() && value?.let(predicate) == true
    }

    @Serializable
    data class EvidenceReference(
        val source: EvidenceSource,
        val manifestFingerprints: Set<String>,
        val artifactKinds: Set<EvidenceArtifact>,
        val note: String? = null,
    ) {
        internal fun isPresent(): Boolean = when (source) {
            EvidenceSource.AUTHENTICATED_EXPORT -> manifestFingerprints.isNotEmpty() && artifactKinds.isNotEmpty()
            EvidenceSource.SYNTHETIC_FIXTURE, EvidenceSource.TEST_FIXTURE -> !note.isNullOrBlank()
        }

        companion object {
            fun authenticated(
                manifestFingerprints: Set<String>,
                artifactKinds: Set<EvidenceArtifact>,
            ): EvidenceReference = EvidenceReference(
                source = EvidenceSource.AUTHENTICATED_EXPORT,
                manifestFingerprints = manifestFingerprints,
                artifactKinds = artifactKinds,
            )

            fun synthetic(reason: String): EvidenceReference = EvidenceReference(
                source = EvidenceSource.SYNTHETIC_FIXTURE,
                manifestFingerprints = emptySet(),
                artifactKinds = emptySet(),
                note = reason,
            )

            fun testFixture(description: String): EvidenceReference = EvidenceReference(
                source = EvidenceSource.TEST_FIXTURE,
                manifestFingerprints = emptySet(),
                artifactKinds = emptySet(),
                note = description,
            )
        }
    }

    @Serializable
    data class Relationship(
        val boundaryFalsePositives: Measurement<Int>,
        val confessionAcceptanceContinuityThroughUnrelatedTurns: Measurement<Boolean>,
        val confessionAcceptanceContinuityThroughRestart: Measurement<Boolean>,
        val confessionAcceptanceContinuityThroughScopeRestore: Measurement<Boolean>,
        val coupleContinuityThroughUnrelatedTurns: Measurement<Boolean>,
        val coupleContinuityThroughRestart: Measurement<Boolean>,
        val coupleContinuityThroughScopeRestore: Measurement<Boolean>,
        val romanticReciprocityRate: Measurement<Double>,
        val hotRomanceReciprocityRate: Measurement<Double>,
        val proceduralReplyRate: Measurement<Double>,
        val pairwiseWinRate: Measurement<Double>,
        val factualRegression: Measurement<Boolean>,
    )

    @Serializable
    data class MemoryContext(
        val sourceTurnLineageOverlap: Measurement<Int>,
        val ragUniqueCandidateCapacityRegression: Measurement<Boolean>,
        val mixedRetrievalSemanticsPreserved: Measurement<Boolean>,
        val contrastRetrievalSemanticsPreserved: Measurement<Boolean>,
        val compactionPeopleFidelity: Measurement<Boolean>,
        val compactionCommitmentFidelity: Measurement<Boolean>,
        val compactionUnresolvedIssueFidelity: Measurement<Boolean>,
        val compactionRelationshipFactFidelity: Measurement<Boolean>,
        val compactionEventOrderFidelity: Measurement<Boolean>,
    )

    @Serializable
    data class Bio(
        val neutralMedianAbsEffectiveDelta: Measurement<Double>,
        val saturationViolations: Measurement<Int>,
        val positivePathDimensions: Measurement<Set<BioDimension>>,
        val zeroPathDimensions: Measurement<Set<BioDimension>>,
        val negativePathDimensions: Measurement<Set<BioDimension>>,
        val reliefPathDimensions: Measurement<Set<BioDimension>>,
        val vqVaeRegression: Measurement<Boolean>,
        val heuristicFallbackRegression: Measurement<Boolean>,
        val derivedDRegression: Measurement<Boolean>,
        val omegaRegression: Measurement<Boolean>,
    )

    @Serializable
    data class Cache(
        val providerMetricAvailability: ProviderMetricAvailability,
        val warmCacheReadRate: Measurement<Double>,
        val localByteIdenticalPrefixRate: Measurement<Double>,
        val sealedChunksByteStable: Measurement<Boolean>,
        val ordinaryTurnsAppendOnly: Measurement<Boolean>,
        val compactionEpochMissCount: Measurement<Int>,
        val compactionRecoveryTurns: Measurement<Int>,
        val preservedCapabilities: Measurement<Set<RequiredCapability>>,
    ) {
        internal fun passesProviderCacheGate(): Boolean = when (providerMetricAvailability) {
            ProviderMetricAvailability.REPORTED -> warmCacheReadRate.matches { it in MIN_WARM_CACHE_READ_RATE..1.0 }
            ProviderMetricAvailability.UNOBSERVABLE -> warmCacheReadRate.value == null && warmCacheReadRate.evidence.isPresent()
        }
    }

    @Serializable
    data class TemporalRuntime(
        val allTimeSourcesVirtual: Measurement<Boolean>,
        val needlessTimestampChurnCount: Measurement<Int>,
        val heartbeatDeterministic: Measurement<Boolean>,
        val silenceWindowDeterministic: Measurement<Boolean>,
        val memoryTimestampDeterministic: Measurement<Boolean>,
        val nonBlockingRuntime: Measurement<Boolean>,
        val silentResponseFailures: Measurement<Int>,
    )

    @Serializable
    enum class ProviderMetricAvailability { REPORTED, UNOBSERVABLE }

    @Serializable
    enum class BioDimension { L, P, E, S, TAU, V, M, F }

    @Serializable
    enum class RequiredCapability { VQ_VAE, EIGHT_DIMENSIONAL_STATE, RAG, RELATIONSHIP, RECENT_CONTEXT }

    @Serializable
    enum class EvidenceSource { AUTHENTICATED_EXPORT, SYNTHETIC_FIXTURE, TEST_FIXTURE }

    @Serializable
    enum class EvidenceArtifact { TRANSCRIPT, CACHE_MANIFEST, BIO_SNAPSHOTS, RETRIEVAL_TRACE, RUNTIME_TRACE, PAIRWISE_DECISIONS }

    companion object {
        private const val MIN_RECIPROCITY_RATE = 0.90
        private const val MAX_PROCEDURAL_REPLY_RATE = 0.02
        private const val MIN_PAIRWISE_WIN_RATE = 0.70
        private const val MAX_NEUTRAL_MEDIAN_ABS_DELTA = 0.02
        private const val MIN_WARM_CACHE_READ_RATE = 0.85
        private const val MAX_COMPACTION_RECOVERY_TURNS = 2

        fun unobservable(reason: String): CompanionQualityMetrics {
            require(reason.isNotBlank()) { "Unobservable evidence requires a reason" }
            return CompanionQualityMetrics(
                relationship = Relationship(
                    boundaryFalsePositives = missing(reason),
                    confessionAcceptanceContinuityThroughUnrelatedTurns = missing(reason),
                    confessionAcceptanceContinuityThroughRestart = missing(reason),
                    confessionAcceptanceContinuityThroughScopeRestore = missing(reason),
                    coupleContinuityThroughUnrelatedTurns = missing(reason),
                    coupleContinuityThroughRestart = missing(reason),
                    coupleContinuityThroughScopeRestore = missing(reason),
                    romanticReciprocityRate = missing(reason),
                    hotRomanceReciprocityRate = missing(reason),
                    proceduralReplyRate = missing(reason),
                    pairwiseWinRate = missing(reason),
                    factualRegression = missing(reason),
                ),
                memory = MemoryContext(
                    sourceTurnLineageOverlap = missing(reason),
                    ragUniqueCandidateCapacityRegression = missing(reason),
                    mixedRetrievalSemanticsPreserved = missing(reason),
                    contrastRetrievalSemanticsPreserved = missing(reason),
                    compactionPeopleFidelity = missing(reason),
                    compactionCommitmentFidelity = missing(reason),
                    compactionUnresolvedIssueFidelity = missing(reason),
                    compactionRelationshipFactFidelity = missing(reason),
                    compactionEventOrderFidelity = missing(reason),
                ),
                bio = Bio(
                    missing(reason), missing(reason), missing(reason), missing(reason), missing(reason),
                    missing(reason), missing(reason), missing(reason), missing(reason), missing(reason),
                ),
                cache = Cache(
                    providerMetricAvailability = ProviderMetricAvailability.UNOBSERVABLE,
                    warmCacheReadRate = missing(reason),
                    localByteIdenticalPrefixRate = missing(reason),
                    sealedChunksByteStable = missing(reason),
                    ordinaryTurnsAppendOnly = missing(reason),
                    compactionEpochMissCount = missing(reason),
                    compactionRecoveryTurns = missing(reason),
                    preservedCapabilities = missing(reason),
                ),
                temporalRuntime = TemporalRuntime(
                    missing(reason), missing(reason), missing(reason), missing(reason), missing(reason), missing(reason), missing(reason),
                ),
            )
        }

        private fun <T> missing(reason: String): Measurement<T> = Measurement(null, EvidenceReference.synthetic(reason))
    }
}
