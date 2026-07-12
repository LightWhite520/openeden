package io.openeden.server

import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.DevelopmentMessagePipeline
import io.openeden.runtime.HeartbeatScheduler
import io.openeden.runtime.LoggingHeartbeatDelivery
import io.openeden.runtime.HeartbeatOwner
import io.openeden.runtime.JvmInferenceExecutor
import io.openeden.runtime.OwnerHeartbeatRouteResolver
import io.openeden.runtime.RuntimeConfig
import io.openeden.runtime.RuntimeTickScheduler
import io.openeden.runtime.SecureRandomHeartbeatInterval
import io.openeden.runtime.SecureRandomSineWaveFluctuation
import io.openeden.runtime.SineWaveFluctuationEngine
import io.openeden.runtime.VectorWriteService
import io.openeden.runtime.DurableDiaryWorker
import io.openeden.runtime.DiaryNarrativeGenerator
import io.openeden.runtime.DiaryWorkerScheduler
import io.openeden.model.LocalModelArtifactLoader
import io.openeden.model.LocalModelArtifact
import io.openeden.codebook.CodebookDictionary
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.DjlVqVaeCodebookModelRunner
import io.openeden.codebook.VqVaeCodebookQuantizer
import io.openeden.memory.DjlMemoryEmbeddingModel
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.bio.BioVector
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.server.db.SqlDelightDiaryTaskStore
import io.openeden.server.db.SqlDelightMemoryRepository
import io.openeden.server.db.SqlDelightTraceStore
import io.openeden.server.db.SqlDelightSessionStateStore
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path

/** The shared, durable-backed pipeline, published for [configureRouting] to consume. */
val PipelineKey = AttributeKey<DevelopmentMessagePipeline>("openeden.pipeline")

/**
 * Boots the persona runtime: durable SQLite store → pipeline → heartbeat scheduler. Runs before
 * [configureRouting] (see application.yaml) so the route reads the shared pipeline from attributes.
 * The scheduler runs on its own dispatcher (§9.3.3) and is torn down on application stop.
 */
fun Application.configureRuntime() {
    val store = SqlDelightSessionStateStore.open(resolveRuntimeDbPath())
    val diaryTaskStore = SqlDelightDiaryTaskStore.open(resolveRuntimeDbPath())
    val traceStore = SqlDelightTraceStore.open(resolveRuntimeDbPath())
    // One VectorWriteService shared by the pipeline and the scheduler so all per-session writes
    // (user deltas + shock-heartbeat latch) serialize on the same Mutex registry (§14.2).
    val writer = VectorWriteService(store)
    val runtimeConfig = RuntimeConfig.Default.copy(owner = resolveHeartbeatOwner())
    val inferenceExecutor = JvmInferenceExecutor()
    val models = loadRuntimeModels()
    val memoryStore = SqlDelightMemoryRepository.open(resolveRuntimeDbPath(), models.embeddingModel)
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = loadDefaultPersonaConfig(),
        llmClient = OpenAiResponsesLlmClient(
            apiKey = requireNotNull(System.getenv("OPENEDEN_OPENAI_API_KEY")) {
                "OPENEDEN_OPENAI_API_KEY is required for server runtime"
            },
            model = System.getenv("OPENEDEN_OPENAI_MODEL") ?: "gpt-5-mini",
            baseUrl = System.getenv("OPENEDEN_OPENAI_BASE_URL") ?: "https://api.openai.com/v1",
        ),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = inferenceExecutor,
        memoryStore = memoryStore,
        quantizer = models.quantizer,
        memoryEmbeddingModel = models.embeddingModel,
        diaryTaskStore = diaryTaskStore,
        traceStore = traceStore,
    )
    attributes.put(PipelineKey, pipeline)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val diaryWorker = DurableDiaryWorker(
        taskStore = diaryTaskStore,
        memoryStore = memoryStore,
        generator = DiaryNarrativeGenerator { task ->
            val content = "Diary event: ${task.reason}"
            MemoryEntry(
                id = "narrative:${task.id}",
                sessionId = task.sessionId,
                content = content,
                room = MemoryRoom.EVENT_ROOM,
                kind = MemoryKind.NARRATIVE,
                semanticEmbedding = models.embeddingModel.embed(content),
                emotionalEmbedding = models.embeddingModel.embed(BioVector.Neutral),
                metadata = MemoryMetadata(
                    snapshot8D = BioVector.Neutral,
                    omegaState = 0.0f,
                    deltaVec = io.openeden.bio.VectorDelta.Zero,
                    snapshotOrigin = BioVector.Neutral,
                    userId = "diary-worker",
                ),
            )
        },
    )
    val diaryJob = DiaryWorkerScheduler(
        taskStore = diaryTaskStore,
        worker = diaryWorker,
        sessionIds = { store.sessionIds() },
        nowMs = { System.currentTimeMillis() },
    ).start(scope)
    val scheduler = HeartbeatScheduler(
        pipeline = pipeline,
        store = store,
        writer = writer,
        delivery = LoggingHeartbeatDelivery { log.info(it) },
        interval = SecureRandomHeartbeatInterval(),
        routeResolver = OwnerHeartbeatRouteResolver(runtimeConfig.owner),
    )
    val job = scheduler.start(scope)
    val tickJob = RuntimeTickScheduler(
        store = store,
        writer = writer,
        fluctuation = SineWaveFluctuationEngine(SecureRandomSineWaveFluctuation.profile()),
        inferenceExecutor = inferenceExecutor,
        config = runtimeConfig,
    ).start(scope)
    log.info("OpenEden heartbeat scheduler started")

    monitor.subscribe(ApplicationStopping) {
        job.cancel()
        tickJob.cancel()
        diaryJob.cancel()
        scope.cancel()
        store.close()
        memoryStore.close()
        diaryTaskStore.close()
        traceStore.close()
        log.info("OpenEden runtime stopped")
    }
}

internal fun loadDefaultPersonaConfig(): PersonaConfig =
    PersonaFileLoader.load(resolveDefaultPersonaPath())

internal fun resolveDefaultPersonaPath(): Path = resolveFromRoot(Path.of("persona", "default.yaml"))

private fun resolveRuntimeDbPath(): Path = resolveFromRoot(Path.of("data", "runtime", "openeden.db"))

private fun loadLocalArtifact() = runCatching {
    val path = System.getenv("OPENEDEN_LOCAL_MODEL_ARTIFACT")
        ?.takeIf { it.isNotBlank() }
        ?.let(Path::of)
        ?: resolveFromRoot(Path.of("data", "models", "local-model-artifact.json"))
    if (Files.exists(path)) LocalModelArtifactLoader.read(path) else null
}.getOrNull()

private data class RuntimeModels(
    val quantizer: CodebookQuantizer,
    val embeddingModel: MemoryEmbeddingModel,
)

private fun loadRuntimeModels(): RuntimeModels {
    val artifact = requireNotNull(loadLocalArtifact()) {
        "Local model artifact is required for server startup"
    }
    return when (System.getenv("OPENEDEN_MODEL_BACKEND")?.lowercase() ?: "djl") {
        "artifact" -> RuntimeModels(artifact.codebookQuantizer(), artifact.memoryEmbeddingModel())
        "djl" -> {
            val runner = DjlVqVaeCodebookModelRunner.fromModelPath(
                modelPath = requiredModelPath("OPENEDEN_DJL_VQVAE_MODEL_PATH"),
                modelName = System.getenv("OPENEDEN_DJL_MODEL_NAME") ?: "model",
                engineName = System.getenv("OPENEDEN_DJL_ENGINE") ?: "PyTorch",
                inputDimension = 9,
                codebook = artifact.vqVae.codebook,
                topK = artifact.vqVae.topK,
            )
            RuntimeModels(
                quantizer = VqVaeCodebookQuantizer(
                    modelRunner = runner,
                    dictionary = CodebookDictionary.parseCsv(artifact.codebookCsv),
                ),
                embeddingModel = DjlMemoryEmbeddingModel.fromModelPaths(
                    textModelPath = requiredModelPath("OPENEDEN_DJL_TEXT_MODEL_PATH"),
                    emotionalModelPath = requiredModelPath("OPENEDEN_DJL_EMOTIONAL_MODEL_PATH"),
                    textModelName = System.getenv("OPENEDEN_DJL_MODEL_NAME") ?: "model",
                    emotionalModelName = System.getenv("OPENEDEN_DJL_MODEL_NAME") ?: "model",
                    engineName = System.getenv("OPENEDEN_DJL_ENGINE") ?: "PyTorch",
                    textInputDimension = artifact.textEmbedding.bucketSize,
                ),
            )
        }
        else -> error("Unsupported OPENEDEN_MODEL_BACKEND")
    }
}

private fun requiredModelPath(envName: String): Path {
    val configured = System.getenv(envName)?.takeIf { it.isNotBlank() }?.let(Path::of)
    val path = configured ?: when (envName) {
        "OPENEDEN_DJL_VQVAE_MODEL_PATH" -> resolveFromRoot(Path.of("data", "models", "djl", "vqvae"))
        "OPENEDEN_DJL_TEXT_MODEL_PATH" -> resolveFromRoot(Path.of("data", "models", "djl", "text"))
        "OPENEDEN_DJL_EMOTIONAL_MODEL_PATH" -> resolveFromRoot(Path.of("data", "models", "djl", "emotional"))
        else -> error("$envName is required when OPENEDEN_MODEL_BACKEND=djl")
    }
    require(Files.exists(path)) { "$envName does not point to a model path: $path" }
    return path
}

private fun resolveHeartbeatOwner(): HeartbeatOwner? {
    val platform = System.getenv("OPENEDEN_OWNER_PLATFORM")?.takeIf { it.isNotBlank() }
    val userId = System.getenv("OPENEDEN_OWNER_USER_ID")?.takeIf { it.isNotBlank() }
    return if (platform != null && userId != null) HeartbeatOwner(platform, userId) else null
}

/** Walk up from the working dir to find a project-relative path, falling back to the relative path. */
private fun resolveFromRoot(relative: Path): Path {
    var current: Path? = Path.of("").toAbsolutePath()
    repeat(6) {
        val dir = current ?: return relative
        val candidate = dir.resolve(relative)
        if (Files.exists(candidate) || Files.exists(dir.resolve("settings.gradle.kts"))) return candidate
        current = dir.parent
    }
    return relative
}
