package io.openeden.server.evaluation

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString

class ProductionEvaluationEvidenceTest {
    @Test
    fun `self issued Task 5 signer is rejected by frozen trust`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("self-issued-ab"),
            signer = TestOnlyTask5ExportFixture.Signer.SELF_ISSUED,
        )

        assertIs<IllegalArgumentException>(
            runCatching { evaluate(input) }.exceptionOrNull(),
        )
    }

    @Test
    fun `trusted Task 5 and pairwise files can satisfy gate mechanics`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("trusted-task5-ab"))

        val report = evaluate(input)

        assertEquals(PairwiseEvaluation.ReleaseDecision.PASS, report.releaseDecision())
        assertEquals(0, report.metrics.relationship.boundaryFalsePositives.value)
        assertEquals(0.85, report.metrics.cache.warmCacheReadRate.value)
        val persisted = report.persisted()
        assertEquals(6, persisted.productionProvenance!!.manifestFingerprints.size)
        assertEquals(TRUSTED_TEST_SIGNER_FINGERPRINT, persisted.productionProvenance.signerKeyFingerprint)
        assertEquals("judge-v1", persisted.pairwiseEvaluation!!.metadata.evaluatorVersion)
        assertEquals(3, persisted.pairwiseEvaluation.decisions.size)
    }

    @Test
    fun `gate fails closed on every missing Task 5 or runtime artifact`() = runTest {
        ProductionEvaluationEvidence.RequiredArtifact.entries.forEach { artifact ->
            val input = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("missing-task5-artifact"))
            Files.delete(input.candidate.first().pathFor(artifact))

            assertIs<IllegalArgumentException>(
                runCatching { evaluate(input) }.exceptionOrNull(),
                artifact.name,
            )
        }
    }

    @Test
    fun `gate rejects explicit path mismatch and hashed content tampering`() = runTest {
        val mismatch = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("path-mismatch"))
        val candidate = mismatch.candidate.first()
        val swapped = mismatch.copy(
            candidate = listOf(candidate.copy(transcript = mismatch.baseline.first().transcript)) + mismatch.candidate.drop(1),
        )

        assertIs<IllegalArgumentException>(
            runCatching { evaluate(swapped) }.exceptionOrNull(),
        )

        val tampered = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("content-tamper"))
        Files.writeString(tampered.candidate.first().runtimeTrace, "malformed runtime evidence")
        assertIs<IllegalArgumentException>(
            runCatching { evaluate(tampered) }.exceptionOrNull(),
        )
    }

    @Test
    fun `gate rejects malformed typed content even when trusted signer signed it`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("malformed-task5-content"),
            profile = TestOnlyTask5ExportFixture.Profile.MALFORMED_RUNTIME,
        )

        assertIs<IllegalArgumentException>(
            runCatching { evaluate(input) }.exceptionOrNull(),
        )
    }

    @Test
    fun `cache rates are derived from per turn Task 5 observations`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("per-turn-cache-observations"),
            profile = TestOnlyTask5ExportFixture.Profile.PARTIAL_LOCAL_PREFIX,
        )

        val report = evaluate(input)

        assertEquals(0.85, report.metrics.cache.warmCacheReadRate.value)
        assertEquals(0.9, report.metrics.cache.localByteIdenticalPrefixRate.value)
    }

    @Test
    fun `pairwise decisions must come from trusted authenticated files`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("self-issued-pairwise"),
            pairwiseSigner = TestOnlyTask5ExportFixture.Signer.SELF_ISSUED,
        )

        assertIs<IllegalArgumentException>(
            runCatching { evaluate(input) }.exceptionOrNull(),
        )
    }

    @Test
    fun `pairwise gate fails closed on missing swapped or tampered files`() = runTest {
        val missing = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("missing-pairwise"))
        Files.delete(missing.pairwiseDecisions.single().decisions)
        assertIs<IllegalArgumentException>(
            runCatching { evaluate(missing) }.exceptionOrNull(),
        )

        val first = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("pairwise-path-first"))
        val second = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("pairwise-path-second"))
        val swapped = first.copy(
            pairwiseDecisions = listOf(
                first.pairwiseDecisions.single().copy(decisions = second.pairwiseDecisions.single().decisions),
            ),
        )
        assertIs<IllegalArgumentException>(
            runCatching { evaluate(swapped) }.exceptionOrNull(),
        )

        val tampered = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("tampered-pairwise"))
        Files.writeString(tampered.pairwiseDecisions.single().decisions, "{}")
        assertIs<IllegalArgumentException>(
            runCatching { evaluate(tampered) }.exceptionOrNull(),
        )
    }

    @Test
    fun `authenticated pairwise fingerprints and blind slots remain binding`() = runTest {
        val forgedFingerprint = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("forged-pairwise-fingerprint"),
            profile = TestOnlyTask5ExportFixture.Profile.FORGED_PAIRWISE_FINGERPRINT,
        )
        val forgedSlot = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("forged-pairwise-slot"),
            profile = TestOnlyTask5ExportFixture.Profile.FORGED_PAIRWISE_SLOT,
        )

        assertEquals(PairwiseEvaluation.ReleaseDecision.FAIL, evaluate(forgedFingerprint).releaseDecision())
        assertEquals(PairwiseEvaluation.ReleaseDecision.FAIL, evaluate(forgedSlot).releaseDecision())
    }

    @Test
    fun `typed regression loaded from trusted files changes metrics and fails release`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("derived-regression"),
            profile = TestOnlyTask5ExportFixture.Profile.BOUNDARY_REGRESSION,
        )

        val report = evaluate(input)

        assertEquals(1, report.metrics.relationship.boundaryFalsePositives.value)
        assertEquals(PairwiseEvaluation.ReleaseDecision.FAIL, report.releaseDecision())
    }

    @Test
    fun `system property mutation cannot replace frozen trust root`() = runTest {
        val trusted = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("frozen-trusted"))
        val selfIssued = TestOnlyTask5ExportFixture.exportInput(
            Files.createTempDirectory("frozen-self-issued"),
            signer = TestOnlyTask5ExportFixture.Signer.SELF_ISSUED,
            pairwiseSigner = TestOnlyTask5ExportFixture.Signer.SELF_ISSUED,
        )
        assertEquals(PairwiseEvaluation.ReleaseDecision.PASS, evaluate(trusted, addUntrustedSystemProperty = true).releaseDecision())
        assertIs<IllegalArgumentException>(runCatching { evaluate(selfIssued, addUntrustedSystemProperty = true) }.exceptionOrNull())
    }

    @Test
    fun `public production API accepts only artifact path input`() {
        val gateMethods = ProductionEvaluationReport.Companion::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        }
        val evaluate = gateMethods.single { it.name == "evaluate" }
        assertEquals(ProductionEvaluationInputPaths::class.java, evaluate.parameterTypes.first())
        assertTrue(gateMethods.none { method -> method.parameterTypes.any(::isForbiddenProductionParameter) })

        assertTrue(
            PairwiseEvaluation.ReleaseReport::class.java.declaredConstructors.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic
            },
        )
        assertTrue(
            PairwiseEvaluation.ReleaseReport.Companion::class.java.declaredMethods.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic &&
                    (it.name.contains("production", ignoreCase = true) || it.parameterTypes.any(::isForbiddenProductionParameter))
            },
        )
        assertTrue(
            ProductionEvaluationEvidence::class.java.declaredMethods.none {
                Modifier.isPublic(it.modifiers) && !it.isSynthetic &&
                    (it.name.contains("load", ignoreCase = true) || it.parameterTypes.any(::isForbiddenProductionParameter))
            },
        )
    }

    private fun isForbiddenProductionParameter(type: Class<*>): Boolean =
        type == ProductionEvaluationEvidence.AuthenticatedABEvidence::class.java ||
            type == CompanionQualityMetrics::class.java ||
            type == PairwiseEvaluation::class.java ||
            java.security.Key::class.java.isAssignableFrom(type) ||
            type.simpleName.contains("Verifier", ignoreCase = true) ||
            type.simpleName.contains("Registry", ignoreCase = true) ||
            type.simpleName.contains("Fingerprint", ignoreCase = true)

    private fun evaluate(
        input: ProductionEvaluationInputPaths,
        addUntrustedSystemProperty: Boolean = false,
    ): ForkedProductionReport {
        val descriptor = writeDescriptor(input)
        val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
        val command = buildList {
            add(java.absolutePathString())
            if (addUntrustedSystemProperty) {
                add("-Dopeneden.evaluation.trustedSignerFingerprints=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(ProductionEvaluationProcessMain::class.java.name)
            add(descriptor.absolutePathString())
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS"] = TRUSTED_TEST_SIGNER_FINGERPRINT
        }.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() != 0) throw IllegalArgumentException(output)
        return ForkedProductionReport(ProductionEvaluationEvidence.json.decodeFromString(output.lineSequence().last(String::isNotBlank)))
    }

    private fun writeDescriptor(input: ProductionEvaluationInputPaths): Path {
        val descriptor = Files.createTempFile("production-evaluation-input", ".txt")
        Files.write(descriptor, buildList {
            add(input.baseline.size.toString())
            input.baseline.forEach { addAll(it.lines()) }
            add(input.candidate.size.toString())
            input.candidate.forEach { addAll(it.lines()) }
            add(input.pairwiseDecisions.size.toString())
            input.pairwiseDecisions.forEach { paths ->
                add(paths.manifest.absolutePathString())
                add(paths.decisions.absolutePathString())
            }
        })
        return descriptor
    }

    private fun ProductionEvaluationEvidence.Task5RunExportPaths.lines(): List<String> = listOf(
        manifest,
        transcript,
        bioSnapshots,
        relationshipEvents,
        cacheManifest,
        evaluationReport,
        retrievalTrace,
        runtimeTrace,
    ).map(Path::absolutePathString)

    private class ForkedProductionReport(
        private val report: PairwiseEvaluation.PersistedReleaseReport,
    ) {
        val metrics: CompanionQualityMetrics get() = report.metrics
        fun releaseDecision(): PairwiseEvaluation.ReleaseDecision = report.releaseDecision
        fun persisted(): PairwiseEvaluation.PersistedReleaseReport = report
    }

    private companion object {
        const val TRUSTED_TEST_SIGNER_FINGERPRINT = "c54d7590a6ad622bfbb2e57b049987a357e8927a2ffbb7b4b97556567eb7a01e"
    }
}
