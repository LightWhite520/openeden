package io.openeden.server.evaluation

import kotlinx.serialization.Serializable

@Serializable
data class PairwiseEvaluation(
    val metadata: Metadata,
    val decisions: List<Decision>,
) {
    val candidateRepetitionCount: Int
        get() = decisions.map(Decision::candidateRepetition).distinct().size

    val candidateWinRate: Double
        get() = if (decisions.isEmpty()) 0.0 else decisions.count(Decision::candidateWon).toDouble() / decisions.size

    fun isAuditable(): Boolean {
        val repetitions = decisions.map(Decision::candidateRepetition).toSet()
        val requiredRepetitions = when (metadata.providerSeedControl) {
            ProviderSeedControl.AVAILABLE -> 1
            ProviderSeedControl.UNAVAILABLE -> MIN_REPETITIONS_WITHOUT_SEED_CONTROL
        }
        return metadata.evaluatorVersion.isNotBlank() &&
            metadata.evaluatorModel.isNotBlank() &&
            metadata.scenarioFingerprint.isNotBlank() &&
            metadata.blindProtocolVersion.isNotBlank() &&
            decisions.isNotEmpty() &&
            decisions.map(Decision::decisionId).distinct().size == decisions.size &&
            repetitions == (1..candidateRepetitionCount).toSet() &&
            candidateRepetitionCount >= requiredRepetitions &&
            decisions.all(Decision::isAuditable)
    }

    @Serializable
    data class Metadata(
        val evaluatorVersion: String,
        val evaluatorModel: String,
        val scenarioFingerprint: String,
        val providerSeedControl: ProviderSeedControl,
        val blindProtocolVersion: String,
    )

    @Serializable
    data class Decision(
        val decisionId: String,
        val scenarioCaseId: String,
        val candidateRepetition: Int,
        val leftArtifactFingerprint: String,
        val rightArtifactFingerprint: String,
        val candidateSlot: ArtifactSlot,
        val winner: Winner,
        val dimensionWinners: Map<JudgeDimension, Winner>,
        val factualRegressionObserved: Boolean,
        val rationale: String,
    ) {
        internal fun isAuditable(): Boolean = decisionId.isNotBlank() &&
            scenarioCaseId.isNotBlank() &&
            candidateRepetition > 0 &&
            leftArtifactFingerprint.isNotBlank() &&
            rightArtifactFingerprint.isNotBlank() &&
            leftArtifactFingerprint != rightArtifactFingerprint &&
            dimensionWinners.keys == JudgeDimension.entries.toSet() &&
            winner == expectedOverallWinner() &&
            rationale.isNotBlank()

        internal fun candidateWon(): Boolean = when (winner) {
            Winner.LEFT -> candidateSlot == ArtifactSlot.LEFT
            Winner.RIGHT -> candidateSlot == ArtifactSlot.RIGHT
            Winner.TIE -> false
        }

        private fun expectedOverallWinner(): Winner {
            val winners = dimensionWinners.values.toSet()
            return if (winners.size == 1) winners.single() else Winner.TIE
        }
    }

    class ReleaseReport private constructor(
        val evidenceKind: EvidenceKind,
        val scenarioFingerprint: String,
        val metrics: CompanionQualityMetrics,
        val pairwiseEvaluation: PairwiseEvaluation?,
        val productionProvenance: ProductionProvenance?,
        val syntheticFixture: SyntheticFixtureDeclaration?,
    ) {
        fun persisted(): PersistedReleaseReport = PersistedReleaseReport(
            releaseDecision = releaseDecision(),
            evidenceKind = evidenceKind,
            scenarioFingerprint = scenarioFingerprint,
            metrics = metrics,
            pairwiseEvaluation = pairwiseEvaluation?.let {
                PersistedPairwiseEvaluation(
                    metadata = it.metadata,
                    candidateRepetitionCount = it.candidateRepetitionCount,
                    candidateWinRate = it.candidateWinRate,
                    decisions = it.decisions,
                )
            },
            productionProvenance = productionProvenance,
            syntheticFixture = syntheticFixture,
        )

        fun releaseDecision(): ReleaseDecision {
            if (evidenceKind == EvidenceKind.SYNTHETIC_FIXTURE) return ReleaseDecision.SYNTHETIC_ONLY
            return ReleaseDecision.FAIL
        }

        companion object {
            fun synthetic(scenarioFingerprint: String): ReleaseReport = ReleaseReport(
                evidenceKind = EvidenceKind.SYNTHETIC_FIXTURE,
                scenarioFingerprint = scenarioFingerprint,
                metrics = CompanionQualityMetrics.unobservable(SYNTHETIC_REASON),
                pairwiseEvaluation = null,
                productionProvenance = null,
                syntheticFixture = SyntheticFixtureDeclaration(
                    nonProduction = true,
                    personaFree = true,
                ),
            )

            private const val SYNTHETIC_REASON = "Synthetic fixture does not observe production quality or runtime evidence"
        }
    }

    @Serializable
    data class PersistedReleaseReport(
        val releaseDecision: ReleaseDecision,
        val evidenceKind: EvidenceKind,
        val scenarioFingerprint: String,
        val metrics: CompanionQualityMetrics,
        val pairwiseEvaluation: PersistedPairwiseEvaluation?,
        val productionProvenance: ProductionProvenance?,
        val syntheticFixture: SyntheticFixtureDeclaration?,
    )

    @Serializable
    data class PersistedPairwiseEvaluation(
        val metadata: Metadata,
        val candidateRepetitionCount: Int,
        val candidateWinRate: Double,
        val decisions: List<Decision>,
    )

    @Serializable
    data class ProductionProvenance(
        val manifestFingerprints: Set<String>,
        val pairwiseManifestFingerprints: Set<String>,
        val runIds: Set<String>,
        val signerKeyFingerprint: String,
    )

    @Serializable
    data class SyntheticFixtureDeclaration(
        val nonProduction: Boolean,
        val personaFree: Boolean,
    )

    @Serializable
    enum class ProviderSeedControl { AVAILABLE, UNAVAILABLE }

    @Serializable
    enum class ArtifactSlot { LEFT, RIGHT }

    @Serializable
    enum class JudgeDimension { ATRI_FIDELITY, COMPANION_QUALITY }

    @Serializable
    enum class Winner { LEFT, RIGHT, TIE }

    @Serializable
    enum class EvidenceKind { PRODUCTION, SYNTHETIC_FIXTURE }

    @Serializable
    enum class ReleaseDecision { PASS, FAIL, SYNTHETIC_ONLY }

    companion object {
        const val MIN_REPETITIONS_WITHOUT_SEED_CONTROL = 3
    }
}
