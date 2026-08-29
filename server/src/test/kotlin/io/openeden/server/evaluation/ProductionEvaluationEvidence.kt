package io.openeden.server.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProductionEvaluationEvidence {
    enum class RequiredArtifact(val fileName: String) {
        TRANSCRIPT("transcript.jsonl"),
        BIO_SNAPSHOTS("bio.csv"),
        RELATIONSHIP_EVENTS("relationship-events.jsonl"),
        CACHE_MANIFEST("prompt-cache-manifest.jsonl"),
        EVALUATION_REPORT("evaluation-report.md"),
        RETRIEVAL_TRACE("retrieval-trace.jsonl"),
        RUNTIME_TRACE("runtime-trace.jsonl"),
    }

    data class Task5RunExportPaths(
        val manifest: Path,
        val transcript: Path,
        val bioSnapshots: Path,
        val relationshipEvents: Path,
        val cacheManifest: Path,
        val evaluationReport: Path,
        val retrievalTrace: Path,
        val runtimeTrace: Path,
    ) {
        fun pathFor(artifact: RequiredArtifact): Path = when (artifact) {
            RequiredArtifact.TRANSCRIPT -> transcript
            RequiredArtifact.BIO_SNAPSHOTS -> bioSnapshots
            RequiredArtifact.RELATIONSHIP_EVENTS -> relationshipEvents
            RequiredArtifact.CACHE_MANIFEST -> cacheManifest
            RequiredArtifact.EVALUATION_REPORT -> evaluationReport
            RequiredArtifact.RETRIEVAL_TRACE -> retrievalTrace
            RequiredArtifact.RUNTIME_TRACE -> runtimeTrace
        }
    }

    data class Task5ABExportPaths(
        val baseline: List<Task5RunExportPaths>,
        val candidate: List<Task5RunExportPaths>,
    )

    @Serializable
    data class ArtifactHeader(
        val runId: String,
        val variant: EvaluationVariant,
        val scenarioFingerprint: String,
        val repetition: Int,
    )

    @Serializable
    data class TranscriptArtifact(
        val header: ArtifactHeader,
        val turns: List<TranscriptTurn>,
        val continuity: List<ContinuityCheck>,
    )

    @Serializable
    data class TranscriptTurn(
        val turnId: String,
        val scenarioCaseId: String,
        val boundaryGoldenCase: Boolean,
        val classifiedAsBoundary: Boolean,
        val directRomanceCase: Boolean,
        val reciprocalRomance: Boolean,
        val hotRomanceCase: Boolean,
        val reciprocalHotRomance: Boolean,
        val operationalContext: Boolean,
        val proceduralReply: Boolean,
        val factualRegression: Boolean,
    )

    @Serializable
    data class ContinuityCheck(
        val fact: ContinuityFact,
        val context: ContinuityContext,
        val preserved: Boolean,
    )

    @Serializable
    enum class ContinuityFact { CONFESSION_ACCEPTANCE, COUPLE_STATUS }

    @Serializable
    enum class ContinuityContext { UNRELATED_TURNS, PROCESS_RESTART, SCOPE_RESTORE }

    @Serializable
    data class CacheArtifact(
        val header: ArtifactHeader,
        val providerMetricAvailability: CompanionQualityMetrics.ProviderMetricAvailability,
        val localByteIdenticalPrefixRate: Double,
        val turns: List<CacheTurn>,
    )

    @Serializable
    data class CacheTurn(
        val turnId: String,
        val inputTokens: Long,
        val cachedInputTokens: Long?,
        val warm: Boolean,
        val localPrefixByteIdentical: Boolean,
        val sealedChunksByteStable: Boolean,
        val appendOnly: Boolean,
        val epochMiss: Boolean,
        val prefixReuseRestored: Boolean,
    )

    @Serializable
    data class BioArtifact(
        val header: ArtifactHeader,
        val turns: List<BioTurn>,
        val paths: List<BioPath>,
        val invariants: Map<BioInvariant, Boolean>,
    )

    @Serializable
    data class BioTurn(
        val turnId: String,
        val ordinary: Boolean,
        val authoritativeHighIntensity: Boolean,
        val neutral: Boolean,
        val snapshot: Map<CompanionQualityMetrics.BioDimension, Double>,
        val effectiveDelta: Map<CompanionQualityMetrics.BioDimension, Double>,
    )

    @Serializable
    data class BioPath(
        val dimension: CompanionQualityMetrics.BioDimension,
        val direction: DeltaDirection,
        val relief: Boolean,
    )

    @Serializable
    enum class DeltaDirection { POSITIVE, ZERO, NEGATIVE }

    @Serializable
    enum class BioInvariant { VQ_VAE, HEURISTIC_FALLBACK, DERIVED_D, OMEGA }

    @Serializable
    data class RetrievalArtifact(
        val header: ArtifactHeader,
        val turns: List<RetrievalTurn>,
        val compactionChecks: Map<CompactionField, Boolean>,
    )

    @Serializable
    data class RetrievalTurn(
        val turnId: String,
        val recentAndSealedSourceTurnIds: Set<String>,
        val ragSourceTurnIds: Set<String>,
        val availableUniqueCandidates: Int,
        val finalResultCount: Int,
        val mode: RetrievalMode,
        val modeSemanticsPreserved: Boolean,
    )

    @Serializable
    enum class RetrievalMode { CONGRUENT, MIXED, CONTRAST }

    @Serializable
    enum class CompactionField { PEOPLE, COMMITMENTS, UNRESOLVED_ISSUES, RELATIONSHIP_FACTS, EVENT_ORDER }

    @Serializable
    data class RuntimeTraceArtifact(
        val header: ArtifactHeader,
        val turns: List<RuntimeTraceTurn>,
    )

    @Serializable
    data class RuntimeTraceTurn(
        val turnId: String,
        val virtualClock: Boolean,
        val needlessTimestampChurn: Boolean,
        val heartbeatDeterministic: Boolean,
        val silenceWindowDeterministic: Boolean,
        val memoryTimestampDeterministic: Boolean,
        val nonBlocking: Boolean,
        val responseEmitted: Boolean,
        val preservedCapabilities: Set<CompanionQualityMetrics.RequiredCapability>,
    )

    internal data class RunArtifacts(
        val transcript: TranscriptArtifact,
        val cache: CacheArtifact,
        val bio: BioArtifact,
        val retrieval: RetrievalArtifact,
        val runtimeTrace: RuntimeTraceArtifact,
    )

    @Serializable
    internal data class Task5RelationshipEvent(
        val turnId: String,
        val events: Set<String>,
        val relationshipState: String,
    )

    @Serializable
    internal data class RetrievalExportRecord(
        val turn: RetrievalTurn,
        val compactionChecks: Map<CompactionField, Boolean>,
    )

    @Serializable
    internal data class RuntimeExportRecord(
        val turn: RuntimeTraceTurn,
        val continuity: List<ContinuityCheck>,
        val bioPaths: List<BioPath>,
        val bioInvariants: Map<BioInvariant, Boolean>,
    )

    @Serializable
    internal data class ArtifactDescriptor(
        val kind: RequiredArtifact,
        val fileName: String,
        val sha256: String,
    )

    @Serializable
    internal data class Manifest(
        val schemaVersion: Int,
        val header: ArtifactHeader,
        val signerKeyFingerprint: String,
        val signerPublicKey: String,
        val artifacts: List<ArtifactDescriptor>,
        val signature: String,
    )

    @Serializable
    internal data class UnsignedManifest(
        val schemaVersion: Int,
        val header: ArtifactHeader,
        val signerKeyFingerprint: String,
        val signerPublicKey: String,
        val artifacts: List<ArtifactDescriptor>,
    )

    internal interface AuthenticatedRun {
        val header: ArtifactHeader
        val manifestFingerprint: String
        val signerKeyFingerprint: String
        val scenarioCaseIds: Set<String>
        val artifacts: RunArtifacts
        val authenticationProof: String
    }

    internal data class RunPair(
        val repetition: Int,
        val baseline: AuthenticatedRun,
        val candidate: AuthenticatedRun,
    )

    internal class AuthenticatedABEvidence internal constructor(
        val scenarioFingerprint: String,
        val pairs: List<RunPair>,
        val signerKeyFingerprint: String,
        internal val authenticationProof: String,
    ) {
        val manifestFingerprints: Set<String> = pairs.flatMap { listOf(it.baseline, it.candidate) }
            .map(AuthenticatedRun::manifestFingerprint)
            .toSet()

        fun pairFor(repetition: Int): RunPair? = pairs.singleOrNull { it.repetition == repetition }

        internal fun deriveMetrics(pairwiseWinRate: Double): CompanionQualityMetrics {
            val candidates = pairs.map { it.candidate.artifacts }
            fun <T> measured(value: T, artifactKinds: String) = CompanionQualityMetrics.Measurement(
                value,
                CompanionQualityMetrics.EvidenceReference.authenticated(
                    manifestFingerprints = manifestFingerprints,
                    artifactKinds = artifactKinds.split(',').mapTo(mutableSetOf()) {
                        CompanionQualityMetrics.EvidenceArtifact.valueOf(it)
                    },
                ),
            )

            val candidateTurns = candidates.flatMap { it.transcript.turns }
            val boundaryFalsePositives = candidateTurns.count { it.boundaryGoldenCase && it.classifiedAsBoundary }
            val directRomance = candidateTurns.filter(TranscriptTurn::directRomanceCase)
            val hotRomance = candidateTurns.filter(TranscriptTurn::hotRomanceCase)
            val nonOperational = candidateTurns.filterNot(TranscriptTurn::operationalContext)

            fun continuity(fact: ContinuityFact, context: ContinuityContext): Boolean = candidates.all { run ->
                run.transcript.continuity.filter { it.fact == fact && it.context == context }
                    .singleOrNull()?.preserved == true
            }

            val retrievalTurns = candidates.flatMap { it.retrieval.turns }
            val ragCapacityRegression = pairs.any { pair ->
                val baselineByTurn = pair.baseline.artifacts.retrieval.turns.associateBy(RetrievalTurn::turnId)
                pair.candidate.artifacts.retrieval.turns.any { candidate ->
                    val baseline = baselineByTurn.getValue(candidate.turnId)
                    candidate.availableUniqueCandidates >= baseline.finalResultCount &&
                        candidate.finalResultCount < baseline.finalResultCount
                }
            }
            fun retrievalModePreserved(mode: RetrievalMode): Boolean = candidates.all { run ->
                val matching = run.retrieval.turns.filter { it.mode == mode }
                matching.isNotEmpty() && matching.all(RetrievalTurn::modeSemanticsPreserved)
            }
            fun compactionPreserved(field: CompactionField): Boolean = candidates.all { run ->
                run.retrieval.compactionChecks[field] == true
            }

            val bioTurns = candidates.flatMap { it.bio.turns }
            val neutralDeltas = bioTurns.filter(BioTurn::neutral).flatMap { it.effectiveDelta.values }.map { kotlin.math.abs(it) }
            val paths = candidates.flatMap { it.bio.paths }
            fun dimensions(direction: DeltaDirection): Set<CompanionQualityMetrics.BioDimension> = paths
                .filter { it.direction == direction }
                .map(BioPath::dimension)
                .toSet()
            fun invariantPreserved(invariant: BioInvariant): Boolean = candidates.all { it.bio.invariants[invariant] == true }

            val cacheArtifacts = candidates.map(RunArtifacts::cache)
            val cacheAvailability = cacheArtifacts.map(CacheArtifact::providerMetricAvailability).distinct().single()
            val warmTurns = cacheArtifacts.flatMap { it.turns }.filter(CacheTurn::warm)
            val warmInputTokens = warmTurns.sumOf(CacheTurn::inputTokens)
            val warmCacheReadRate = when (cacheAvailability) {
                CompanionQualityMetrics.ProviderMetricAvailability.REPORTED -> warmInputTokens.takeIf { it > 0L }?.let {
                    warmTurns.sumOf { turn -> turn.cachedInputTokens ?: 0L }.toDouble() / it
                }
                CompanionQualityMetrics.ProviderMetricAvailability.UNOBSERVABLE -> null
            }
            val epochMissCounts = cacheArtifacts.map { cache -> cache.turns.count(CacheTurn::epochMiss) }
            val recoveryTurns = cacheArtifacts.map(::compactionRecoveryTurns)

            val runtimeTurns = candidates.flatMap { it.runtimeTrace.turns }
            val preservedCapabilities = runtimeTurns.map(RuntimeTraceTurn::preservedCapabilities)
                .reduce { preserved, current -> preserved intersect current }

            return CompanionQualityMetrics(
                relationship = CompanionQualityMetrics.Relationship(
                    boundaryFalsePositives = measured(boundaryFalsePositives, "TRANSCRIPT"),
                    confessionAcceptanceContinuityThroughUnrelatedTurns = measured(continuity(ContinuityFact.CONFESSION_ACCEPTANCE, ContinuityContext.UNRELATED_TURNS), "TRANSCRIPT"),
                    confessionAcceptanceContinuityThroughRestart = measured(continuity(ContinuityFact.CONFESSION_ACCEPTANCE, ContinuityContext.PROCESS_RESTART), "TRANSCRIPT"),
                    confessionAcceptanceContinuityThroughScopeRestore = measured(continuity(ContinuityFact.CONFESSION_ACCEPTANCE, ContinuityContext.SCOPE_RESTORE), "TRANSCRIPT"),
                    coupleContinuityThroughUnrelatedTurns = measured(continuity(ContinuityFact.COUPLE_STATUS, ContinuityContext.UNRELATED_TURNS), "TRANSCRIPT"),
                    coupleContinuityThroughRestart = measured(continuity(ContinuityFact.COUPLE_STATUS, ContinuityContext.PROCESS_RESTART), "TRANSCRIPT"),
                    coupleContinuityThroughScopeRestore = measured(continuity(ContinuityFact.COUPLE_STATUS, ContinuityContext.SCOPE_RESTORE), "TRANSCRIPT"),
                    romanticReciprocityRate = measured(ratio(directRomance.count(TranscriptTurn::reciprocalRomance), directRomance.size), "TRANSCRIPT"),
                    hotRomanceReciprocityRate = measured(ratio(hotRomance.count(TranscriptTurn::reciprocalHotRomance), hotRomance.size), "TRANSCRIPT"),
                    proceduralReplyRate = measured(ratio(nonOperational.count(TranscriptTurn::proceduralReply), nonOperational.size), "TRANSCRIPT"),
                    pairwiseWinRate = measured(pairwiseWinRate, "PAIRWISE_DECISIONS"),
                    factualRegression = measured(candidateTurns.any(TranscriptTurn::factualRegression), "TRANSCRIPT"),
                ),
                memory = CompanionQualityMetrics.MemoryContext(
                    sourceTurnLineageOverlap = measured(retrievalTurns.sumOf { (it.recentAndSealedSourceTurnIds intersect it.ragSourceTurnIds).size }, "RETRIEVAL_TRACE"),
                    ragUniqueCandidateCapacityRegression = measured(ragCapacityRegression, "RETRIEVAL_TRACE"),
                    mixedRetrievalSemanticsPreserved = measured(retrievalModePreserved(RetrievalMode.MIXED), "RETRIEVAL_TRACE"),
                    contrastRetrievalSemanticsPreserved = measured(retrievalModePreserved(RetrievalMode.CONTRAST), "RETRIEVAL_TRACE"),
                    compactionPeopleFidelity = measured(compactionPreserved(CompactionField.PEOPLE), "RETRIEVAL_TRACE"),
                    compactionCommitmentFidelity = measured(compactionPreserved(CompactionField.COMMITMENTS), "RETRIEVAL_TRACE"),
                    compactionUnresolvedIssueFidelity = measured(compactionPreserved(CompactionField.UNRESOLVED_ISSUES), "RETRIEVAL_TRACE"),
                    compactionRelationshipFactFidelity = measured(compactionPreserved(CompactionField.RELATIONSHIP_FACTS), "RETRIEVAL_TRACE"),
                    compactionEventOrderFidelity = measured(compactionPreserved(CompactionField.EVENT_ORDER), "RETRIEVAL_TRACE"),
                ),
                bio = CompanionQualityMetrics.Bio(
                    neutralMedianAbsEffectiveDelta = measured(median(neutralDeltas), "BIO_SNAPSHOTS"),
                    saturationViolations = measured(candidates.sumOf { saturationViolations(it.bio.turns) }, "BIO_SNAPSHOTS"),
                    positivePathDimensions = measured(dimensions(DeltaDirection.POSITIVE), "BIO_SNAPSHOTS"),
                    zeroPathDimensions = measured(dimensions(DeltaDirection.ZERO), "BIO_SNAPSHOTS"),
                    negativePathDimensions = measured(dimensions(DeltaDirection.NEGATIVE), "BIO_SNAPSHOTS"),
                    reliefPathDimensions = measured(paths.filter(BioPath::relief).map(BioPath::dimension).toSet(), "BIO_SNAPSHOTS"),
                    vqVaeRegression = measured(!invariantPreserved(BioInvariant.VQ_VAE), "BIO_SNAPSHOTS"),
                    heuristicFallbackRegression = measured(!invariantPreserved(BioInvariant.HEURISTIC_FALLBACK), "BIO_SNAPSHOTS"),
                    derivedDRegression = measured(!invariantPreserved(BioInvariant.DERIVED_D), "BIO_SNAPSHOTS"),
                    omegaRegression = measured(!invariantPreserved(BioInvariant.OMEGA), "BIO_SNAPSHOTS"),
                ),
                cache = CompanionQualityMetrics.Cache(
                    providerMetricAvailability = cacheAvailability,
                    warmCacheReadRate = CompanionQualityMetrics.Measurement(
                        warmCacheReadRate,
                        CompanionQualityMetrics.EvidenceReference.authenticated(
                            manifestFingerprints,
                            setOf(CompanionQualityMetrics.EvidenceArtifact.CACHE_MANIFEST),
                        ),
                    ),
                    localByteIdenticalPrefixRate = measured(cacheArtifacts.map(CacheArtifact::localByteIdenticalPrefixRate).average(), "CACHE_MANIFEST"),
                    sealedChunksByteStable = measured(cacheArtifacts.flatMap { it.turns }.all(CacheTurn::sealedChunksByteStable), "CACHE_MANIFEST"),
                    ordinaryTurnsAppendOnly = measured(cacheArtifacts.flatMap { it.turns }.all(CacheTurn::appendOnly), "CACHE_MANIFEST"),
                    compactionEpochMissCount = measured(epochMissCounts.distinct().singleOrNull() ?: -1, "CACHE_MANIFEST"),
                    compactionRecoveryTurns = measured(recoveryTurns.maxOrNull() ?: Int.MAX_VALUE, "CACHE_MANIFEST"),
                    preservedCapabilities = measured(preservedCapabilities, "RUNTIME_TRACE"),
                ),
                temporalRuntime = CompanionQualityMetrics.TemporalRuntime(
                    allTimeSourcesVirtual = measured(runtimeTurns.all(RuntimeTraceTurn::virtualClock), "RUNTIME_TRACE"),
                    needlessTimestampChurnCount = measured(runtimeTurns.count(RuntimeTraceTurn::needlessTimestampChurn), "RUNTIME_TRACE"),
                    heartbeatDeterministic = measured(runtimeTurns.all(RuntimeTraceTurn::heartbeatDeterministic), "RUNTIME_TRACE"),
                    silenceWindowDeterministic = measured(runtimeTurns.all(RuntimeTraceTurn::silenceWindowDeterministic), "RUNTIME_TRACE"),
                    memoryTimestampDeterministic = measured(runtimeTurns.all(RuntimeTraceTurn::memoryTimestampDeterministic), "RUNTIME_TRACE"),
                    nonBlockingRuntime = measured(runtimeTurns.all(RuntimeTraceTurn::nonBlocking), "RUNTIME_TRACE"),
                    silentResponseFailures = measured(runtimeTurns.count { !it.responseEmitted }, "RUNTIME_TRACE"),
                ),
            )
        }
    }

    @JvmSynthetic
    internal suspend fun loadTask5ABExports(
        paths: Task5ABExportPaths,
    ): AuthenticatedABEvidence = withContext(Dispatchers.IO) {
        val baselineRuns = paths.baseline.map { loadTask5RunBlocking(it) }
        val candidateRuns = paths.candidate.map { loadTask5RunBlocking(it) }
        bindLoadedRuns(baselineRuns, candidateRuns)
    }

    private fun bindLoadedRuns(
        baselineRuns: List<AuthenticatedRun>,
        candidateRuns: List<AuthenticatedRun>,
    ): AuthenticatedABEvidence {
        require(baselineRuns.isNotEmpty() && candidateRuns.isNotEmpty()) { "A/B evidence must contain both variants" }
        require((baselineRuns + candidateRuns).all(::isAuthenticated)) { "A/B evidence contains an unauthenticated run" }
        val baseline = baselineRuns.associateBy { it.header.repetition }
        val candidate = candidateRuns.associateBy { it.header.repetition }
        require(baseline.size == baselineRuns.size && candidate.size == candidateRuns.size) { "A/B repetitions must be unique" }
        require(baseline.keys == candidate.keys && baseline.keys == (1..baseline.size).toSet()) {
            "A/B repetitions must be paired and contiguous"
        }
        require(baseline.values.all { it.header.variant == EvaluationVariant.A }) { "Baseline evidence must be variant A" }
        require(candidate.values.all { it.header.variant == EvaluationVariant.B }) { "Candidate evidence must be variant B" }
        val scenarios = (baselineRuns + candidateRuns).map { it.header.scenarioFingerprint }.distinct()
        require(scenarios.size == 1) { "A/B evidence must share one scenario fingerprint" }
        val signerKeyFingerprints = (baselineRuns + candidateRuns).map(AuthenticatedRun::signerKeyFingerprint).distinct()
        require(signerKeyFingerprints.size == 1) { "A/B evidence must share one trusted signer" }
        require(candidate.values.map { it.artifacts.cache.providerMetricAvailability }.distinct().size == 1) {
            "Candidate provider cache availability must be consistent"
        }
        val fingerprints = (baselineRuns + candidateRuns).map(AuthenticatedRun::manifestFingerprint)
        require(fingerprints.distinct().size == fingerprints.size) { "Every A/B run must have a unique manifest" }
        val scenarioFingerprint = scenarios.single()
        val pairs = baseline.keys.sorted().map { repetition -> RunPair(repetition, baseline.getValue(repetition), candidate.getValue(repetition)) }
        return AuthenticatedABEvidence(
            scenarioFingerprint = scenarioFingerprint,
            pairs = pairs,
            signerKeyFingerprint = signerKeyFingerprints.single(),
            authenticationProof = abProof(scenarioFingerprint, signerKeyFingerprints.single(), pairs),
        )
    }

    private fun loadTask5RunBlocking(
        paths: Task5RunExportPaths,
    ): AuthenticatedRun {
        val manifestPath = paths.manifest.toAbsolutePath().normalize()
        require(manifestPath.fileName.toString() == MANIFEST_FILE) { "Unexpected evaluation manifest path" }
        require(Files.isRegularFile(manifestPath)) { "Missing authenticated evaluation manifest" }
        val root = manifestPath.parent
        val manifestBytes = Files.readAllBytes(manifestPath)
        val manifest = runCatching { json.decodeFromString<Manifest>(manifestBytes.toString(StandardCharsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("Malformed evaluation manifest", it) }
        require(manifest.schemaVersion == SCHEMA_VERSION) { "Unsupported evaluation manifest schema" }
        val unsignedManifest = UnsignedManifest(
            manifest.schemaVersion,
            manifest.header,
            manifest.signerKeyFingerprint,
            manifest.signerPublicKey,
            manifest.artifacts,
        )
        FrozenTask5TrustHolder.registry.verify(
            manifest.signerKeyFingerprint,
            manifest.signerPublicKey,
            manifest.signature,
            json.encodeToString(unsignedManifest),
        )
        require(manifest.artifacts.map(ArtifactDescriptor::kind).toSet() == RequiredArtifact.entries.toSet()) {
            "Evaluation manifest must contain every required artifact"
        }
        require(manifest.artifacts.distinctBy(ArtifactDescriptor::kind).size == RequiredArtifact.entries.size) {
            "Evaluation manifest contains duplicate artifacts"
        }
        val content = manifest.artifacts.associate { descriptor ->
            require(descriptor.fileName == descriptor.kind.fileName) { "Unexpected artifact file name" }
            val artifactPath = paths.pathFor(descriptor.kind).toAbsolutePath().normalize()
            val expectedPath = root.resolve(descriptor.kind.fileName).normalize()
            require(artifactPath == expectedPath && artifactPath.parent == root) { "Explicit artifact path does not match its manifest" }
            require(Files.isRegularFile(artifactPath)) { "Missing evaluation artifact ${descriptor.kind}" }
            val bytes = Files.readAllBytes(artifactPath)
            require(sha256(bytes) == descriptor.sha256) { "Evaluation artifact hash mismatch for ${descriptor.kind}" }
            descriptor.kind to bytes
        }
        val artifacts = try {
            parseTask5Artifacts(manifest.header, content)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Malformed typed Task 5 evaluation artifact", failure)
        }
        validateArtifacts(artifacts)
        val manifestFingerprint = sha256(manifestBytes)
        return LoadedAuthenticatedRun(
            header = manifest.header,
            artifacts = artifacts,
            manifestFingerprint = manifestFingerprint,
            signerKeyFingerprint = manifest.signerKeyFingerprint,
            authenticationProof = runProof(manifest.header, artifacts, manifestFingerprint, manifest.signerKeyFingerprint),
        )
    }

    private fun parseTask5Artifacts(
        header: ArtifactHeader,
        content: Map<RequiredArtifact, ByteArray>,
    ): RunArtifacts {
        val transcriptTurns = decodeJsonLines<TranscriptTurn>(content.getValue(RequiredArtifact.TRANSCRIPT))
        val cacheTurns = decodeJsonLines<CacheTurn>(content.getValue(RequiredArtifact.CACHE_MANIFEST))
        val bioTurns = decodeBioCsv(content.getValue(RequiredArtifact.BIO_SNAPSHOTS))
        val relationshipEvents = decodeJsonLines<Task5RelationshipEvent>(content.getValue(RequiredArtifact.RELATIONSHIP_EVENTS))
        val retrievalRecords = decodeJsonLines<RetrievalExportRecord>(content.getValue(RequiredArtifact.RETRIEVAL_TRACE))
        val runtimeRecords = decodeJsonLines<RuntimeExportRecord>(content.getValue(RequiredArtifact.RUNTIME_TRACE))
        require(content.getValue(RequiredArtifact.EVALUATION_REPORT).toString(StandardCharsets.UTF_8).isNotBlank()) {
            "Task 5 evaluation report is empty"
        }
        require(retrievalRecords.isNotEmpty() && runtimeRecords.isNotEmpty()) { "Runtime evidence is missing" }
        val compactionChecks = retrievalRecords.map(RetrievalExportRecord::compactionChecks).distinct().single()
        val continuity = runtimeRecords.map(RuntimeExportRecord::continuity).distinct().single()
        val bioPaths = runtimeRecords.map(RuntimeExportRecord::bioPaths).distinct().single()
        val bioInvariants = runtimeRecords.map(RuntimeExportRecord::bioInvariants).distinct().single()
        val transcriptTurnIds = transcriptTurns.map(TranscriptTurn::turnId)
        require(relationshipEvents.map(Task5RelationshipEvent::turnId) == transcriptTurnIds) {
            "Relationship event lineage must exactly match the transcript"
        }
        require(relationshipEvents.all { it.relationshipState.isNotBlank() }) { "Relationship event state is missing" }
        val providerMetricAvailability = when {
            cacheTurns.all { it.cachedInputTokens != null } -> CompanionQualityMetrics.ProviderMetricAvailability.REPORTED
            cacheTurns.all { it.cachedInputTokens == null } -> CompanionQualityMetrics.ProviderMetricAvailability.UNOBSERVABLE
            else -> throw IllegalArgumentException("Provider cache evidence mixes reported and unobservable turns")
        }
        val localPrefixRate = cacheTurns.count(CacheTurn::localPrefixByteIdentical).toDouble() / cacheTurns.size
        return RunArtifacts(
            transcript = TranscriptArtifact(header, transcriptTurns, continuity),
            cache = CacheArtifact(header, providerMetricAvailability, localPrefixRate, cacheTurns),
            bio = BioArtifact(header, bioTurns, bioPaths, bioInvariants),
            retrieval = RetrievalArtifact(header, retrievalRecords.map(RetrievalExportRecord::turn), compactionChecks),
            runtimeTrace = RuntimeTraceArtifact(header, runtimeRecords.map(RuntimeExportRecord::turn)),
        )
    }

    private fun validateArtifacts(artifacts: RunArtifacts) {
        val headers = listOf(
            artifacts.transcript.header,
            artifacts.cache.header,
            artifacts.bio.header,
            artifacts.retrieval.header,
            artifacts.runtimeTrace.header,
        )
        require(headers.distinct().size == 1) { "All evaluation artifacts must share identical provenance" }
        val header = headers.first()
        require(header.runId.isNotBlank() && header.scenarioFingerprint.isNotBlank() && header.repetition > 0) {
            "Evaluation artifact provenance is incomplete"
        }
        val transcriptTurnIds = artifacts.transcript.turns.map(TranscriptTurn::turnId)
        require(transcriptTurnIds.isNotEmpty() && transcriptTurnIds.distinct().size == transcriptTurnIds.size) {
            "Transcript turn lineage must be non-empty and unique"
        }
        require(artifacts.transcript.turns.all { it.scenarioCaseId.isNotBlank() }) { "Transcript scenario cases must be identified" }
        listOf(
            artifacts.cache.turns.map(CacheTurn::turnId),
            artifacts.bio.turns.map(BioTurn::turnId),
            artifacts.retrieval.turns.map(RetrievalTurn::turnId),
            artifacts.runtimeTrace.turns.map(RuntimeTraceTurn::turnId),
        ).forEach { turnIds ->
            require(turnIds == transcriptTurnIds) { "Artifact turn lineage must exactly match the transcript" }
        }
        require(artifacts.cache.localByteIdenticalPrefixRate in 0.0..1.0) { "Invalid local prefix rate" }
        artifacts.cache.turns.forEach { turn ->
            require(turn.inputTokens >= 0L && (turn.cachedInputTokens == null || turn.cachedInputTokens in 0L..turn.inputTokens)) {
                "Invalid provider cache token evidence"
            }
        }
        when (artifacts.cache.providerMetricAvailability) {
            CompanionQualityMetrics.ProviderMetricAvailability.REPORTED -> require(artifacts.cache.turns.all { it.cachedInputTokens != null }) {
                "Reported provider cache evidence requires cached token counts"
            }
            CompanionQualityMetrics.ProviderMetricAvailability.UNOBSERVABLE -> require(artifacts.cache.turns.all { it.cachedInputTokens == null }) {
                "Unobservable provider cache evidence cannot contain provider token counts"
            }
        }
        val dimensions = CompanionQualityMetrics.BioDimension.entries.toSet()
        artifacts.bio.turns.forEach { turn ->
            require(turn.snapshot.keys == dimensions && turn.effectiveDelta.keys == dimensions) { "Bio evidence must contain exactly eight dimensions" }
            require(turn.snapshot.values.all { it.isFinite() && it in 0.0..1.0 } && turn.effectiveDelta.values.all(Double::isFinite)) {
                "Bio evidence contains invalid coordinates"
            }
        }
        artifacts.retrieval.turns.forEach { turn ->
            require(turn.availableUniqueCandidates >= 0 && turn.finalResultCount in 0..turn.availableUniqueCandidates) {
                "Retrieval capacity evidence is invalid"
            }
        }
        require(artifacts.runtimeTrace.turns.all { it.preservedCapabilities.isNotEmpty() }) {
            "Runtime capability evidence is missing"
        }
    }

    private fun compactionRecoveryTurns(cache: CacheArtifact): Int {
        val miss = cache.turns.indexOfFirst(CacheTurn::epochMiss)
        if (miss < 0) return Int.MAX_VALUE
        val restoredAfterMiss = cache.turns.drop(miss + 1).indexOfFirst(CacheTurn::prefixReuseRestored)
        return if (restoredAfterMiss < 0) Int.MAX_VALUE else restoredAfterMiss + 1
    }

    private fun saturationViolations(turns: List<BioTurn>): Int = CompanionQualityMetrics.BioDimension.entries.sumOf { dimension ->
        var streak = 0
        var violations = 0
        turns.forEach { turn ->
            streak = if (turn.ordinary && !turn.authoritativeHighIntensity && turn.snapshot.getValue(dimension) >= 0.99) streak + 1 else 0
            if (streak == 10) violations++
        }
        violations
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val midpoint = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[midpoint] else (sorted[midpoint - 1] + sorted[midpoint]) / 2.0
    }

    private fun ratio(numerator: Int, denominator: Int): Double = if (denominator == 0) Double.NaN else numerator.toDouble() / denominator

    private inline fun <reified T> decodeJsonLines(bytes: ByteArray): List<T> =
        bytes.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).map { line ->
            json.decodeFromString<T>(line)
        }.toList()

    private fun decodeBioCsv(bytes: ByteArray): List<BioTurn> {
        val rows = bytes.toString(StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).map(::parseCsvRow).toList()
        require(rows.isNotEmpty()) { "Bio CSV is empty" }
        val dimensions = CompanionQualityMetrics.BioDimension.entries
        val expectedHeader = listOf("turn_id", "ordinary", "authoritative_high_intensity", "neutral") +
            dimensions.map(::bioColumn) + dimensions.map { "delta_${bioColumn(it)}" }
        require(rows.first() == expectedHeader) { "Unexpected Bio CSV schema" }
        return rows.drop(1).map { values ->
            require(values.size == expectedHeader.size) { "Malformed Bio CSV row" }
            val row = expectedHeader.zip(values).toMap()
            BioTurn(
                turnId = row.getValue("turn_id"),
                ordinary = row.getValue("ordinary").toBooleanStrict(),
                authoritativeHighIntensity = row.getValue("authoritative_high_intensity").toBooleanStrict(),
                neutral = row.getValue("neutral").toBooleanStrict(),
                snapshot = dimensions.associateWith { row.getValue(bioColumn(it)).toDouble() },
                effectiveDelta = dimensions.associateWith { row.getValue("delta_${bioColumn(it)}").toDouble() },
            )
        }
    }

    private fun parseCsvRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < row.length) {
            when (val character = row[index]) {
                '"' -> if (quoted && row.getOrNull(index + 1) == '"') {
                    field.append(character)
                    index++
                } else {
                    quoted = !quoted
                }
                ',' -> if (quoted) field.append(character) else {
                    fields += field.toString()
                    field.clear()
                }
                else -> field.append(character)
            }
            index++
        }
        require(!quoted) { "Unterminated quoted Bio CSV field" }
        fields += field.toString()
        return fields
    }

    private fun bioColumn(dimension: CompanionQualityMetrics.BioDimension): String =
        if (dimension == CompanionQualityMetrics.BioDimension.TAU) "tau" else dimension.name

    private fun hmac(key: ByteArray, value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun isAuthenticated(run: AuthenticatedRun): Boolean =
        run.authenticationProof == runProof(run.header, run.artifacts, run.manifestFingerprint, run.signerKeyFingerprint)

    private fun runProof(
        header: ArtifactHeader,
        artifacts: RunArtifacts,
        manifestFingerprint: String,
        signerKeyFingerprint: String,
    ): String = hmac(
        authenticationSecret,
        buildString {
            append(json.encodeToString(header)).append('|').append(manifestFingerprint).append('|').append(signerKeyFingerprint)
            append('|').append(sha256(json.encodeToString(artifacts.transcript).toByteArray(StandardCharsets.UTF_8)))
            append('|').append(sha256(json.encodeToString(artifacts.cache).toByteArray(StandardCharsets.UTF_8)))
            append('|').append(sha256(json.encodeToString(artifacts.bio).toByteArray(StandardCharsets.UTF_8)))
            append('|').append(sha256(json.encodeToString(artifacts.retrieval).toByteArray(StandardCharsets.UTF_8)))
            append('|').append(sha256(json.encodeToString(artifacts.runtimeTrace).toByteArray(StandardCharsets.UTF_8)))
        },
    )

    private fun abProof(scenarioFingerprint: String, signerKeyFingerprint: String, pairs: List<RunPair>): String = hmac(
        authenticationSecret,
        buildString {
            append(scenarioFingerprint).append('|').append(signerKeyFingerprint)
            pairs.sortedBy(RunPair::repetition).forEach { pair ->
                append('|').append(pair.repetition)
                append('|').append(pair.baseline.manifestFingerprint).append(':').append(pair.baseline.authenticationProof)
                append('|').append(pair.candidate.manifestFingerprint).append(':').append(pair.candidate.authenticationProof)
            }
        },
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class LoadedAuthenticatedRun(
        override val header: ArtifactHeader,
        override val artifacts: RunArtifacts,
        override val manifestFingerprint: String,
        override val signerKeyFingerprint: String,
        override val authenticationProof: String,
    ) : AuthenticatedRun {
        override val scenarioCaseIds: Set<String> = artifacts.transcript.turns.map(TranscriptTurn::scenarioCaseId).toSet()
    }

    internal const val SCHEMA_VERSION = 2
    private const val MANIFEST_FILE = "evaluation-manifest.json"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private val authenticationSecret = ByteArray(32).also(SecureRandom()::nextBytes)
    internal val json = Json { encodeDefaults = true }
}

private object FrozenTask5TrustHolder {
    val registry: FrozenTask5Trust = FrozenTask5Trust.fromDeploymentEnvironment()
}

private class FrozenTask5Trust private constructor(
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

        fun fromDeploymentEnvironment(): FrozenTask5Trust {
            val configured = System.getenv(TRUSTED_SIGNERS_ENV)
            require(!configured.isNullOrBlank()) { "No trusted production evaluation signer fingerprints are configured" }
            val values = configured.split(',', ';').map(String::trim).filter(String::isNotEmpty).map(String::lowercase).toSet()
            require(values.isNotEmpty() && values.all { it.matches(Regex("[0-9a-f]{64}")) }) {
                "Trusted signer fingerprints must be SHA-256 values"
            }
            return FrozenTask5Trust(values)
        }
    }
}
