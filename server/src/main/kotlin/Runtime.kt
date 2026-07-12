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
import io.openeden.runtime.SessionStateStore
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
import io.openeden.relationship.UserAffectAnalyzer
import io.openeden.relationship.DjlTextAffectAnalyzer
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.bio.BioVector
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.llm.ReasoningEffort
import io.openeden.server.db.SqlDelightDiaryTaskStore
import io.openeden.server.db.SqlDelightMemoryRepository
import io.openeden.server.db.SqlDelightTraceStore
import io.openeden.server.db.SqlDelightSessionStateStore
import io.openeden.server.db.SqlDelightRelationshipStateStore
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
val SessionStateStoreKey = AttributeKey<SessionStateStore>("openeden.session-state-store")

/**
 * Boots the persona runtime: durable SQLite store → pipeline → heartbeat scheduler. Runs before
 * [configureRouting] (see application.yaml) so the route reads the shared pipeline from attributes.
 * The scheduler runs on its own dispatcher (§9.3.3) and is torn down on application stop.
 */
fun Application.configureRuntime() {
    val serverConfig = loadServerRuntimeConfig(environment.config)
    val store = SqlDelightSessionStateStore.open(serverConfig.runtimeDbPath)
    val relationshipStore = SqlDelightRelationshipStateStore.open(serverConfig.runtimeDbPath)
    val diaryTaskStore = SqlDelightDiaryTaskStore.open(serverConfig.runtimeDbPath)
    val traceStore = SqlDelightTraceStore.open(serverConfig.runtimeDbPath)
    // One VectorWriteService shared by the pipeline and the scheduler so all per-session writes
    // (user deltas + shock-heartbeat latch) serialize on the same Mutex registry (§14.2).
    val writer = VectorWriteService(store)
    val runtimeConfig = RuntimeConfig.Default.copy(owner = serverConfig.heartbeatOwner)
    val inferenceExecutor = JvmInferenceExecutor()
    val models = loadRuntimeModels(serverConfig)
    val memoryStore = SqlDelightMemoryRepository.open(serverConfig.runtimeDbPath, models.embeddingModel)
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = PersonaFileLoader.load(serverConfig.personaPath),
        llmClient = OpenAiResponsesLlmClient(
            apiKey = serverConfig.apiKey,
            model = serverConfig.model,
            reasoningEffort = serverConfig.reasoningEffort,
            baseUrl = serverConfig.baseUrl,
        ),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = inferenceExecutor,
        memoryStore = memoryStore,
        quantizer = models.quantizer,
        memoryEmbeddingModel = models.embeddingModel,
        diaryTaskStore = diaryTaskStore,
        traceStore = traceStore,
        relationshipStore = relationshipStore,
        userAffectAnalyzer = models.userAffectAnalyzer,
    )
    attributes.put(PipelineKey, pipeline)
    attributes.put(SessionStateStoreKey, store)

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
        relationshipStore.close()
        log.info("OpenEden runtime stopped")
    }
}

internal fun loadDefaultPersonaConfig(): PersonaConfig =
    PersonaFileLoader.load(resolveFromRoot(Path.of("persona", "atri.yaml")))

private fun loadLocalArtifact(config: ServerRuntimeConfig) = runCatching {
    val path = config.localModelArtifactPath
    if (Files.exists(path)) LocalModelArtifactLoader.read(path) else null
}.getOrNull()

private data class RuntimeModels(
    val quantizer: CodebookQuantizer,
    val embeddingModel: MemoryEmbeddingModel,
    val userAffectAnalyzer: UserAffectAnalyzer,
)

private fun loadRuntimeModels(config: ServerRuntimeConfig): RuntimeModels {
    val artifact = requireNotNull(loadLocalArtifact(config)) {
        "Local model artifact is required for server startup"
    }
    return when (config.modelBackend.lowercase()) {
        "artifact" -> RuntimeModels(artifact.codebookQuantizer(), artifact.memoryEmbeddingModel(), artifact.userAffectAnalyzer())
        "djl" -> {
            val runner = DjlVqVaeCodebookModelRunner.fromModelPath(
                modelPath = config.djlVqVaeModelPath,
                modelName = config.djlModelName,
                engineName = config.djlEngine,
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
                    textModelPath = config.djlTextModelPath,
                    emotionalModelPath = config.djlEmotionalModelPath,
                    textModelName = config.djlModelName,
                    emotionalModelName = config.djlModelName,
                    engineName = config.djlEngine,
                    textInputDimension = artifact.textEmbedding.bucketSize,
                ),
                userAffectAnalyzer = DjlTextAffectAnalyzer.fromModelPath(
                    modelPath = config.djlAffectModelPath,
                    modelName = config.djlModelName,
                    engineName = config.djlEngine,
                    textInputDimension = artifact.textEmbedding.bucketSize,
                ),
            )
        }
        else -> error("Unsupported OPENEDEN_MODEL_BACKEND")
    }
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

private data class ServerRuntimeConfig(
    val apiKey: String,
    val model: String,
    val reasoningEffort: ReasoningEffort,
    val baseUrl: String,
    val personaPath: Path,
    val runtimeDbPath: Path,
    val localModelArtifactPath: Path,
    val modelBackend: String,
    val djlVqVaeModelPath: Path,
    val djlTextModelPath: Path,
    val djlEmotionalModelPath: Path,
    val djlAffectModelPath: Path,
    val djlEngine: String,
    val djlModelName: String,
    val heartbeatOwner: HeartbeatOwner?,
)

private fun loadServerRuntimeConfig(config: io.ktor.server.config.ApplicationConfig): ServerRuntimeConfig {
    fun required(path: String): String = config.property(path).getString()
    fun optional(path: String, default: String): String =
        config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: default
    fun rootPath(path: String, default: String): Path =
        resolveFromRoot(Path.of(optional(path, default)))
    val ownerPlatform = config.propertyOrNull("openeden.heartbeat.ownerPlatform")?.getString()
        ?.takeIf { it.isNotBlank() }
    val ownerUserId = config.propertyOrNull("openeden.heartbeat.ownerUserId")?.getString()
        ?.takeIf { it.isNotBlank() }
    return ServerRuntimeConfig(
        apiKey = required("openeden.llm.apiKey"),
        model = required("openeden.llm.model"),
        reasoningEffort = ReasoningEffort.parse(optional("openeden.llm.reasoningEffort", "medium")),
        baseUrl = required("openeden.llm.baseUrl"),
        personaPath = rootPath("openeden.runtime.personaPath", "persona/atri.yaml"),
        runtimeDbPath = rootPath("openeden.runtime.databasePath", "data/runtime/openeden.db"),
        localModelArtifactPath = rootPath("openeden.runtime.localModelArtifact", "data/models/local-model-artifact.json"),
        modelBackend = optional("openeden.runtime.modelBackend", "djl"),
        djlVqVaeModelPath = rootPath("openeden.runtime.djlVqVaeModelPath", "data/models/djl/vqvae"),
        djlTextModelPath = rootPath("openeden.runtime.djlTextModelPath", "data/models/djl/text"),
        djlEmotionalModelPath = rootPath("openeden.runtime.djlEmotionalModelPath", "data/models/djl/emotional"),
        djlAffectModelPath = rootPath("openeden.runtime.djlAffectModelPath", "data/models/djl/affect"),
        djlEngine = optional("openeden.runtime.djlEngine", "PyTorch"),
        djlModelName = optional("openeden.runtime.djlModelName", "model"),
        heartbeatOwner = if (ownerPlatform != null && ownerUserId != null) {
            HeartbeatOwner(ownerPlatform, ownerUserId)
        } else {
            null
        },
    )
}
