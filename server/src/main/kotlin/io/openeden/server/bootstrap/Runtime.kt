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
import io.openeden.server.api.route.DiagnosticsAccess
import io.openeden.server.api.route.DiagnosticsAccessKey
import io.openeden.codebook.CodebookDictionary
import io.openeden.codebook.CodebookQuantizer
import io.openeden.codebook.DjlVqVaeCodebookModelRunner
import io.openeden.codebook.VqVaeCodebookQuantizer
import io.openeden.memory.DjlMemoryEmbeddingModel
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.relationship.UserAffectAnalyzer
import io.openeden.relationship.DjlTextAffectAnalyzer
import io.openeden.relationship.HostIdentity
import io.openeden.relationship.RelationshipRoleResolver
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.bio.BioVector
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.llm.ReasoningEffort
import io.openeden.server.persistence.sqldelight.SqlDelightDiaryTaskStore
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore
import io.openeden.server.persistence.sqldelight.SqlDelightTraceStore
import io.openeden.server.persistence.sqldelight.SqlDelightSessionStateStore
import io.openeden.server.persistence.sqldelight.SqlDelightRelationshipStateStore
import io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStore
import io.openeden.server.vector.QdrantCircuitBreaker
import io.openeden.server.vector.QdrantProjectionSynchronizer
import io.openeden.server.vector.ResilientVectorIndex
import io.openeden.server.vector.asProjectionWorkStore
import io.openeden.server.vector.qdrant.QdrantClient
import io.openeden.server.vector.qdrant.QdrantCollectionNaming
import io.openeden.server.vector.qdrant.QdrantVectorIndex
import io.openeden.server.vector.VectorDatabaseStatusProvider
import io.openeden.server.vector.VectorDatabaseStatus
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationLifecycleRepository
import io.openeden.server.runtime.IncarnationTerminationCoordinator
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.lifecycle.IncarnationLifecycleGate
import io.openeden.runtime.lifecycle.TerminationReason
import io.openeden.transcript.TranscriptStore
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** The shared, durable-backed pipeline, published for [configureRouting] to consume. */
val PipelineKey = AttributeKey<DevelopmentMessagePipeline>("openeden.pipeline")
val SessionStateStoreKey = AttributeKey<SessionStateStore>("openeden.session-state-store")
val TranscriptStoreKey = AttributeKey<TranscriptStore>("openeden.transcript-store")
val VectorDatabaseStatusKey = AttributeKey<VectorDatabaseStatusProvider>("openeden.vector-database-status")
val IncarnationTerminationCoordinatorKey = AttributeKey<IncarnationTerminationCoordinator>("openeden.incarnation-termination-coordinator")

/**
 * Boots the persona runtime: durable SQLite store → pipeline → heartbeat scheduler. Runs before
 * [configureRouting] (see application.yaml) so the route reads the shared pipeline from attributes.
 * The scheduler runs on its own dispatcher (§9.3.3) and is torn down on application stop.
 */
suspend fun Application.configureRuntime() {
    val startupClosers = ArrayDeque<suspend () -> Unit>()
    try {
        startRuntime(startupClosers)
    } catch (failure: Throwable) {
        withContext(NonCancellable) {
            startupClosers.forEach { close ->
                try {
                    close()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }
        throw failure
    }
}

private suspend fun Application.startRuntime(
    startupClosers: ArrayDeque<suspend () -> Unit>,
) {
    val serverConfig = loadServerRuntimeConfig(environment.config)
    val staticGenerationSettings = serverConfig.llmGenerationPolicy.staticSettings()
    val persona = PersonaFileLoader.load(serverConfig.personaPath)
    val persistenceIo = PersistenceStartupIo()
    val transcriptStore = SqlDelightTranscriptStore.open(serverConfig.runtimeDbPath)
    startupClosers.addFirst { transcriptStore.close() }
    val lifecycleRepository = persistenceIo.open {
        SqlDelightIncarnationLifecycleRepository.open(serverConfig.runtimeDbPath)
    }
    startupClosers.addFirst { lifecycleRepository.close() }
    val lifecycleGate = IncarnationLifecycleGate()
    if (lifecycleRepository.read() == IncarnationLifecycle.TERMINATING) {
        IncarnationTerminationCoordinator(lifecycleGate, lifecycleRepository).terminate(
            TerminationReason("startup-recovery", System.currentTimeMillis()),
        )
    } else if (lifecycleRepository.read() == IncarnationLifecycle.TERMINATED) {
        lifecycleGate.beginTermination()
        lifecycleGate.markTerminated()
    }
    val store = persistenceIo.open {
        SqlDelightSessionStateStore.open(
            serverConfig.runtimeDbPath,
            persona.mode,
            persona.startSubState,
            committedTranscriptStore = transcriptStore,
        )
    }
    startupClosers.addFirst { store.close() }
    val relationshipStore = persistenceIo.open {
        SqlDelightRelationshipStateStore.open(serverConfig.runtimeDbPath)
    }
    startupClosers.addFirst { relationshipStore.close() }
    val diaryTaskStore = persistenceIo.open {
        SqlDelightDiaryTaskStore.open(serverConfig.runtimeDbPath)
    }
    startupClosers.addFirst { diaryTaskStore.close() }
    val traceStore = persistenceIo.open {
        SqlDelightTraceStore.open(serverConfig.runtimeDbPath)
    }
    startupClosers.addFirst { traceStore.close() }
    val inferenceExecutor = JvmInferenceExecutor()
    // One VectorWriteService shared by the pipeline and the scheduler so all per-session writes
    // (user deltas + shock-heartbeat latch) serialize on the same Mutex registry (§14.2).
    val writer = VectorWriteService(store, inferenceExecutor = inferenceExecutor)
    val runtimeConfig = RuntimeConfig.Default.copy(owner = serverConfig.heartbeatOwner)
    val models = loadRuntimeModels(serverConfig)
    startupClosers.addFirst { models.close() }
    val projectionStore = persistenceIo.open {
        MemoryVectorProjectionStore.open(serverConfig.runtimeDbPath)
    }
    startupClosers.addFirst { projectionStore.close() }
    val fallbackIndex = RebuildableInMemoryVectorIndex(inferenceExecutor)
    var projectionWake: () -> Unit = {}
    val qdrantRuntime = if (serverConfig.vectorDatabase.enabled) {
        val qdrantClient = QdrantClient(
            baseUrl = serverConfig.vectorDatabase.url,
            apiKey = serverConfig.vectorDatabase.apiKey,
            timeoutMillis = serverConfig.vectorDatabase.requestTimeoutMs,
        )
        startupClosers.addFirst { qdrantClient.close() }
        val naming = QdrantCollectionNaming(serverConfig.vectorDatabase.collectionPrefix)
        val circuit = QdrantCircuitBreaker(
            failureThreshold = serverConfig.vectorDatabase.failureThreshold,
            probeIntervalMs = serverConfig.vectorDatabase.syncIntervalMs,
        )
        val primary = QdrantVectorIndex(
            client = qdrantClient,
            naming = naming,
            modelId = serverConfig.vectorDatabase.modelId,
            onCollectionRecreated = {
                while (projectionStore.requeueReady(
                    serverConfig.vectorDatabase.modelId,
                    System.currentTimeMillis(),
                    serverConfig.vectorDatabase.syncBatchSize,
                ).isNotEmpty()) Unit
            },
            onTrace = { tag -> log.info("Qdrant trace tag: $tag") },
        )
        QdrantRuntime(
            client = qdrantClient,
            primary = primary,
            circuit = circuit,
            collection = naming.collectionName(serverConfig.vectorDatabase.modelId),
        )
    } else {
        null
    }
    val resilientIndex = qdrantRuntime?.resilientIndex(fallbackIndex)
    lateinit var projectionSynchronizer: QdrantProjectionSynchronizer
    val memoryStore = persistenceIo.open {
        SqlDelightMemoryRepository.open(
            dbPath = serverConfig.runtimeDbPath,
            embeddingModel = models.embeddingModel,
            activeModelId = serverConfig.vectorDatabase.modelId,
            projectionWake = { projectionWake() },
            index = resilientIndex,
            fallbackIndex = fallbackIndex,
        )
    }
    startupClosers.addFirst { memoryStore.close() }
    val llmClient = OpenAiResponsesLlmClient(
        apiKey = serverConfig.apiKey, model = serverConfig.model,
        reasoningEffort = serverConfig.reasoningEffort, baseUrl = serverConfig.baseUrl,
        defaultGenerationSettings = staticGenerationSettings,
    )
    startupClosers.addFirst { llmClient.close() }
    val diaryCoordinator = DiaryTriggerCoordinator(
        diaryTaskStore, diaryTaskStore, memoryStore,
        DiaryTriggerConfig(serverConfig.diaryDeltaThreshold, serverConfig.diaryElapsedHours * 60L * 60L * 1000L),
    )
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = persona, llmClient = llmClient,
        llmGenerationPolicyConfig = serverConfig.llmGenerationPolicy,
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = inferenceExecutor,
        memoryStore = memoryStore,
        quantizer = models.quantizer,
        memoryEmbeddingModel = models.embeddingModel,
        diaryTaskStore = diaryTaskStore,
        traceStore = traceStore,
        relationshipStore = relationshipStore,
        relationshipRoleResolver = RelationshipRoleResolver(
            host = serverConfig.hostIdentity,
            hostAddress = serverConfig.hostAddress,
        ),
        userAffectAnalyzer = models.userAffectAnalyzer,
        diaryTriggerCoordinator = diaryCoordinator,
        transcriptStore = transcriptStore,
        lifecycleGate = lifecycleGate,
    )
    attributes.put(PipelineKey, pipeline)
    attributes.put(SessionStateStoreKey, store)
    attributes.put(TranscriptStoreKey, transcriptStore)
    attributes.put(DiagnosticsAccessKey, serverConfig.diagnosticsAccess)

    val applicationContext = this.coroutineContext
    val runtimeJob = SupervisorJob(requireNotNull(applicationContext[Job]))
    val scope = CoroutineScope(applicationContext + runtimeJob + Dispatchers.IO)
    startupClosers.addFirst { runtimeJob.cancelAndJoin() }
    val projectionJob = qdrantRuntime?.let { runtime ->
        projectionSynchronizer = QdrantProjectionSynchronizer(
            store = projectionStore.asProjectionWorkStore(),
            index = runtime.primary,
            loadEntry = { id -> memoryStore.readById(id)?.takeIf { it.modelId == serverConfig.vectorDatabase.modelId }?.entry },
            modelId = serverConfig.vectorDatabase.modelId,
            intervalMs = serverConfig.vectorDatabase.syncIntervalMs,
            batchSize = serverConfig.vectorDatabase.syncBatchSize,
            circuit = runtime.circuit,
            onCollectionLoss = { runtime.primary.markDirty() },
            onTrace = { tag -> log.info("Qdrant projection trace tag: $tag") },
        )
        projectionWake = projectionSynchronizer::signal
        projectionSynchronizer.start(scope)
    }
    attributes.put(
        VectorDatabaseStatusKey,
        VectorDatabaseStatusProvider {
            val counts = projectionStore.projectionCounts(System.currentTimeMillis())
            val status = resilientIndex?.status() ?: VectorDatabaseStatus(
                backend = "IN_MEMORY",
                circuit = QdrantCircuitBreaker.Snapshot(
                    state = QdrantCircuitBreaker.State.CLOSED,
                    consecutiveFailures = 0,
                    openedAtMs = null,
                    lastSuccessAtMs = null,
                    lastFailure = null,
                ),
                fallbackActive = true,
                lastTraceTag = "vector_db=IN_MEMORY",
            )
            status.copy(
                pendingProjectionCount = counts.duePending,
                totalNonReadyProjectionCount = counts.nonReady,
            )
        },
    )
    val modelRefreshJob = scope.launch {
        try {
            memoryStore.refreshOutdatedEmbeddings(
                inferenceExecutor = inferenceExecutor,
                batchSize = serverConfig.vectorDatabase.syncBatchSize,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            log.error("Unable to refresh outdated memory embeddings", failure)
        }
    }
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
            generationSettings = staticGenerationSettings,
        )::generate),
    )
    val diaryWorkerJob = DiaryWorkerScheduler(
        taskStore = diaryTaskStore,
        worker = diaryWorker,
        sessionIds = { store.sessionIds() },
        nowMs = { System.currentTimeMillis() },
    ).start(scope)
    val elapsedDiaryJob = scope.launch {
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
    val heartbeatJob = scheduler.start(scope)
    val tickJob = RuntimeTickScheduler(
        store = store,
        writer = writer,
        fluctuation = SineWaveFluctuationEngine(SecureRandomSineWaveFluctuation.profile()),
        inferenceExecutor = inferenceExecutor,
        config = runtimeConfig,
        onOmegaCritical = { lifecycleRepository.markCritical() },
    ).start(scope)
    val terminationCoordinator = IncarnationTerminationCoordinator(
        gate = lifecycleGate,
        store = lifecycleRepository,
        runtimeJobs = listOfNotNull(diaryWorkerJob, elapsedDiaryJob, heartbeatJob, tickJob, projectionJob, modelRefreshJob),
    )
    attributes.put(IncarnationTerminationCoordinatorKey, terminationCoordinator)
    log.info("OpenEden heartbeat scheduler started")

    val shutdown = RuntimeShutdownCoordinator(
        runtimeJob = runtimeJob,
        closers = listOf(
            { lifecycleRepository.close() },
            { transcriptStore.close() },
            { store.close() },
            { memoryStore.close(); Unit },
            { qdrantRuntime?.client?.close() },
            { projectionStore.close() },
            { diaryTaskStore.close() },
            { traceStore.close() },
            { relationshipStore.close() },
            { llmClient.close() },
            { models.close() },
        ),
    )
    monitor.subscribe(ApplicationStopping) {
        shutdown.stopping()
    }
    // Ktor raises ApplicationStopped only after disposeAndJoin has waited for Application children.
    // These synchronous closes release already-quiesced resources; they perform no query or inference.
    monitor.subscribe(ApplicationStopped) {
        shutdown.stopped()?.let { failure ->
            log.error("One or more OpenEden runtime resources failed to close", failure)
        }
        log.info("OpenEden runtime stopped")
    }
    startupClosers.clear()
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
) : AutoCloseable {
    override fun close() {
        closeBestEffort(closers.asReversed().map { closeable -> closeable::close })
            ?.let { throw it }
    }
}

private data class QdrantRuntime(
    val client: QdrantClient,
    val primary: QdrantVectorIndex,
    val circuit: QdrantCircuitBreaker,
    val collection: String,
) {
    fun resilientIndex(fallback: RebuildableInMemoryVectorIndex): ResilientVectorIndex =
        ResilientVectorIndex(
            primary = primary,
            fallback = fallback,
            circuit = circuit,
            collection = collection,
        )
}

private fun loadRuntimeModels(config: ServerRuntimeConfig): RuntimeModels {
    val artifact = requireNotNull(loadLocalArtifact(config)) {
        "Local model artifact is required for server startup"
    }
    return when (config.modelBackend.lowercase()) {
        "artifact" -> RuntimeModels(artifact.codebookQuantizer(), artifact.memoryEmbeddingModel(), artifact.userAffectAnalyzer())
        "djl" -> withPreparedThymosRuntime {
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
    val llmGenerationPolicy: LlmGenerationPolicyConfig,
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
    val hostIdentity: HostIdentity?,
    val hostAddress: String?,
    val diaryDeltaThreshold: Float,
    val diaryElapsedHours: Long,
    val diaryScanIntervalMs: Long,
    val diaryMaxRawMemories: Int,
    val diagnosticsAccess: DiagnosticsAccess,
    val vectorDatabase: VectorDatabaseConfig,
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
    val diagnosticsEnabled = optional("openeden.diagnostics.enabled", "false").equals("true", ignoreCase = true)
    val diagnosticsToken = config.propertyOrNull("openeden.diagnostics.token")?.getString()
        ?.takeIf { it.isNotBlank() }
    val hostIdentity = loadHostIdentity(config)
    return ServerRuntimeConfig(
        apiKey = required("openeden.llm.apiKey"),
        model = required("openeden.llm.model"),
        reasoningEffort = ReasoningEffort.parse(optional("openeden.llm.reasoningEffort", "medium")),
        baseUrl = required("openeden.llm.baseUrl"),
        llmGenerationPolicy = loadLlmGenerationPolicyConfig(config),
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
        hostIdentity = hostIdentity,
        hostAddress = loadHostAddress(config, hostIdentity),
        diaryDeltaThreshold = optional("openeden.diary.deltaThreshold", "0.25").toFloat(),
        diaryElapsedHours = optional("openeden.diary.elapsedHours", "5").toLong(),
        diaryScanIntervalMs = optional("openeden.diary.scanIntervalSeconds", "60").toLong().coerceAtLeast(1L) * 1000L,
        diaryMaxRawMemories = optional("openeden.diary.maxRawMemories", "32").toInt().coerceAtLeast(1),
        diagnosticsAccess = if (diagnosticsEnabled) {
            DiagnosticsAccess.enabled(diagnosticsToken.orEmpty())
        } else {
            DiagnosticsAccess.disabled()
        },
        vectorDatabase = loadVectorDatabaseConfig(config),
    )
}
