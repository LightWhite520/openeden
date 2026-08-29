package io.openeden.server.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CompanionQualityMetricsTest {
    @Test
    fun `qualifying section 18 measurements pass every threshold`() {
        assertTrue(qualifyingMetrics().passesReleaseGate())
    }

    @Test
    fun `every section 18 threshold independently blocks release`() {
        val passing = qualifyingMetrics()
        val allDimensions = CompanionQualityMetrics.BioDimension.entries.toSet()
        val allCapabilities = CompanionQualityMetrics.RequiredCapability.entries.toSet()
        val regressions = listOf(
            "boundary false positive" to passing.copy(
                relationship = passing.relationship.copy(boundaryFalsePositives = measured(1)),
            ),
            "confession acceptance continuity through unrelated turns" to passing.copy(
                relationship = passing.relationship.copy(confessionAcceptanceContinuityThroughUnrelatedTurns = measured(false)),
            ),
            "confession acceptance continuity through restart" to passing.copy(
                relationship = passing.relationship.copy(confessionAcceptanceContinuityThroughRestart = measured(false)),
            ),
            "confession acceptance continuity through scope restore" to passing.copy(
                relationship = passing.relationship.copy(confessionAcceptanceContinuityThroughScopeRestore = measured(false)),
            ),
            "couple continuity through unrelated turns" to passing.copy(
                relationship = passing.relationship.copy(coupleContinuityThroughUnrelatedTurns = measured(false)),
            ),
            "couple continuity through restart" to passing.copy(
                relationship = passing.relationship.copy(coupleContinuityThroughRestart = measured(false)),
            ),
            "couple continuity through scope restore" to passing.copy(
                relationship = passing.relationship.copy(coupleContinuityThroughScopeRestore = measured(false)),
            ),
            "romantic reciprocity" to passing.copy(
                relationship = passing.relationship.copy(romanticReciprocityRate = measured(0.899)),
            ),
            "hot romance reciprocity" to passing.copy(
                relationship = passing.relationship.copy(hotRomanceReciprocityRate = measured(0.899)),
            ),
            "procedural reply rate" to passing.copy(
                relationship = passing.relationship.copy(proceduralReplyRate = measured(0.02)),
            ),
            "pairwise win rate" to passing.copy(
                relationship = passing.relationship.copy(pairwiseWinRate = measured(0.699)),
            ),
            "factual regression" to passing.copy(
                relationship = passing.relationship.copy(factualRegression = measured(true)),
            ),
            "memory lineage overlap" to passing.copy(
                memory = passing.memory.copy(sourceTurnLineageOverlap = measured(1)),
            ),
            "RAG capacity regression with unique candidates" to passing.copy(
                memory = passing.memory.copy(ragUniqueCandidateCapacityRegression = measured(true)),
            ),
            "MIXED retrieval semantics" to passing.copy(
                memory = passing.memory.copy(mixedRetrievalSemanticsPreserved = measured(false)),
            ),
            "CONTRAST retrieval semantics" to passing.copy(
                memory = passing.memory.copy(contrastRetrievalSemanticsPreserved = measured(false)),
            ),
            "compaction people fidelity" to passing.copy(
                memory = passing.memory.copy(compactionPeopleFidelity = measured(false)),
            ),
            "compaction commitment fidelity" to passing.copy(
                memory = passing.memory.copy(compactionCommitmentFidelity = measured(false)),
            ),
            "compaction unresolved issue fidelity" to passing.copy(
                memory = passing.memory.copy(compactionUnresolvedIssueFidelity = measured(false)),
            ),
            "compaction relationship fact fidelity" to passing.copy(
                memory = passing.memory.copy(compactionRelationshipFactFidelity = measured(false)),
            ),
            "compaction event order fidelity" to passing.copy(
                memory = passing.memory.copy(compactionEventOrderFidelity = measured(false)),
            ),
            "neutral median effective delta" to passing.copy(
                bio = passing.bio.copy(neutralMedianAbsEffectiveDelta = measured(0.020_001)),
            ),
            "saturation violation" to passing.copy(
                bio = passing.bio.copy(saturationViolations = measured(1)),
            ),
            "positive 8D path coverage" to passing.copy(
                bio = passing.bio.copy(positivePathDimensions = measured(allDimensions - CompanionQualityMetrics.BioDimension.F)),
            ),
            "zero 8D path coverage" to passing.copy(
                bio = passing.bio.copy(zeroPathDimensions = measured(allDimensions - CompanionQualityMetrics.BioDimension.M)),
            ),
            "negative 8D path coverage" to passing.copy(
                bio = passing.bio.copy(negativePathDimensions = measured(allDimensions - CompanionQualityMetrics.BioDimension.S)),
            ),
            "S relief path" to passing.copy(
                bio = passing.bio.copy(reliefPathDimensions = measured(setOf(CompanionQualityMetrics.BioDimension.F))),
            ),
            "F relief path" to passing.copy(
                bio = passing.bio.copy(reliefPathDimensions = measured(setOf(CompanionQualityMetrics.BioDimension.S))),
            ),
            "VQ-VAE regression" to passing.copy(
                bio = passing.bio.copy(vqVaeRegression = measured(true)),
            ),
            "heuristic fallback regression" to passing.copy(
                bio = passing.bio.copy(heuristicFallbackRegression = measured(true)),
            ),
            "derived D regression" to passing.copy(
                bio = passing.bio.copy(derivedDRegression = measured(true)),
            ),
            "Omega regression" to passing.copy(
                bio = passing.bio.copy(omegaRegression = measured(true)),
            ),
            "provider cache read rate" to passing.copy(
                cache = passing.cache.copy(warmCacheReadRate = measured(0.849)),
            ),
            "local byte-identical prefix rate" to passing.copy(
                cache = passing.cache.copy(localByteIdenticalPrefixRate = measured(1.001)),
            ),
            "sealed chunk byte stability" to passing.copy(
                cache = passing.cache.copy(sealedChunksByteStable = measured(false)),
            ),
            "ordinary turn append-only history" to passing.copy(
                cache = passing.cache.copy(ordinaryTurnsAppendOnly = measured(false)),
            ),
            "compaction epoch miss count" to passing.copy(
                cache = passing.cache.copy(compactionEpochMissCount = measured(2)),
            ),
            "compaction epoch recovery" to passing.copy(
                cache = passing.cache.copy(compactionRecoveryTurns = measured(3)),
            ),
            "capability preservation" to passing.copy(
                cache = passing.cache.copy(
                    preservedCapabilities = measured(allCapabilities - CompanionQualityMetrics.RequiredCapability.RAG),
                ),
            ),
            "virtual time source" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(allTimeSourcesVirtual = measured(false)),
            ),
            "needless timestamp churn" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(needlessTimestampChurnCount = measured(1)),
            ),
            "heartbeat determinism" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(heartbeatDeterministic = measured(false)),
            ),
            "silence window determinism" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(silenceWindowDeterministic = measured(false)),
            ),
            "memory timestamp determinism" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(memoryTimestampDeterministic = measured(false)),
            ),
            "non-blocking runtime" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(nonBlockingRuntime = measured(false)),
            ),
            "silent response failure" to passing.copy(
                temporalRuntime = passing.temporalRuntime.copy(silentResponseFailures = measured(1)),
            ),
        )

        regressions.forEach { (name, metrics) ->
            assertFalse(metrics.passesReleaseGate(), name)
        }
    }

    @Test
    fun `required unobservable measurement blocks release`() {
        val passing = qualifyingMetrics()

        assertFalse(
            passing.copy(
                memory = passing.memory.copy(
                    ragUniqueCandidateCapacityRegression = CompanionQualityMetrics.Measurement(
                        null,
                        CompanionQualityMetrics.EvidenceReference.testFixture("unobservable RAG fixture"),
                    ),
                ),
            ).passesReleaseGate(),
        )
    }

    @Test
    fun `unobservable provider cache usage passes only without a fabricated provider rate`() {
        val passing = qualifyingMetrics()
        val unobservable = passing.copy(
            cache = passing.cache.copy(
                providerMetricAvailability = CompanionQualityMetrics.ProviderMetricAvailability.UNOBSERVABLE,
                warmCacheReadRate = CompanionQualityMetrics.Measurement(
                    null,
                    CompanionQualityMetrics.EvidenceReference.testFixture("provider usage absent"),
                ),
                localByteIdenticalPrefixRate = measured(0.93),
            ),
        )

        assertTrue(unobservable.passesReleaseGate())
        assertFalse(
            unobservable.copy(
                cache = unobservable.cache.copy(warmCacheReadRate = measured(0.0)),
            ).passesReleaseGate(),
        )
        assertFalse(
            unobservable.copy(
                cache = unobservable.cache.copy(
                    localByteIdenticalPrefixRate = CompanionQualityMetrics.Measurement(
                        null,
                        CompanionQualityMetrics.EvidenceReference.testFixture("local prefix not measured"),
                    ),
                ),
            ).passesReleaseGate(),
        )
    }

    @Test
    fun `pairwise decisions retain evaluator metadata and derive candidate repetitions`() {
        val evaluation = qualifyingPairwiseEvaluation()

        assertTrue(evaluation.isAuditable())
        assertEquals(4, evaluation.candidateRepetitionCount)
        assertEquals(0.75, evaluation.candidateWinRate)
        assertEquals(listOf(1, 2, 3, 4), evaluation.decisions.map(PairwiseEvaluation.Decision::candidateRepetition))
    }

    @Test
    fun `candidate wins are derived from blinded left right decisions`() {
        val evaluation = qualifyingPairwiseEvaluation()

        assertEquals(
            listOf(
                PairwiseEvaluation.ArtifactSlot.LEFT,
                PairwiseEvaluation.ArtifactSlot.RIGHT,
                PairwiseEvaluation.ArtifactSlot.LEFT,
                PairwiseEvaluation.ArtifactSlot.RIGHT,
            ),
            evaluation.decisions.map(PairwiseEvaluation.Decision::candidateSlot),
        )
        assertEquals(
            listOf(
                PairwiseEvaluation.Winner.LEFT,
                PairwiseEvaluation.Winner.RIGHT,
                PairwiseEvaluation.Winner.RIGHT,
                PairwiseEvaluation.Winner.RIGHT,
            ),
            evaluation.decisions.map(PairwiseEvaluation.Decision::winner),
        )
        assertEquals(0.75, evaluation.candidateWinRate)
    }

    @Test
    fun `candidate needs three repetitions when provider seed control is unavailable`() {
        val twoRepetitions = qualifyingPairwiseEvaluation().copy(
            decisions = qualifyingPairwiseEvaluation().decisions.take(2),
        )

        assertFalse(twoRepetitions.isAuditable())
        assertFalse(
            qualifyingPairwiseEvaluation().copy(
                decisions = qualifyingPairwiseEvaluation().decisions.filter { it.candidateRepetition != 2 },
            ).isAuditable(),
        )
        assertTrue(
            twoRepetitions.copy(
                metadata = twoRepetitions.metadata.copy(
                    providerSeedControl = PairwiseEvaluation.ProviderSeedControl.AVAILABLE,
                ),
                decisions = twoRepetitions.decisions.take(1),
            ).isAuditable(),
        )
    }

    @Test
    fun `pairwise audit rejects missing evaluator metadata and inferred decisions`() {
        val passing = qualifyingPairwiseEvaluation()

        assertFalse(passing.copy(metadata = passing.metadata.copy(evaluatorVersion = "")).isAuditable())
        assertFalse(passing.copy(metadata = passing.metadata.copy(evaluatorModel = "")).isAuditable())
        assertFalse(passing.copy(metadata = passing.metadata.copy(scenarioFingerprint = "")).isAuditable())
        assertFalse(passing.copy(metadata = passing.metadata.copy(blindProtocolVersion = "")).isAuditable())
        assertFalse(
            passing.copy(
                decisions = passing.decisions.mapIndexed { index, decision ->
                    if (index == 0) decision.copy(rationale = "") else decision
                },
            ).isAuditable(),
        )
        assertFalse(
            passing.copy(
                decisions = passing.decisions.mapIndexed { index, decision ->
                    if (index == 0) {
                        decision.copy(
                            winner = PairwiseEvaluation.Winner.LEFT,
                            dimensionWinners = mapOf(
                                PairwiseEvaluation.JudgeDimension.ATRI_FIDELITY to PairwiseEvaluation.Winner.LEFT,
                                PairwiseEvaluation.JudgeDimension.COMPANION_QUALITY to PairwiseEvaluation.Winner.RIGHT,
                            ),
                        )
                    } else {
                        decision
                    }
                },
            ).isAuditable(),
        )
        assertFalse(
            passing.copy(
                decisions = passing.decisions.mapIndexed { index, decision ->
                    if (index == 0) {
                        decision.copy(
                            dimensionWinners = decision.dimensionWinners - PairwiseEvaluation.JudgeDimension.COMPANION_QUALITY,
                        )
                    } else {
                        decision
                    }
                },
            ).isAuditable(),
        )
        assertFalse(
            passing.copy(
                decisions = passing.decisions.mapIndexed { index, decision ->
                    if (index == 0) {
                        decision.copy(rightArtifactFingerprint = decision.leftArtifactFingerprint)
                    } else {
                        decision
                    }
                },
            ).isAuditable(),
        )
    }

    @Test
    fun `synthetic report declares non production persona free evidence`() {
        val report = PairwiseEvaluation.ReleaseReport.synthetic("synthetic-scenario").persisted()
        val json = Json.parseToJsonElement(Json.encodeToString(report)).jsonObject
        val declaration = json.getValue("syntheticFixture").jsonObject

        assertEquals("true", declaration.getValue("nonProduction").jsonPrimitive.content)
        assertEquals("true", declaration.getValue("personaFree").jsonPrimitive.content)
        assertEquals("SYNTHETIC_ONLY", json.getValue("releaseDecision").jsonPrimitive.content)
    }

    private fun qualifyingMetrics(): CompanionQualityMetrics {
        val allDimensions = CompanionQualityMetrics.BioDimension.entries.toSet()
        return CompanionQualityMetrics(
            relationship = CompanionQualityMetrics.Relationship(
                boundaryFalsePositives = measured(0),
                confessionAcceptanceContinuityThroughUnrelatedTurns = measured(true),
                confessionAcceptanceContinuityThroughRestart = measured(true),
                confessionAcceptanceContinuityThroughScopeRestore = measured(true),
                coupleContinuityThroughUnrelatedTurns = measured(true),
                coupleContinuityThroughRestart = measured(true),
                coupleContinuityThroughScopeRestore = measured(true),
                romanticReciprocityRate = measured(0.90),
                hotRomanceReciprocityRate = measured(0.90),
                proceduralReplyRate = measured(0.019),
                pairwiseWinRate = measured(0.75),
                factualRegression = measured(false),
            ),
            memory = CompanionQualityMetrics.MemoryContext(
                sourceTurnLineageOverlap = measured(0),
                ragUniqueCandidateCapacityRegression = measured(false),
                mixedRetrievalSemanticsPreserved = measured(true),
                contrastRetrievalSemanticsPreserved = measured(true),
                compactionPeopleFidelity = measured(true),
                compactionCommitmentFidelity = measured(true),
                compactionUnresolvedIssueFidelity = measured(true),
                compactionRelationshipFactFidelity = measured(true),
                compactionEventOrderFidelity = measured(true),
            ),
            bio = CompanionQualityMetrics.Bio(
                neutralMedianAbsEffectiveDelta = measured(0.02),
                saturationViolations = measured(0),
                positivePathDimensions = measured(allDimensions),
                zeroPathDimensions = measured(allDimensions),
                negativePathDimensions = measured(allDimensions),
                reliefPathDimensions = measured(
                    setOf(CompanionQualityMetrics.BioDimension.S, CompanionQualityMetrics.BioDimension.F),
                ),
                vqVaeRegression = measured(false),
                heuristicFallbackRegression = measured(false),
                derivedDRegression = measured(false),
                omegaRegression = measured(false),
            ),
            cache = CompanionQualityMetrics.Cache(
                providerMetricAvailability = CompanionQualityMetrics.ProviderMetricAvailability.REPORTED,
                warmCacheReadRate = measured(0.85),
                localByteIdenticalPrefixRate = measured(0.93),
                sealedChunksByteStable = measured(true),
                ordinaryTurnsAppendOnly = measured(true),
                compactionEpochMissCount = measured(1),
                compactionRecoveryTurns = measured(2),
                preservedCapabilities = measured(CompanionQualityMetrics.RequiredCapability.entries.toSet()),
            ),
            temporalRuntime = CompanionQualityMetrics.TemporalRuntime(
                allTimeSourcesVirtual = measured(true),
                needlessTimestampChurnCount = measured(0),
                heartbeatDeterministic = measured(true),
                silenceWindowDeterministic = measured(true),
                memoryTimestampDeterministic = measured(true),
                nonBlockingRuntime = measured(true),
                silentResponseFailures = measured(0),
            ),
        )
    }

    private fun qualifyingPairwiseEvaluation(): PairwiseEvaluation = PairwiseEvaluation(
        metadata = PairwiseEvaluation.Metadata(
            evaluatorVersion = "judge-v1",
            evaluatorModel = "evaluator-model",
            scenarioFingerprint = "scenario-sha256",
            providerSeedControl = PairwiseEvaluation.ProviderSeedControl.UNAVAILABLE,
            blindProtocolVersion = "blind-left-right-v1",
        ),
        decisions = listOf(
            decision(1, candidateSlot = PairwiseEvaluation.ArtifactSlot.LEFT, candidateWins = true),
            decision(2, candidateSlot = PairwiseEvaluation.ArtifactSlot.RIGHT, candidateWins = true),
            decision(3, candidateSlot = PairwiseEvaluation.ArtifactSlot.LEFT, candidateWins = false),
            decision(4, candidateSlot = PairwiseEvaluation.ArtifactSlot.RIGHT, candidateWins = true),
        ),
    )

    private fun decision(
        repetition: Int,
        candidateSlot: PairwiseEvaluation.ArtifactSlot,
        candidateWins: Boolean,
    ): PairwiseEvaluation.Decision {
        val candidateFingerprint = "candidate-$repetition-sha256"
        val baselineFingerprint = "baseline-$repetition-sha256"
        val winner = when {
            candidateWins -> candidateSlot.toWinner()
            candidateSlot == PairwiseEvaluation.ArtifactSlot.LEFT -> PairwiseEvaluation.Winner.RIGHT
            else -> PairwiseEvaluation.Winner.LEFT
        }
        return PairwiseEvaluation.Decision(
            decisionId = "decision-$repetition",
            scenarioCaseId = "case-$repetition",
            candidateRepetition = repetition,
            leftArtifactFingerprint = if (candidateSlot == PairwiseEvaluation.ArtifactSlot.LEFT) candidateFingerprint else baselineFingerprint,
            rightArtifactFingerprint = if (candidateSlot == PairwiseEvaluation.ArtifactSlot.RIGHT) candidateFingerprint else baselineFingerprint,
            candidateSlot = candidateSlot,
            winner = winner,
            dimensionWinners = PairwiseEvaluation.JudgeDimension.entries.associateWith { winner },
            factualRegressionObserved = false,
            rationale = "Structured blind fixture decision $repetition",
        )
    }

    private fun PairwiseEvaluation.ArtifactSlot.toWinner(): PairwiseEvaluation.Winner = when (this) {
        PairwiseEvaluation.ArtifactSlot.LEFT -> PairwiseEvaluation.Winner.LEFT
        PairwiseEvaluation.ArtifactSlot.RIGHT -> PairwiseEvaluation.Winner.RIGHT
    }

    private fun <T> measured(value: T): CompanionQualityMetrics.Measurement<T> =
        CompanionQualityMetrics.Measurement(
            value,
            CompanionQualityMetrics.EvidenceReference.testFixture("gate mechanics"),
        )
}
