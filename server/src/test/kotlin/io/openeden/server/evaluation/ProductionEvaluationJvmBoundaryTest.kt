package io.openeden.server.evaluation

import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

class ProductionEvaluationJvmBoundaryTest {
    @Test
    fun `production report is final concrete and privately constructed`() {
        val report = ProductionEvaluationReport::class.java

        assertFalse(report.isInterface, "ProductionEvaluationReport must not be a JVM interface")
        assertTrue(Modifier.isFinal(report.modifiers), "ProductionEvaluationReport must be JVM final")
        assertTrue(report.declaredConstructors.isNotEmpty(), "ProductionEvaluationReport must declare a constructor")
        assertTrue(
            report.declaredConstructors.all { Modifier.isPrivate(it.modifiers) },
            "ProductionEvaluationReport constructors must all be JVM private: ${report.declaredConstructors.toList()}",
        )
    }

    @Test
    fun `only canonical path gate is a public production evaluator`() {
        val classes = evaluationClasses()
        val publicClasses = classes.filter { Modifier.isPublic(it.modifiers) }
        val forbiddenMembers = publicClasses.flatMap { type ->
            (type.declaredConstructors.toList() + type.declaredMethods.toList())
                .filter { Modifier.isPublic(it.modifiers) }
                .filterNot { executable -> executable.isCompilerGeneratedDataOrSerialization(type) }
                .filter { executable -> executable.parameterTypes.any(::isForbiddenBoundaryType) }
                .map { executable -> "${type.name}#${executable.signature()}" }
        }
        assertEquals(emptyList(), forbiddenMembers.sorted())

        val reportAuthorityLeaks = publicClasses
            .filter { it.name == ProductionEvaluationReport::class.java.name || it.enclosingClass == ProductionEvaluationReport::class.java }
            .flatMap { type ->
                type.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && isForbiddenAuthorityReturn(it.returnType) }
                    .map { method -> "${type.name}#${method.signature()}" }
            }
        assertEquals(emptyList(), reportAuthorityLeaks.sorted())

        val publicCreators = publicClasses.flatMap { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .filter { method -> method.returnsReleaseReport() }
                .map { method -> type to method }
        }
        assertEquals(1, publicCreators.size, publicCreators.joinToString { (owner, method) -> "${owner.name}#${method.signature()}" })
        val (owner, evaluate) = publicCreators.single()
        assertEquals("io.openeden.server.evaluation.ProductionEvaluationReport\$Companion", owner.name)
        assertEquals("evaluate", evaluate.name)
        assertEquals(
            listOf(ProductionEvaluationInputPaths::class.java),
            evaluate.parameterTypes.filterNot { it.name == "kotlin.coroutines.Continuation" },
        )

        assertJvmNonPublic("io.openeden.server.evaluation.ProductionEvaluationMechanics")
        assertJvmNonPublic("io.openeden.server.evaluation.FrozenPairwiseTrust")
        assertJvmNonPublic("io.openeden.server.evaluation.FrozenTask5Trust")
        assertJvmNonPublicOrAbsent("io.openeden.server.evaluation.FrozenTrustedSignerRegistry")
        assertJvmNonPublicOrAbsent("io.openeden.server.evaluation.ProductionEvaluationGate")
        assertJvmNonPublicOrAbsent("io.openeden.server.evaluation.TestOnlyProductionEvaluationGate")

        val fixture = Class.forName("io.openeden.server.evaluation.TestOnlyTask5ExportFixture", false, javaClass.classLoader)
        assertTrue(
            fixture.declaredMethods.none { method ->
                Modifier.isPublic(method.modifiers) &&
                    (method.name == "gate" || method.name == "evaluate" || isForbiddenBoundaryType(method.returnType))
            },
        )
    }

    @Test
    fun `javap shows no public mechanics registry or fixture gate`() {
        val report = javap("io.openeden.server.evaluation.ProductionEvaluationReport")
        val mechanics = javap("io.openeden.server.evaluation.ProductionEvaluationMechanics")
        val pairwiseTrust = javap("io.openeden.server.evaluation.FrozenPairwiseTrust")
        val task5Trust = javap("io.openeden.server.evaluation.FrozenTask5Trust")
        val fixture = javap("io.openeden.server.evaluation.TestOnlyTask5ExportFixture")

        assertTrue(report.lineSequence().first { it.contains(" class ") || it.contains(" interface ") }.startsWith("public final class "))
        assertFalse(report.contains("public interface "))
        assertFalse(mechanics.lineSequence().first { it.contains(" class ") }.startsWith("public "))
        assertFalse(pairwiseTrust.lineSequence().first { it.contains(" class ") }.startsWith("public "))
        assertFalse(task5Trust.lineSequence().first { it.contains(" class ") }.startsWith("public "))
        assertFalse(fixture.contains(" gate("))
        assertFalse(fixture.contains(" evaluate("))
        assertFalse(fixture.contains("FrozenTrustedSignerRegistry"))
        assertFalse(fixture.contains("TestOnlyProductionEvaluationGate"))
    }

    @Test
    fun `canonical gate trusts only child process environment`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("canonical-process-gate"))
        val descriptor = writeProcessDescriptor(input)

        val trusted = runGateProcess(descriptor, TRUSTED_TEST_SIGNER_FINGERPRINT)
        assertEquals(0, trusted.exitCode, trusted.output)
        assertTrue(trusted.output.contains("\"evidenceKind\":\"PRODUCTION\""), trusted.output)
        assertTrue(trusted.output.contains("\"releaseDecision\":\"PASS\""), trusted.output)

        val untrusted = runGateProcess(descriptor, SELF_ISSUED_TEST_SIGNER_FINGERPRINT)
        assertTrue(untrusted.exitCode != 0, untrusted.output)
        assertTrue(untrusted.output.contains("not configured as trusted"), untrusted.output)
    }

    @Test
    fun `production report authority collections are JVM unmodifiable`() = runTest {
        val input = TestOnlyTask5ExportFixture.exportInput(Files.createTempDirectory("immutable-process-gate"))
        val descriptor = writeProcessDescriptor(input)

        val result = runGateProcess(descriptor, TRUSTED_TEST_SIGNER_FINGERPRINT, "assert-authority-immutable")

        assertEquals(0, result.exitCode, result.output)
        assertTrue(result.output.contains("\"releaseDecision\":\"PASS\""), result.output)
    }

    private fun evaluationClasses(): List<Class<*>> {
        val packagePath = ProductionEvaluationReport::class.java.packageName.replace('.', '/')
        val roots = javaClass.classLoader.getResources(packagePath).toList()
        return roots.filter { it.protocol == "file" }.flatMap { resource ->
            val root = Path.of(resource.toURI())
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.name.endsWith(".class") }
                    .map { path ->
                        val relative = root.relativize(path).toString().replace('\\', '/').removeSuffix(".class")
                        Class.forName("${ProductionEvaluationReport::class.java.packageName}.${relative.replace('/', '.')}", false, javaClass.classLoader)
                    }
                    .toList()
            }
        }.distinctBy(Class<*>::getName)
    }

    private fun assertJvmNonPublic(className: String) {
        val type = Class.forName(className, false, javaClass.classLoader)
        assertFalse(Modifier.isPublic(type.modifiers), "$className must not be JVM public")
    }

    private fun assertJvmNonPublicOrAbsent(className: String) {
        val type = runCatching { Class.forName(className, false, javaClass.classLoader) }.getOrNull() ?: return
        assertFalse(Modifier.isPublic(type.modifiers), "$className must not be JVM public")
    }

    private fun isForbiddenBoundaryType(type: Class<*>): Boolean =
        type.name == "io.openeden.server.evaluation.ProductionEvaluationEvidence\$AuthenticatedABEvidence" ||
            type.name == "io.openeden.server.evaluation.ProductionEvaluationEvidence\$RunArtifacts" ||
            type == CompanionQualityMetrics::class.java ||
            type == PairwiseEvaluation::class.java ||
            java.security.Key::class.java.isAssignableFrom(type) ||
            type.simpleName.contains("Verifier", ignoreCase = true) ||
            type.simpleName.contains("Registry", ignoreCase = true) ||
            type.simpleName.contains("Fingerprint", ignoreCase = true)

    private fun isForbiddenAuthorityReturn(type: Class<*>): Boolean =
        type.name == "io.openeden.server.evaluation.ProductionEvaluationEvidence\$AuthenticatedABEvidence" ||
            type.name == "io.openeden.server.evaluation.ProductionEvaluationEvidence\$RunArtifacts" ||
            java.security.Key::class.java.isAssignableFrom(type) ||
            type.simpleName.contains("Verifier", ignoreCase = true) ||
            type.simpleName.contains("Registry", ignoreCase = true) ||
            type.simpleName.contains("Fingerprint", ignoreCase = true) ||
            type.simpleName.contains("Trust", ignoreCase = true)

    private fun Method.returnsReleaseReport(): Boolean =
        returnType == ProductionEvaluationReport::class.java ||
            genericParameterTypes.any { it.typeName.contains("Continuation<? super io.openeden.server.evaluation.ProductionEvaluationReport>") }

    private fun Executable.isCompilerGeneratedDataOrSerialization(owner: Class<*>): Boolean =
        name.startsWith("component") ||
            name.startsWith("copy") ||
            name.startsWith("write\$Self") ||
            owner.simpleName.endsWith("\$serializer") ||
            parameterTypes.any { it.name == "kotlinx.serialization.internal.SerializationConstructorMarker" } ||
            parameterTypes.any { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" } ||
            owner.name.endsWith("PairwiseEvaluation\$PersistedReleaseReport")

    private fun Executable.signature(): String = buildString {
        append(name)
        append(parameterTypes.joinToString(prefix = "(", postfix = ")") { it.typeName })
    }

    private fun javap(className: String): String {
        val executable = Path.of(System.getProperty("java.home"), "bin", "javap.exe")
        val process = ProcessBuilder(
            executable.absolutePathString(),
            "-classpath",
            System.getProperty("java.class.path"),
            "-p",
            "-s",
            className,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private companion object {
        const val TRUSTED_TEST_SIGNER_FINGERPRINT = "c54d7590a6ad622bfbb2e57b049987a357e8927a2ffbb7b4b97556567eb7a01e"
        const val SELF_ISSUED_TEST_SIGNER_FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private data class GateProcessResult(val exitCode: Int, val output: String)

private fun writeProcessDescriptor(input: ProductionEvaluationInputPaths): Path {
    val descriptor = Files.createTempFile("production-evaluation-input", ".txt")
    val lines = buildList {
        add(input.baseline.size.toString())
        input.baseline.forEach { addAll(it.asLines()) }
        add(input.candidate.size.toString())
        input.candidate.forEach { addAll(it.asLines()) }
        add(input.pairwiseDecisions.size.toString())
        input.pairwiseDecisions.forEach { paths ->
            add(paths.manifest.absolutePathString())
            add(paths.decisions.absolutePathString())
        }
    }
    Files.write(descriptor, lines)
    return descriptor
}

private fun ProductionEvaluationEvidence.Task5RunExportPaths.asLines(): List<String> = listOf(
    manifest,
    transcript,
    bioSnapshots,
    relationshipEvents,
    cacheManifest,
    evaluationReport,
    retrievalTrace,
    runtimeTrace,
).map(Path::absolutePathString)

private fun runGateProcess(
    descriptor: Path,
    trustedFingerprint: String,
    vararg options: String,
): GateProcessResult {
    val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
    val process = ProcessBuilder(
        java.absolutePathString(),
        "-cp",
        System.getProperty("java.class.path"),
        ProductionEvaluationProcessMain::class.java.name,
        descriptor.absolutePathString(),
        *options,
    ).redirectErrorStream(true).apply {
        environment()["OPENEDEN_EVALUATION_TRUSTED_SIGNER_FINGERPRINTS"] = trustedFingerprint
    }.start()
    val output = process.inputStream.bufferedReader().readText().trim()
    return GateProcessResult(process.waitFor(), output)
}

object ProductionEvaluationProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2)
        val input = readProcessDescriptor(Path.of(args[0]))
        val report = kotlinx.coroutines.runBlocking { ProductionEvaluationReport.evaluate(input) }
        if (args.getOrNull(1) == "assert-authority-immutable") {
            val mutations = listOf(
                "decisions" to { ProductionEvaluationJavaMutationProbe.replaceFirstDecision(report) },
                "dimensionWinners" to { ProductionEvaluationJavaMutationProbe.replaceDimensionWinner(report) },
                "provenance" to { ProductionEvaluationJavaMutationProbe.clearManifestFingerprints(report) },
                "metricValue" to { ProductionEvaluationJavaMutationProbe.clearPositivePathDimensions(report) },
                "metricEvidence" to { ProductionEvaluationJavaMutationProbe.clearMetricEvidenceFingerprints(report) },
            )
            mutations.forEach { (label, mutation) ->
                val failure = runCatching(mutation).exceptionOrNull()
                check(failure is UnsupportedOperationException) {
                    "$label must reject JVM mutation, got ${failure?.javaClass?.name ?: "success"}"
                }
            }
            check(report.releaseDecision() == PairwiseEvaluation.ReleaseDecision.PASS)
        }
        println(ProductionEvaluationEvidence.json.encodeToString(report.persisted()))
    }
}

private fun readProcessDescriptor(path: Path): ProductionEvaluationInputPaths {
    val lines = Files.readAllLines(path).iterator()
    fun nextPath(): Path = Path.of(lines.next())
    fun nextRuns(): List<ProductionEvaluationEvidence.Task5RunExportPaths> = List(lines.next().toInt()) {
        ProductionEvaluationEvidence.Task5RunExportPaths(
            manifest = nextPath(),
            transcript = nextPath(),
            bioSnapshots = nextPath(),
            relationshipEvents = nextPath(),
            cacheManifest = nextPath(),
            evaluationReport = nextPath(),
            retrievalTrace = nextPath(),
            runtimeTrace = nextPath(),
        )
    }
    val baseline = nextRuns()
    val candidate = nextRuns()
    val pairwise = List(lines.next().toInt()) { PairwiseDecisionArtifactPaths(nextPath(), nextPath()) }
    require(!lines.hasNext()) { "Unexpected production evaluation process arguments" }
    return ProductionEvaluationInputPaths(baseline, candidate, pairwise)
}
