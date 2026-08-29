package io.openeden.server.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Generates file fixtures only; production APIs cannot accept its assembled records or signing keys. */
internal object TestOnlyTask5ExportFixture {
    enum class Signer { TRUSTED, SELF_ISSUED }

    enum class Profile {
        PASSING,
        BOUNDARY_REGRESSION,
        MALFORMED_RUNTIME,
        PARTIAL_LOCAL_PREFIX,
        FORGED_PAIRWISE_FINGERPRINT,
        FORGED_PAIRWISE_SLOT,
    }

    suspend fun exportInput(
        root: Path,
        repetitions: Int = 3,
        scenarioFingerprint: String = "scenario-sha256",
        signer: Signer = Signer.TRUSTED,
        pairwiseSigner: Signer = signer,
        profile: Profile = Profile.PASSING,
    ): ProductionEvaluationInputPaths = TestOnlyTask5ExportWriter.exportInput(
        root,
        repetitions,
        scenarioFingerprint,
        signer,
        pairwiseSigner,
        profile,
    )
}

private typealias FixtureSigner = TestOnlyTask5ExportFixture.Signer
private typealias FixtureProfile = TestOnlyTask5ExportFixture.Profile

private object TestOnlyTask5ExportWriter {

    private val trustedKeyPair = KeyFactory.getInstance(SIGNATURE_ALGORITHM).let { factory ->
        KeyPair(
            factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PRECONFIGURED_PUBLIC_KEY))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRECONFIGURED_PRIVATE_KEY))),
        )
    }
    private val selfIssuedKeyPair = KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM).generateKeyPair()

    suspend fun exportInput(
        root: Path,
        repetitions: Int = 3,
        scenarioFingerprint: String = "scenario-sha256",
        signer: FixtureSigner = FixtureSigner.TRUSTED,
        pairwiseSigner: FixtureSigner = signer,
        profile: FixtureProfile = FixtureProfile.PASSING,
    ): ProductionEvaluationInputPaths = withContext(Dispatchers.IO) {
        val keyPair = keyPair(signer)
        val baseline = (1..repetitions).map { repetition ->
            exportRunBlocking(root.resolve("baseline-$repetition"), EvaluationVariant.A, repetition, scenarioFingerprint, keyPair, FixtureProfile.PASSING)
        }
        val candidate = (1..repetitions).map { repetition ->
            val runProfile = when (profile) {
                FixtureProfile.PARTIAL_LOCAL_PREFIX -> profile
                else -> if (repetition == 1) profile else FixtureProfile.PASSING
            }
            exportRunBlocking(root.resolve("candidate-$repetition"), EvaluationVariant.B, repetition, scenarioFingerprint, keyPair, runProfile)
        }
        val pairwise = exportPairwiseBlocking(
            directory = root.resolve("pairwise"),
            baseline = baseline,
            candidate = candidate,
            scenarioFingerprint = scenarioFingerprint,
            keyPair = keyPair(pairwiseSigner),
            profile = profile,
        )
        ProductionEvaluationInputPaths(baseline, candidate, listOf(pairwise))
    }

    private fun keyPair(signer: FixtureSigner): KeyPair = when (signer) {
        FixtureSigner.TRUSTED -> trustedKeyPair
        FixtureSigner.SELF_ISSUED -> selfIssuedKeyPair
    }

    private fun exportRunBlocking(
        directory: Path,
        variant: EvaluationVariant,
        repetition: Int,
        scenarioFingerprint: String,
        keyPair: KeyPair,
        profile: FixtureProfile,
    ): ProductionEvaluationEvidence.Task5RunExportPaths {
        Files.createDirectories(directory)
        val artifacts = artifacts(variant, repetition, scenarioFingerprint, profile)
        val json = ProductionEvaluationEvidence.json
        val files = linkedMapOf(
            ProductionEvaluationEvidence.RequiredArtifact.TRANSCRIPT to artifacts.transcript.turns.joinToString("\n") { json.encodeToString(it) } + "\n",
            ProductionEvaluationEvidence.RequiredArtifact.BIO_SNAPSHOTS to encodeBioCsv(artifacts.bio.turns),
            ProductionEvaluationEvidence.RequiredArtifact.RELATIONSHIP_EVENTS to artifacts.transcript.turns.joinToString("\n") { turn ->
                json.encodeToString(ProductionEvaluationEvidence.Task5RelationshipEvent(turn.turnId, emptySet(), "OBSERVED"))
            } + "\n",
            ProductionEvaluationEvidence.RequiredArtifact.CACHE_MANIFEST to artifacts.cache.turns.joinToString("\n") { json.encodeToString(it) } + "\n",
            ProductionEvaluationEvidence.RequiredArtifact.EVALUATION_REPORT to "# Task 5 Evaluation Export\n\nRuntime observations are manifest-bound.\n",
            ProductionEvaluationEvidence.RequiredArtifact.RETRIEVAL_TRACE to artifacts.retrieval.turns.joinToString("\n") { turn ->
                json.encodeToString(ProductionEvaluationEvidence.RetrievalExportRecord(turn, artifacts.retrieval.compactionChecks))
            } + "\n",
            ProductionEvaluationEvidence.RequiredArtifact.RUNTIME_TRACE to if (profile == FixtureProfile.MALFORMED_RUNTIME) {
                "malformed runtime evidence\n"
            } else {
                artifacts.runtimeTrace.turns.joinToString("\n") { turn ->
                    json.encodeToString(
                        ProductionEvaluationEvidence.RuntimeExportRecord(
                            turn = turn,
                            continuity = artifacts.transcript.continuity,
                            bioPaths = artifacts.bio.paths,
                            bioInvariants = artifacts.bio.invariants,
                        ),
                    )
                } + "\n"
            },
        )
        files.forEach { (kind, content) ->
            Files.writeString(directory.resolve(kind.fileName), content, StandardCharsets.UTF_8)
        }
        val publicKey = keyPair.public.encoded
        val publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey)
        val fingerprint = sha256(publicKey)
        val descriptors = ProductionEvaluationEvidence.RequiredArtifact.entries.map { kind ->
            ProductionEvaluationEvidence.ArtifactDescriptor(
                kind = kind,
                fileName = kind.fileName,
                sha256 = sha256(Files.readAllBytes(directory.resolve(kind.fileName))),
            )
        }
        val unsigned = ProductionEvaluationEvidence.UnsignedManifest(
            schemaVersion = ProductionEvaluationEvidence.SCHEMA_VERSION,
            header = artifacts.transcript.header,
            signerKeyFingerprint = fingerprint,
            signerPublicKey = publicKeyBase64,
            artifacts = descriptors,
        )
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(json.encodeToString(unsigned).toByteArray(StandardCharsets.UTF_8))
        }.sign()
        val manifest = ProductionEvaluationEvidence.Manifest(
            schemaVersion = unsigned.schemaVersion,
            header = unsigned.header,
            signerKeyFingerprint = unsigned.signerKeyFingerprint,
            signerPublicKey = unsigned.signerPublicKey,
            artifacts = unsigned.artifacts,
            signature = Base64.getEncoder().encodeToString(signature),
        )
        val manifestPath = directory.resolve("evaluation-manifest.json")
        Files.writeString(manifestPath, json.encodeToString(manifest), StandardCharsets.UTF_8)
        return ProductionEvaluationEvidence.Task5RunExportPaths(
            manifest = manifestPath,
            transcript = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.TRANSCRIPT.fileName),
            bioSnapshots = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.BIO_SNAPSHOTS.fileName),
            relationshipEvents = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.RELATIONSHIP_EVENTS.fileName),
            cacheManifest = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.CACHE_MANIFEST.fileName),
            evaluationReport = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.EVALUATION_REPORT.fileName),
            retrievalTrace = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.RETRIEVAL_TRACE.fileName),
            runtimeTrace = directory.resolve(ProductionEvaluationEvidence.RequiredArtifact.RUNTIME_TRACE.fileName),
        )
    }

    private fun exportPairwiseBlocking(
        directory: Path,
        baseline: List<ProductionEvaluationEvidence.Task5RunExportPaths>,
        candidate: List<ProductionEvaluationEvidence.Task5RunExportPaths>,
        scenarioFingerprint: String,
        keyPair: KeyPair,
        profile: FixtureProfile,
    ): PairwiseDecisionArtifactPaths {
        Files.createDirectories(directory)
        val baselineFingerprints = baseline.associate { run ->
            manifestRepetition(run.manifest) to sha256(Files.readAllBytes(run.manifest))
        }
        val candidateFingerprints = candidate.associate { run ->
            manifestRepetition(run.manifest) to sha256(Files.readAllBytes(run.manifest))
        }
        var evaluation = PairwiseEvaluation(
            metadata = PairwiseEvaluation.Metadata(
                evaluatorVersion = "judge-v1",
                evaluatorModel = "evaluator-model",
                scenarioFingerprint = scenarioFingerprint,
                providerSeedControl = PairwiseEvaluation.ProviderSeedControl.UNAVAILABLE,
                blindProtocolVersion = "blind-left-right-v1",
            ),
            decisions = baselineFingerprints.keys.sorted().mapIndexed { index, repetition ->
                val candidateSlot = if (index % 2 == 0) PairwiseEvaluation.ArtifactSlot.LEFT else PairwiseEvaluation.ArtifactSlot.RIGHT
                val winner = candidateSlot.toWinner()
                PairwiseEvaluation.Decision(
                    decisionId = "decision-$repetition",
                    scenarioCaseId = "case-$repetition",
                    candidateRepetition = repetition,
                    leftArtifactFingerprint = if (candidateSlot == PairwiseEvaluation.ArtifactSlot.LEFT) {
                        candidateFingerprints.getValue(repetition)
                    } else {
                        baselineFingerprints.getValue(repetition)
                    },
                    rightArtifactFingerprint = if (candidateSlot == PairwiseEvaluation.ArtifactSlot.RIGHT) {
                        candidateFingerprints.getValue(repetition)
                    } else {
                        baselineFingerprints.getValue(repetition)
                    },
                    candidateSlot = candidateSlot,
                    winner = winner,
                    dimensionWinners = PairwiseEvaluation.JudgeDimension.entries.associateWith { winner },
                    factualRegressionObserved = false,
                    rationale = "Blind evaluator selected the candidate artifact",
                )
            },
        )
        evaluation = when (profile) {
            FixtureProfile.FORGED_PAIRWISE_FINGERPRINT -> evaluation.copy(
                decisions = evaluation.decisions.mapIndexed { index, decision ->
                    if (index == 0) decision.copy(leftArtifactFingerprint = "forged") else decision
                },
            )
            FixtureProfile.FORGED_PAIRWISE_SLOT -> evaluation.copy(
                decisions = evaluation.decisions.mapIndexed { index, decision ->
                    if (index == 0) decision.copy(candidateSlot = PairwiseEvaluation.ArtifactSlot.RIGHT) else decision
                },
            )
            else -> evaluation
        }
        val json = ProductionEvaluationEvidence.json
        val decisionsPath = directory.resolve(PAIRWISE_DECISIONS_FILE)
        val decisionBytes = json.encodeToString(evaluation).toByteArray(StandardCharsets.UTF_8)
        Files.write(decisionsPath, decisionBytes)
        val publicKey = keyPair.public.encoded
        val unsigned = UnsignedPairwiseArtifactManifest(
            schemaVersion = PAIRWISE_SCHEMA_VERSION,
            signerKeyFingerprint = sha256(publicKey),
            signerPublicKey = Base64.getEncoder().encodeToString(publicKey),
            decisionsFileName = PAIRWISE_DECISIONS_FILE,
            decisionsSha256 = sha256(decisionBytes),
        )
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(keyPair.private)
            update(json.encodeToString(unsigned).toByteArray(StandardCharsets.UTF_8))
        }.sign()
        val manifest = PairwiseArtifactManifest(
            schemaVersion = unsigned.schemaVersion,
            signerKeyFingerprint = unsigned.signerKeyFingerprint,
            signerPublicKey = unsigned.signerPublicKey,
            decisionsFileName = unsigned.decisionsFileName,
            decisionsSha256 = unsigned.decisionsSha256,
            signature = Base64.getEncoder().encodeToString(signature),
        )
        val manifestPath = directory.resolve(PAIRWISE_MANIFEST_FILE)
        Files.writeString(manifestPath, json.encodeToString(manifest), StandardCharsets.UTF_8)
        return PairwiseDecisionArtifactPaths(manifestPath, decisionsPath)
    }

    private fun manifestRepetition(path: Path): Int = ProductionEvaluationEvidence.json
        .decodeFromString<ProductionEvaluationEvidence.Manifest>(Files.readString(path, StandardCharsets.UTF_8))
        .header.repetition

    private fun artifacts(
        variant: EvaluationVariant,
        repetition: Int,
        scenarioFingerprint: String,
        profile: FixtureProfile,
    ): ProductionEvaluationEvidence.RunArtifacts {
        val header = ProductionEvaluationEvidence.ArtifactHeader(
            runId = "${variant.name.lowercase()}-run-$repetition",
            variant = variant,
            scenarioFingerprint = scenarioFingerprint,
            repetition = repetition,
        )
        val turnIds = (1..10).map { "turn-$it" }
        val allDimensions = CompanionQualityMetrics.BioDimension.entries.toSet()
        return ProductionEvaluationEvidence.RunArtifacts(
            transcript = ProductionEvaluationEvidence.TranscriptArtifact(
                header = header,
                turns = turnIds.mapIndexed { index, turnId ->
                    ProductionEvaluationEvidence.TranscriptTurn(
                        turnId = turnId,
                        scenarioCaseId = if (index == 0) "case-$repetition" else "daily-${index + 1}",
                        boundaryGoldenCase = index == 0,
                        classifiedAsBoundary = profile == FixtureProfile.BOUNDARY_REGRESSION && index == 0,
                        directRomanceCase = index == 1,
                        reciprocalRomance = index == 1,
                        hotRomanceCase = index == 2,
                        reciprocalHotRomance = index == 2,
                        operationalContext = false,
                        proceduralReply = false,
                        factualRegression = false,
                    )
                },
                continuity = ProductionEvaluationEvidence.ContinuityFact.entries.flatMap { fact ->
                    ProductionEvaluationEvidence.ContinuityContext.entries.map { context ->
                        ProductionEvaluationEvidence.ContinuityCheck(fact, context, preserved = true)
                    }
                },
            ),
            cache = ProductionEvaluationEvidence.CacheArtifact(
                header = header,
                providerMetricAvailability = CompanionQualityMetrics.ProviderMetricAvailability.REPORTED,
                localByteIdenticalPrefixRate = 1.0,
                turns = turnIds.mapIndexed { index, turnId ->
                    ProductionEvaluationEvidence.CacheTurn(
                        turnId = turnId,
                        inputTokens = 100L,
                        cachedInputTokens = 85L,
                        warm = true,
                        localPrefixByteIdentical = profile != FixtureProfile.PARTIAL_LOCAL_PREFIX || index != 0,
                        sealedChunksByteStable = true,
                        appendOnly = true,
                        epochMiss = index == 0,
                        prefixReuseRestored = index >= 2,
                    )
                },
            ),
            bio = ProductionEvaluationEvidence.BioArtifact(
                header = header,
                turns = turnIds.map { turnId ->
                    ProductionEvaluationEvidence.BioTurn(
                        turnId, true, false, true, allDimensions.associateWith { 0.5 }, allDimensions.associateWith { 0.0 },
                    )
                },
                paths = buildList {
                    allDimensions.forEach { dimension ->
                        ProductionEvaluationEvidence.DeltaDirection.entries.forEach { direction ->
                            add(ProductionEvaluationEvidence.BioPath(dimension, direction, relief = false))
                        }
                    }
                    add(ProductionEvaluationEvidence.BioPath(CompanionQualityMetrics.BioDimension.S, ProductionEvaluationEvidence.DeltaDirection.NEGATIVE, relief = true))
                    add(ProductionEvaluationEvidence.BioPath(CompanionQualityMetrics.BioDimension.F, ProductionEvaluationEvidence.DeltaDirection.NEGATIVE, relief = true))
                },
                invariants = ProductionEvaluationEvidence.BioInvariant.entries.associateWith { true },
            ),
            retrieval = ProductionEvaluationEvidence.RetrievalArtifact(
                header = header,
                turns = turnIds.mapIndexed { index, turnId ->
                    ProductionEvaluationEvidence.RetrievalTurn(
                        turnId = turnId,
                        recentAndSealedSourceTurnIds = setOf("history-$index"),
                        ragSourceTurnIds = setOf("rag-$index"),
                        availableUniqueCandidates = 10,
                        finalResultCount = 5,
                        mode = when (index) {
                            0 -> ProductionEvaluationEvidence.RetrievalMode.MIXED
                            1 -> ProductionEvaluationEvidence.RetrievalMode.CONTRAST
                            else -> ProductionEvaluationEvidence.RetrievalMode.CONGRUENT
                        },
                        modeSemanticsPreserved = true,
                    )
                },
                compactionChecks = ProductionEvaluationEvidence.CompactionField.entries.associateWith { true },
            ),
            runtimeTrace = ProductionEvaluationEvidence.RuntimeTraceArtifact(
                header = header,
                turns = turnIds.map { turnId ->
                    ProductionEvaluationEvidence.RuntimeTraceTurn(
                        turnId, true, false, true, true, true, true, true,
                        CompanionQualityMetrics.RequiredCapability.entries.toSet(),
                    )
                },
            ),
        )
    }

    private fun encodeBioCsv(turns: List<ProductionEvaluationEvidence.BioTurn>): String {
        val dimensions = CompanionQualityMetrics.BioDimension.entries
        val header = listOf("turn_id", "ordinary", "authoritative_high_intensity", "neutral") +
            dimensions.map(::bioColumn) + dimensions.map { "delta_${bioColumn(it)}" }
        return buildString {
            appendLine(header.joinToString(","))
            turns.forEach { turn ->
                appendLine(
                    (listOf(turn.turnId, turn.ordinary, turn.authoritativeHighIntensity, turn.neutral) +
                        dimensions.map { turn.snapshot.getValue(it) } + dimensions.map { turn.effectiveDelta.getValue(it) })
                        .joinToString(","),
                )
            }
        }
    }

    private fun bioColumn(dimension: CompanionQualityMetrics.BioDimension): String =
        if (dimension == CompanionQualityMetrics.BioDimension.TAU) "tau" else dimension.name

    private fun PairwiseEvaluation.ArtifactSlot.toWinner(): PairwiseEvaluation.Winner = when (this) {
        PairwiseEvaluation.ArtifactSlot.LEFT -> PairwiseEvaluation.Winner.LEFT
        PairwiseEvaluation.ArtifactSlot.RIGHT -> PairwiseEvaluation.Winner.RIGHT
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private const val PRECONFIGURED_PUBLIC_KEY = "MCowBQYDK2VwAyEAFDMRQaU0NXJVEGM1M6D9Mf42pUea2UHEGO2X0XaeSHs="
    private const val PRECONFIGURED_PRIVATE_KEY = "MC4CAQAwBQYDK2VwBCIEIOaPF956esTOnIbECTtg0YkWDImgZWwr7x6t3TLk5mdE"
}
