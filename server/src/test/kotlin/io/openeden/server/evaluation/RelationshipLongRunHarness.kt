package io.openeden.server.evaluation

import io.openeden.bio.BioVector
import io.openeden.llm.LlmCacheMetrics
import io.openeden.runtime.time.RuntimeClock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EvaluatedTurn(
    val index: Int,
    val nowMs: Long,
    val userText: String,
    val tags: Set<String>,
    val response: String,
)

data class RelationshipEvent(
    val turnIndex: Int,
    val nowMs: Long,
    val tag: String,
)

data class PromptCacheManifestEntry(
    val turnIndex: Int,
    val nowMs: Long,
    val metrics: LlmCacheMetrics,
)

data class LongRunResult(
    val turns: List<EvaluatedTurn>,
    val bioSnapshots: List<BioVector>,
    val cacheReadRate: Double?,
    val relationshipEvents: List<RelationshipEvent>,
    val promptCacheManifest: List<PromptCacheManifestEntry>,
) {
    suspend fun exportTo(outputDirectory: Path): ExportedArtifacts = withContext(Dispatchers.IO) {
        Files.createDirectories(outputDirectory)
        val files = listOf(
            outputDirectory.resolve("transcript.jsonl") to turns.joinToString("\n") { turn ->
                "{\"turn\":${turn.index},\"now_ms\":${turn.nowMs},\"user_text\":\"${turn.userText.jsonEscape()}\",\"response\":\"${turn.response}\",\"tags\":${turn.tags.jsonArray()}}"
            },
            outputDirectory.resolve("bio.csv") to buildString {
                appendLine("turn,now_ms,L,P,E,S,tau,V,M,F")
                turns.zip(bioSnapshots).forEach { (turn, bio) ->
                    appendLine("${turn.index},${turn.nowMs},${bio.l},${bio.p},${bio.e},${bio.s},${bio.tau},${bio.v},${bio.m},${bio.f}")
                }
            }.trimEnd(),
            outputDirectory.resolve("relationship-events.jsonl") to relationshipEvents.joinToString("\n") { event ->
                "{\"turn\":${event.turnIndex},\"now_ms\":${event.nowMs},\"tag\":\"${event.tag}\"}"
            },
            outputDirectory.resolve("prompt-cache-manifest.jsonl") to promptCacheManifest.joinToString("\n") { entry ->
                "{\"turn\":${entry.turnIndex},\"now_ms\":${entry.nowMs},\"input_tokens\":${entry.metrics.inputTokens},\"cached_input_tokens\":${entry.metrics.cachedInputTokens},\"cache_read_rate\":${entry.metrics.cacheHitRate}}"
            },
            outputDirectory.resolve("evaluation-report.md") to buildString {
                appendLine("# Relationship Evaluation")
                appendLine()
                appendLine("- Turns: ${turns.size}")
                appendLine("- Cache read rate: ${cacheReadRate ?: "unobservable"}")
                appendLine("- Relationship events: ${relationshipEvents.size}")
            }.trimEnd(),
        )
        files.forEach { (path, content) -> Files.writeString(path, "$content\n", StandardCharsets.UTF_8) }
        ExportedArtifacts(files.map { it.first }, files.associate { (path, _) -> path.fileName.toString() to path.sha256() })
    }
}

data class ExportedArtifacts(
    val files: List<Path>,
    val fingerprints: Map<String, String>,
)

class RelationshipLongRunHarness private constructor(
    private val clock: MutableEvaluationClock,
    private val pipeline: FakeEvaluationPipeline,
) {
    suspend fun run(scenario: RelationshipScenario): LongRunResult {
        val turns = mutableListOf<EvaluatedTurn>()
        val bioSnapshots = mutableListOf<BioVector>()
        val relationshipEvents = mutableListOf<RelationshipEvent>()
        val cacheManifest = mutableListOf<PromptCacheManifestEntry>()

        scenario.turns.forEachIndexed { index, scenarioTurn ->
            clock.advanceBy(scenarioTurn.advanceMs)
            val evaluation = pipeline.evaluate(index + 1, clock, scenarioTurn)
            turns += evaluation.turn
            bioSnapshots += evaluation.bio
            cacheManifest += evaluation.cacheEntry
            scenarioTurn.tags
                .filter { it in relationshipEventTags }
                .forEach { relationshipEvents += RelationshipEvent(index + 1, clock.nowMs(), it) }
        }
        val metrics = cacheManifest.map(PromptCacheManifestEntry::metrics)
        return LongRunResult(
            turns = turns,
            bioSnapshots = bioSnapshots,
            cacheReadRate = if (metrics.isEmpty()) null else metrics.sumOf { it.cachedInputTokens }.toDouble() / metrics.sumOf { it.inputTokens },
            relationshipEvents = relationshipEvents,
            promptCacheManifest = cacheManifest,
        )
    }

    companion object {
        fun fake(startMs: Long = 0L): RelationshipLongRunHarness = RelationshipLongRunHarness(
            clock = MutableEvaluationClock(startMs),
            pipeline = FakeEvaluationPipeline(),
        )

        private val relationshipEventTags = setOf(
            "confession", "acceptance", "restart", "hot-romance", "chores", "boundary", "conflict", "repair",
        )
    }
}

private class MutableEvaluationClock(
    private var currentMs: Long,
) : RuntimeClock {
    override fun nowMs(): Long = currentMs

    fun advanceBy(milliseconds: Long) {
        require(milliseconds >= 0L)
        currentMs += milliseconds
    }
}

private class FakeEvaluationPipeline {
    fun evaluate(index: Int, clock: RuntimeClock, scenarioTurn: ScenarioTurn): FakeEvaluation = FakeEvaluation(
        turn = EvaluatedTurn(index, clock.nowMs(), scenarioTurn.userText, scenarioTurn.tags, "baseline-response-$index"),
        bio = deterministicBio(index),
        cacheEntry = PromptCacheManifestEntry(
            turnIndex = index,
            nowMs = clock.nowMs(),
            metrics = LlmCacheMetrics(inputTokens = 1_000L, cachedInputTokens = if (index == 1) 0L else 900L),
        ),
    )

    private fun deterministicBio(index: Int): BioVector {
        val offset = (index % 20) / 100.0f
        return BioVector(0.5f + offset, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    }
}

private data class FakeEvaluation(
    val turn: EvaluatedTurn,
    val bio: BioVector,
    val cacheEntry: PromptCacheManifestEntry,
)

private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun Set<String>.jsonArray(): String = sorted().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

private fun Path.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(this))
    .joinToString("") { "%02x".format(it) }
