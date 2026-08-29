package io.openeden.server.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

data class ProductionEvaluationInputPaths(
    val baseline: List<ProductionEvaluationEvidence.Task5RunExportPaths>,
    val candidate: List<ProductionEvaluationEvidence.Task5RunExportPaths>,
    val pairwiseDecisions: List<PairwiseDecisionArtifactPaths>,
)

data class PairwiseDecisionArtifactPaths(
    val manifest: Path,
    val decisions: Path,
)

class ProductionEvaluationReport private constructor(
    private val evidence: ProductionEvaluationEvidence.AuthenticatedABEvidence,
    pairwiseEvaluation: PairwiseEvaluation,
    pairwiseManifestFingerprints: Set<String>,
) {
    val evidenceKind: PairwiseEvaluation.EvidenceKind = PairwiseEvaluation.EvidenceKind.PRODUCTION
    val scenarioFingerprint: String = evidence.scenarioFingerprint
    val pairwiseEvaluation: PairwiseEvaluation = pairwiseEvaluation.authoritySnapshot()
    val metrics: CompanionQualityMetrics = evidence.deriveMetrics(this.pairwiseEvaluation.candidateWinRate).authoritySnapshot()
    val productionProvenance: PairwiseEvaluation.ProductionProvenance

    private val pairwiseAuditable: Boolean

    init {
        require(pairwiseManifestFingerprints.isNotEmpty()) { "Authenticated pairwise provenance is required" }
        productionProvenance = PairwiseEvaluation.ProductionProvenance(
            manifestFingerprints = evidence.manifestFingerprints,
            pairwiseManifestFingerprints = pairwiseManifestFingerprints,
            runIds = evidence.pairs.flatMap { listOf(it.baseline.header.runId, it.candidate.header.runId) }.toSet(),
            signerKeyFingerprint = evidence.signerKeyFingerprint,
        ).authoritySnapshot()
        pairwiseAuditable = isPairwiseAuditable()
    }

    fun persisted(): PairwiseEvaluation.PersistedReleaseReport = PairwiseEvaluation.PersistedReleaseReport(
        releaseDecision = releaseDecision(),
        evidenceKind = evidenceKind,
        scenarioFingerprint = scenarioFingerprint,
        metrics = metrics,
        pairwiseEvaluation = PairwiseEvaluation.PersistedPairwiseEvaluation(
            metadata = pairwiseEvaluation.metadata,
            candidateRepetitionCount = pairwiseEvaluation.candidateRepetitionCount,
            candidateWinRate = pairwiseEvaluation.candidateWinRate,
            decisions = pairwiseEvaluation.decisions,
        ),
        productionProvenance = productionProvenance,
        syntheticFixture = null,
    )

    fun releaseDecision(): PairwiseEvaluation.ReleaseDecision = if (
        pairwiseAuditable &&
        pairwiseEvaluation.decisions.none(PairwiseEvaluation.Decision::factualRegressionObserved) &&
        metrics.isProvenanceLinkedTo(evidence.manifestFingerprints) &&
        metrics.passesReleaseGate()
    ) {
        PairwiseEvaluation.ReleaseDecision.PASS
    } else {
        PairwiseEvaluation.ReleaseDecision.FAIL
    }

    private fun isPairwiseAuditable(): Boolean {
        if (!pairwiseEvaluation.isAuditable() || pairwiseEvaluation.metadata.scenarioFingerprint != evidence.scenarioFingerprint) return false
        val repetitions = pairwiseEvaluation.decisions.map(PairwiseEvaluation.Decision::candidateRepetition).toSet()
        if (repetitions != evidence.pairs.map(ProductionEvaluationEvidence.RunPair::repetition).toSet()) return false
        return pairwiseEvaluation.decisions.all { decision ->
            val pair = evidence.pairFor(decision.candidateRepetition) ?: return@all false
            if (decision.scenarioCaseId !in pair.baseline.scenarioCaseIds || decision.scenarioCaseId !in pair.candidate.scenarioCaseIds) {
                return@all false
            }
            val expectedLeft = if (decision.candidateSlot == PairwiseEvaluation.ArtifactSlot.LEFT) {
                pair.candidate.manifestFingerprint
            } else {
                pair.baseline.manifestFingerprint
            }
            val expectedRight = if (decision.candidateSlot == PairwiseEvaluation.ArtifactSlot.RIGHT) {
                pair.candidate.manifestFingerprint
            } else {
                pair.baseline.manifestFingerprint
            }
            decision.leftArtifactFingerprint == expectedLeft && decision.rightArtifactFingerprint == expectedRight
        }
    }

    private fun PairwiseEvaluation.authoritySnapshot(): PairwiseEvaluation = PairwiseEvaluation(
        metadata = metadata,
        decisions = immutableList(decisions.map { decision ->
            decision.copy(dimensionWinners = immutableMap(decision.dimensionWinners))
        }),
    )

    private fun PairwiseEvaluation.ProductionProvenance.authoritySnapshot(): PairwiseEvaluation.ProductionProvenance = copy(
        manifestFingerprints = immutableSet(manifestFingerprints),
        pairwiseManifestFingerprints = immutableSet(pairwiseManifestFingerprints),
        runIds = immutableSet(runIds),
    )

    private fun CompanionQualityMetrics.authoritySnapshot(): CompanionQualityMetrics = copy(
        relationship = relationship.copy(
            boundaryFalsePositives = relationship.boundaryFalsePositives.authoritySnapshot(),
            confessionAcceptanceContinuityThroughUnrelatedTurns =
                relationship.confessionAcceptanceContinuityThroughUnrelatedTurns.authoritySnapshot(),
            confessionAcceptanceContinuityThroughRestart =
                relationship.confessionAcceptanceContinuityThroughRestart.authoritySnapshot(),
            confessionAcceptanceContinuityThroughScopeRestore =
                relationship.confessionAcceptanceContinuityThroughScopeRestore.authoritySnapshot(),
            coupleContinuityThroughUnrelatedTurns = relationship.coupleContinuityThroughUnrelatedTurns.authoritySnapshot(),
            coupleContinuityThroughRestart = relationship.coupleContinuityThroughRestart.authoritySnapshot(),
            coupleContinuityThroughScopeRestore = relationship.coupleContinuityThroughScopeRestore.authoritySnapshot(),
            romanticReciprocityRate = relationship.romanticReciprocityRate.authoritySnapshot(),
            hotRomanceReciprocityRate = relationship.hotRomanceReciprocityRate.authoritySnapshot(),
            proceduralReplyRate = relationship.proceduralReplyRate.authoritySnapshot(),
            pairwiseWinRate = relationship.pairwiseWinRate.authoritySnapshot(),
            factualRegression = relationship.factualRegression.authoritySnapshot(),
        ),
        memory = memory.copy(
            sourceTurnLineageOverlap = memory.sourceTurnLineageOverlap.authoritySnapshot(),
            ragUniqueCandidateCapacityRegression = memory.ragUniqueCandidateCapacityRegression.authoritySnapshot(),
            mixedRetrievalSemanticsPreserved = memory.mixedRetrievalSemanticsPreserved.authoritySnapshot(),
            contrastRetrievalSemanticsPreserved = memory.contrastRetrievalSemanticsPreserved.authoritySnapshot(),
            compactionPeopleFidelity = memory.compactionPeopleFidelity.authoritySnapshot(),
            compactionCommitmentFidelity = memory.compactionCommitmentFidelity.authoritySnapshot(),
            compactionUnresolvedIssueFidelity = memory.compactionUnresolvedIssueFidelity.authoritySnapshot(),
            compactionRelationshipFactFidelity = memory.compactionRelationshipFactFidelity.authoritySnapshot(),
            compactionEventOrderFidelity = memory.compactionEventOrderFidelity.authoritySnapshot(),
        ),
        bio = bio.copy(
            neutralMedianAbsEffectiveDelta = bio.neutralMedianAbsEffectiveDelta.authoritySnapshot(),
            saturationViolations = bio.saturationViolations.authoritySnapshot(),
            positivePathDimensions = bio.positivePathDimensions.authoritySnapshot(::immutableSet),
            zeroPathDimensions = bio.zeroPathDimensions.authoritySnapshot(::immutableSet),
            negativePathDimensions = bio.negativePathDimensions.authoritySnapshot(::immutableSet),
            reliefPathDimensions = bio.reliefPathDimensions.authoritySnapshot(::immutableSet),
            vqVaeRegression = bio.vqVaeRegression.authoritySnapshot(),
            heuristicFallbackRegression = bio.heuristicFallbackRegression.authoritySnapshot(),
            derivedDRegression = bio.derivedDRegression.authoritySnapshot(),
            omegaRegression = bio.omegaRegression.authoritySnapshot(),
        ),
        cache = cache.copy(
            warmCacheReadRate = cache.warmCacheReadRate.authoritySnapshot(),
            localByteIdenticalPrefixRate = cache.localByteIdenticalPrefixRate.authoritySnapshot(),
            sealedChunksByteStable = cache.sealedChunksByteStable.authoritySnapshot(),
            ordinaryTurnsAppendOnly = cache.ordinaryTurnsAppendOnly.authoritySnapshot(),
            compactionEpochMissCount = cache.compactionEpochMissCount.authoritySnapshot(),
            compactionRecoveryTurns = cache.compactionRecoveryTurns.authoritySnapshot(),
            preservedCapabilities = cache.preservedCapabilities.authoritySnapshot(::immutableSet),
        ),
        temporalRuntime = temporalRuntime.copy(
            allTimeSourcesVirtual = temporalRuntime.allTimeSourcesVirtual.authoritySnapshot(),
            needlessTimestampChurnCount = temporalRuntime.needlessTimestampChurnCount.authoritySnapshot(),
            heartbeatDeterministic = temporalRuntime.heartbeatDeterministic.authoritySnapshot(),
            silenceWindowDeterministic = temporalRuntime.silenceWindowDeterministic.authoritySnapshot(),
            memoryTimestampDeterministic = temporalRuntime.memoryTimestampDeterministic.authoritySnapshot(),
            nonBlockingRuntime = temporalRuntime.nonBlockingRuntime.authoritySnapshot(),
            silentResponseFailures = temporalRuntime.silentResponseFailures.authoritySnapshot(),
        ),
    )

    private fun <T> CompanionQualityMetrics.Measurement<T>.authoritySnapshot(
        snapshotValue: (T) -> T = { it },
    ): CompanionQualityMetrics.Measurement<T> = copy(
        value = value?.let(snapshotValue),
        evidence = evidence.copy(
            manifestFingerprints = immutableSet(evidence.manifestFingerprints),
            artifactKinds = immutableSet(evidence.artifactKinds),
        ),
    )

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun <T> immutableSet(values: Collection<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(values))

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    companion object {
        suspend fun evaluate(paths: ProductionEvaluationInputPaths): ProductionEvaluationReport {
            val verified = ProductionEvaluationMechanics.evaluate(paths)
            return construct(verified)
        }

        private fun construct(verified: VerifiedProductionEvaluationInputs): ProductionEvaluationReport {
            val constructor = ProductionEvaluationReport::class.java.getDeclaredConstructor(
                ProductionEvaluationEvidence.AuthenticatedABEvidence::class.java,
                PairwiseEvaluation::class.java,
                Set::class.java,
            )
            check(constructor.trySetAccessible()) { "Production evaluation result constructor is not accessible to its canonical evaluator" }
            return constructor.newInstance(
                verified.evidence,
                verified.pairwiseEvaluation,
                verified.pairwiseManifestFingerprints,
            )
        }
    }
}

private class FrozenPairwiseTrust private constructor(
    private val fingerprints: Set<String>,
) {
    fun verify(
        signerKeyFingerprint: String,
        signerPublicKey: String,
        signatureValue: String,
        signedPayload: String,
    ) {
        val publicKeyBytes = runCatching { Base64.getDecoder().decode(signerPublicKey) }
            .getOrElse { throw IllegalArgumentException("Malformed evaluation signer public key", it) }
        require(signerKeyFingerprint == sha256(publicKeyBytes)) { "Evaluation signer fingerprint does not match its public key" }
        require(signerKeyFingerprint.lowercase() in fingerprints) { "Evaluation manifest signer is not configured as trusted" }
        val signatureBytes = runCatching { Base64.getDecoder().decode(signatureValue) }
            .getOrElse { throw IllegalArgumentException("Malformed evaluation manifest signature", it) }
        val publicKey = runCatching {
            KeyFactory.getInstance(SIGNATURE_ALGORITHM).generatePublic(X509EncodedKeySpec(publicKeyBytes))
        }.getOrElse { throw IllegalArgumentException("Invalid evaluation signer public key", it) }
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(publicKey)
            update(signedPayload.toByteArray(StandardCharsets.UTF_8))
        }
        require(signature.verify(signatureBytes)) { "Evaluation manifest signature mismatch" }
    }

    companion object {
        private const val TRUSTED_SIGNERS_ENV = "OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS"

        fun fromDeploymentEnvironment(): FrozenPairwiseTrust =
            FrozenPairwiseTrust(parseFingerprints(System.getenv(TRUSTED_SIGNERS_ENV)))

        private fun parseFingerprints(configured: String?): Set<String> {
            require(!configured.isNullOrBlank()) { "No trusted production evaluation signer fingerprints are configured" }
            return validateFingerprints(configured.split(',', ';').map(String::trim).filter(String::isNotEmpty).toSet())
        }

        private fun validateFingerprints(values: Set<String>): Set<String> = values.map(String::lowercase).toSet().also { fingerprints ->
            require(fingerprints.isNotEmpty() && fingerprints.all { it.matches(Regex("[0-9a-f]{64}")) }) {
                "Trusted signer fingerprints must be SHA-256 values"
            }
        }
    }
}

private object ProductionEvaluationMechanics {
    private val frozenTrustedSigners = FrozenPairwiseTrust.fromDeploymentEnvironment()

    suspend fun evaluate(paths: ProductionEvaluationInputPaths): VerifiedProductionEvaluationInputs = withContext(Dispatchers.IO) {
        val evidence = ProductionEvaluationEvidence.loadTask5ABExports(
            ProductionEvaluationEvidence.Task5ABExportPaths(paths.baseline, paths.candidate),
        )
        val pairwise = loadPairwiseDecisions(paths.pairwiseDecisions, frozenTrustedSigners, evidence)
        VerifiedProductionEvaluationInputs(
            evidence = evidence,
            pairwiseEvaluation = pairwise.evaluation,
            pairwiseManifestFingerprints = pairwise.manifestFingerprints,
        )
    }

    private fun loadPairwiseDecisions(
        paths: List<PairwiseDecisionArtifactPaths>,
        trustedSigners: FrozenPairwiseTrust,
        evidence: ProductionEvaluationEvidence.AuthenticatedABEvidence,
    ): LoadedPairwiseEvaluation {
        require(paths.isNotEmpty()) { "Pairwise decision artifact paths are required" }
        val loaded = paths.map { loadPairwiseDecisionArtifact(it, trustedSigners) }
        require(loaded.all { it.signerKeyFingerprint == evidence.signerKeyFingerprint }) {
            "Pairwise decisions and A/B evidence must share one trusted signer"
        }
        val metadata = loaded.map { it.evaluation.metadata }.distinct().single()
        val evaluation = PairwiseEvaluation(metadata, loaded.flatMap { it.evaluation.decisions })
        require(evaluation.decisions.map(PairwiseEvaluation.Decision::decisionId).distinct().size == evaluation.decisions.size) {
            "Pairwise decision artifacts contain duplicate decisions"
        }
        return LoadedPairwiseEvaluation(evaluation, loaded.map(LoadedPairwiseArtifact::manifestFingerprint).toSet())
    }

    private fun loadPairwiseDecisionArtifact(
        paths: PairwiseDecisionArtifactPaths,
        trustedSigners: FrozenPairwiseTrust,
    ): LoadedPairwiseArtifact {
        val manifestPath = paths.manifest.toAbsolutePath().normalize()
        require(manifestPath.fileName.toString() == PAIRWISE_MANIFEST_FILE && Files.isRegularFile(manifestPath)) {
            "Missing authenticated pairwise manifest"
        }
        val root = manifestPath.parent
        val manifestBytes = Files.readAllBytes(manifestPath)
        val manifest = runCatching {
            ProductionEvaluationEvidence.json.decodeFromString<PairwiseArtifactManifest>(
                manifestBytes.toString(StandardCharsets.UTF_8),
            )
        }.getOrElse { throw IllegalArgumentException("Malformed pairwise manifest", it) }
        require(manifest.schemaVersion == PAIRWISE_SCHEMA_VERSION) { "Unsupported pairwise manifest schema" }
        val unsigned = UnsignedPairwiseArtifactManifest(
            manifest.schemaVersion,
            manifest.signerKeyFingerprint,
            manifest.signerPublicKey,
            manifest.decisionsFileName,
            manifest.decisionsSha256,
        )
        trustedSigners.verify(
            manifest.signerKeyFingerprint,
            manifest.signerPublicKey,
            manifest.signature,
            ProductionEvaluationEvidence.json.encodeToString(unsigned),
        )
        require(manifest.decisionsFileName == PAIRWISE_DECISIONS_FILE) { "Unexpected pairwise decision file name" }
        val decisionsPath = paths.decisions.toAbsolutePath().normalize()
        require(decisionsPath == root.resolve(PAIRWISE_DECISIONS_FILE).normalize() && decisionsPath.parent == root) {
            "Explicit pairwise decision path does not match its manifest"
        }
        require(Files.isRegularFile(decisionsPath)) { "Missing pairwise decision artifact" }
        val decisionBytes = Files.readAllBytes(decisionsPath)
        require(sha256(decisionBytes) == manifest.decisionsSha256) { "Pairwise decision artifact hash mismatch" }
        val evaluation = runCatching {
            ProductionEvaluationEvidence.json.decodeFromString<PairwiseEvaluation>(decisionBytes.toString(StandardCharsets.UTF_8))
        }.getOrElse { throw IllegalArgumentException("Malformed typed pairwise decision artifact", it) }
        return LoadedPairwiseArtifact(
            evaluation,
            manifest.signerKeyFingerprint,
            sha256(manifestBytes),
        )
    }

    private data class LoadedPairwiseEvaluation(
        val evaluation: PairwiseEvaluation,
        val manifestFingerprints: Set<String>,
    )

    private data class LoadedPairwiseArtifact(
        val evaluation: PairwiseEvaluation,
        val signerKeyFingerprint: String,
        val manifestFingerprint: String,
    )
}

private data class VerifiedProductionEvaluationInputs(
    val evidence: ProductionEvaluationEvidence.AuthenticatedABEvidence,
    val pairwiseEvaluation: PairwiseEvaluation,
    val pairwiseManifestFingerprints: Set<String>,
)

@Serializable
internal data class PairwiseArtifactManifest(
    val schemaVersion: Int,
    val signerKeyFingerprint: String,
    val signerPublicKey: String,
    val decisionsFileName: String,
    val decisionsSha256: String,
    val signature: String,
)

@Serializable
internal data class UnsignedPairwiseArtifactManifest(
    val schemaVersion: Int,
    val signerKeyFingerprint: String,
    val signerPublicKey: String,
    val decisionsFileName: String,
    val decisionsSha256: String,
)

internal const val PAIRWISE_SCHEMA_VERSION = 1
internal const val PAIRWISE_MANIFEST_FILE = "pairwise-evaluation-manifest.json"
internal const val PAIRWISE_DECISIONS_FILE = "pairwise-decisions.json"
internal const val SIGNATURE_ALGORITHM = "Ed25519"

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
