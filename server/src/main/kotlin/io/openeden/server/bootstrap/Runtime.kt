package io.openeden.server.bootstrap

import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.heartbeat.HeartbeatScheduler
import io.openeden.runtime.heartbeat.LoggingHeartbeatDelivery
import io.openeden.runtime.heartbeat.HeartbeatOwner
import io.openeden.runtime.inference.JvmInferenceExecutor
import io.openeden.runtime.heartbeat.OwnerHeartbeatRouteResolver
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.tick.RuntimeTickScheduler
import io.openeden.runtime.heartbeat.SecureRandomHeartbeatInterval
import io.openeden.runtime.tick.SecureRandomSineWaveFluctuation
import io.openeden.runtime.tick.SineWaveFluctuationEngine
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.session.SessionStateStore
import io.openeden.runtime.diary.DurableDiaryWorker
import io.openeden.runtime.diary.DiaryNarrativeGenerator
import io.openeden.runtime.diary.DiaryWorkerScheduler
import io.openeden.runtime.diary.DiaryTriggerCoordinator
import io.openeden.runtime.diary.DiaryTriggerConfig
import io.openeden.runtime.diary.CheckpointedDiaryDataSource
import io.openeden.runtime.diary.LlmDiaryNarrativeGenerator
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
import io.openeden.server.persistence.sqldelight.SqlDelightDiaryTaskStore
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import io.openeden.server.persistence.sqldelight.SqlDelightTraceStore
import io.openeden.server.persistence.sqldelight.SqlDelightSessionStateStore
import io.openeden.server.persistence.sqldelight.SqlDelightRelationshipStateStore
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
    val persona = PersonaFileLoader.load(serverConfig.personaPath)
    val store = SqlDelightSessionStateStore.open(
        serverConfig.runtimeDbPath,
        persona.mode,
        persona.startSubState,
    )
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
    val llmClient = OpenAiResponsesLlmClient(
        apiKey = serverConfig.apiKey, model = serverConfig.model,
        reasoningEffort = serverConfig.reasoningEffort, baseUrl = serverConfig.baseUrl,
    )
    val diaryCoordinator = DiaryTriggerCoordinator(
        diaryTaskStore, diaryTaskStore, memoryStore,
        DiaryTriggerConfig(serverConfig.diaryDeltaThreshold, serverConfig.diaryElapsedHours * 60L * 60L * 1000L),
    )
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = persona, llmClient = llmClient,
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
        diaryTriggerCoordinator = diaryCoordinator,
    )
    attributes.put(PipelineKey, pipeline)
    attributes.put(SessionStateStoreKey, store)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val diaryWorker = DurableDiaryWorker(
        taskStore = diaryTaskStore,
        memoryStore = memoryStore,
        generator = DiaryNarrativeGenerator(LlmDiaryNarrativeGenerator(
            persona, store,
            CheckpointedDiaryDataSource(diaryTaskStore, memoryStore) { session, after, through, limit ->
                memoryStore.rawMemoryRange(session, after, through, minOf(limit, serverConfig.diaryMaxRawMemories)).map { entry ->
                    io.openeden.memory.MemorySnippet(entry.id, entry.content, entry.metadata)
                }
            }, models.quantizer, inferenceExecutor, llmClient, models.embeddingModel, serverConfig.diaryMaxRawMemories,
        )::generate),
    )
    val diaryJob = DiaryWorkerScheduler(
        taskStore = diaryTaskStore,
        worker = diaryWorker,
        sessionIds = { store.sessionIds() },
        nowMs = { System.currentTimeMillis() },
    ).start(scope)
    val elapsedJob = scope.launch {
        while (true) {
            diaryCoordinator.flushElapsedSessions(System.currentTimeMillis())
            kotlinx.coroutines.delay(serverConfig.diaryScanIntervalMs)
        }
    }
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
        job.cancel(); tickJob.cancel(); diaryJob.cancel(); elapsedJob.cancel()
        scope.launch {
            joinAll(job, tickJob, diaryJob, elapsedJob)
            scope.cancel()
            store.close()
            memoryStore.close()
            diaryTaskStore.close()
            traceStore.close()
            relationshipStore.close()
            llmClient.close()
            models.close()
            log.info("OpenEden runtime stopped")
        }
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
    val closers: List<AutoCloseable> = emptyList(),
) : AutoCloseable { override fun close() = closers.asReversed().forEach { runCatching { it.close() } } }

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
                inputDimension = 8,
                codebook = artifact.vqVae.codebook,
                topK = artifact.vqVae.topK,
            )
            val embedding = DjlMemoryEmbeddingModel.fromModelPaths(
                textModelPath = config.djlTextModelPath,
                emotionalModelPath = config.djlEmotionalModelPath,
                textModelName = config.djlModelName,
                emotionalModelName = config.djlModelName,
                engineName = config.djlEngine,
                textInputDimension = artifact.textEmbedding.bucketSize,
            )
            val affect = DjlTextAffectAnalyzer.fromQwenBundle(
                bundlePath = config.djlAffectModelPath,
                engineName = config.djlEngine,
            )
            RuntimeModels(
                quantizer = VqVaeCodebookQuantizer(
                    modelRunner = runner,
                    dictionary = CodebookDictionary.parseCsv(artifact.codebookCsv),
                ),
                embeddingModel = embedding,
                userAffectAnalyzer = affect,
                closers = listOf(runner, embedding, affect),
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
    val diaryDeltaThreshold: Float,
    val diaryElapsedHours: Long,
    val diaryScanIntervalMs: Long,
    val diaryMaxRawMemories: Int,
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
        djlAffectModelPath = rootPath("openeden.runtime.djlAffectModelPath", "data/models/thymos-6d"),
        djlEngine = optional("openeden.runtime.djlEngine", "PyTorch"),
        djlModelName = optional("openeden.runtime.djlModelName", "model"),
        heartbeatOwner = if (ownerPlatform != null && ownerUserId != null) {
            HeartbeatOwner(ownerPlatform, ownerUserId)
        } else {
            null
        },
        diaryDeltaThreshold = optional("openeden.diary.deltaThreshold", "0.25").toFloat(),
        diaryElapsedHours = optional("openeden.diary.elapsedHours", "5").toLong(),
        diaryScanIntervalMs = optional("openeden.diary.scanIntervalSeconds", "60").toLong().coerceAtLeast(1L) * 1000L,
        diaryMaxRawMemories = optional("openeden.diary.maxRawMemories", "32").toInt().coerceAtLeast(1),
    )
}
