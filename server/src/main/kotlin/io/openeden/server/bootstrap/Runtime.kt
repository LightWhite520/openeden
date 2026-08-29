package io.openeden.server.bootstrap

import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaFileLoader
import io.openeden.identity.CanonicalSubjectId
import io.openeden.identity.CanonicalSubjectResolver
import io.openeden.runtime.pipeline.DevelopmentMessagePipeline
import io.openeden.runtime.heartbeat.HeartbeatScheduler
import io.openeden.runtime.heartbeat.HeartbeatOwner
import io.openeden.runtime.heartbeat.NoopHeartbeatDelivery
import io.openeden.runtime.inference.JvmInferenceExecutor
import io.openeden.runtime.heartbeat.OwnerHeartbeatRouteResolver
import io.openeden.runtime.state.RuntimeConfig
import io.openeden.runtime.tick.RuntimeTickScheduler
import io.openeden.runtime.heartbeat.SecureRandomHeartbeatInterval
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
import io.openeden.relationship.DeterministicRelationshipEventEvaluator
import io.openeden.relationship.FallbackRelationshipEventEvaluator
import io.openeden.relationship.HostIdentity
import io.openeden.relationship.OpenAiRelationshipEventEvaluator
import io.openeden.relationship.RelationshipRoleResolver
import io.openeden.memory.MemoryEntry
import io.openeden.memory.MemoryKind
import io.openeden.memory.MemoryMetadata
import io.openeden.memory.MemoryRoom
import io.openeden.memory.RebuildableInMemoryVectorIndex
import io.openeden.bio.BioVector
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.llm.OpenAiCacheKeyContext
import io.openeden.llm.OpenAiCachePolicy
import io.openeden.llm.OpenAiCapabilityProbe
import io.openeden.llm.OpenAiCapabilityCache
import io.openeden.llm.CachedOpenAiCapabilityProvider
import io.openeden.llm.OpenAiCapabilityProvider
import io.openeden.llm.OpenAiProviderCapabilities
import io.openeden.llm.ReasoningEffort
import io.openeden.server.persistence.sqldelight.SqlDelightDiaryTaskStore
import io.openeden.server.persistence.sqldelight.SqlDelightMemoryRepository
import io.openeden.server.persistence.sqldelight.MemoryVectorProjectionStore
import io.openeden.server.persistence.sqldelight.SqlDelightTraceStore
import io.openeden.server.persistence.sqldelight.SqlDelightSessionStateStore
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationStateStore
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
import io.openeden.onebot.config.OneBotConfig
import io.openeden.onebot.connection.OneBotConnectionRegistry
import io.openeden.onebot.egress.OneBotActionSender
import io.openeden.onebot.heartbeat.OneBotHeartbeatDelivery
import io.openeden.onebot.ingress.OneBotAdapter
import io.openeden.onebot.ingress.OneBotMessageHandler
import io.openeden.onebot.ingress.OneBotMessageResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/** The shared, durable-backed pipeline, published for [configureRouting] to consume. */
val PipelineKey = AttributeKey<DevelopmentMessagePipeline>("openeden.pipeline")
val SessionStateStoreKey = AttributeKey<SessionStateStore>("openeden.session-state-store")
val TranscriptStoreKey = AttributeKey<TranscriptStore>("openeden.transcript-store")
val VectorDatabaseStatusKey = AttributeKey<VectorDatabaseStatusProvider>("openeden.vector-database-status")
val IncarnationTerminationCoordinatorKey = AttributeKey<IncarnationTerminationCoordinator>("openeden.incarnation-termination-coordinator")
val OneBotAdapterKey = AttributeKey<OneBotAdapter>("openeden.onebot-adapter")
val OpenAiCapabilityProviderKey = AttributeKey<OpenAiCapabilityProvider>("openeden.openai-capability-provider")

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
    val canonicalSubjectResolver = loadCanonicalSubjectResolver(environment.config)
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
    val incarnationStore = persistenceIo.open {
        SqlDelightIncarnationStateStore.open(
            serverConfig.runtimeDbPath,
            committedTranscriptStore = transcriptStore,
        )
    }
    startupClosers.addFirst { incarnationStore.shutdown() }
    val relationshipStore = SqlDelightRelationshipStateStore.open(serverConfig.runtimeDbPath)
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
    startupClosers.addFirst { inferenceExecutor.close() }
    val runtimeConfig = RuntimeConfig.Default.copy(owner = serverConfig.heartbeatOwner)
    val backgroundDynamicsFactory = IncarnationBackgroundDynamicsReducerFactory(runtimeConfig.omega)
    val writer = VectorWriteService(
        incarnationStore = incarnationStore,
        inferenceExecutor = inferenceExecutor,
        backgroundDynamicsReducerFactory = backgroundDynamicsFactory::create,
    )
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
            inferenceExecutor = inferenceExecutor,
            canonicalSubjectResolver = canonicalSubjectResolver,
        )
    }
    startupClosers.addFirst { memoryStore.close() }
    val capabilityProvider: OpenAiCapabilityProvider = if (serverConfig.openAiCache.capabilityProbeEnabled) {
        val capabilityCache = OpenAiCapabilityCache()
        val capabilityProbe = OpenAiCapabilityProbe(
            apiKey = serverConfig.apiKey,
            baseUrl = serverConfig.baseUrl,
            model = serverConfig.model,
            routingFingerprint = serverConfig.capabilityProbeRoutingFingerprint,
            ttlMs = serverConfig.capabilityProbeTtlSeconds * 1_000L,
        )
        startupClosers.addFirst { capabilityProbe.close() }
        CachedOpenAiCapabilityProvider(capabilityCache, capabilityProbe.cacheKey, capabilityProbe::probe)
    } else {
        OpenAiCapabilityProvider { OpenAiProviderCapabilities.unavailable(System.currentTimeMillis()) }
    }
    attributes.put(OpenAiCapabilityProviderKey, capabilityProvider)
    if (serverConfig.openAiCache.capabilityProbeEnabled) capabilityProvider.capabilities()
    val llmClient = OpenAiResponsesLlmClient(
        apiKey = serverConfig.apiKey,
        model = serverConfig.model,
        reasoningEffort = serverConfig.reasoningEffort,
        baseUrl = serverConfig.baseUrl,
        cachePolicy = serverConfig.openAiCache.policy,
        capabilityProvider = capabilityProvider,
        cacheKeyContext = OpenAiCacheKeyContext(
            providerPolicyRevision = serverConfig.cacheProviderPolicyRevision,
            systemSchemaRevision = serverConfig.cacheSystemSchemaRevision,
            personaRevision = "${serverConfig.cachePersonaRevision}:${persona.startSubState.name}",
            dialogueNamespace = serverConfig.cacheDialogueNamespace,
        ),
        defaultGenerationSettings = staticGenerationSettings,
    )
    startupClosers.addFirst { llmClient.close() }
    val relationshipEvaluatorClient = relationshipEvaluatorHttpClient()
    startupClosers.addFirst { relationshipEvaluatorClient.close() }
    val relationshipEventEvaluator = FallbackRelationshipEventEvaluator(
        primary = OpenAiRelationshipEventEvaluator(
            apiKey = serverConfig.apiKey,
            model = serverConfig.model,
            baseUrl = serverConfig.baseUrl,
            httpClient = relationshipEvaluatorClient,
        ),
        fallback = DeterministicRelationshipEventEvaluator(),
    )
    val diaryCoordinator = DiaryTriggerCoordinator(
        diaryTaskStore, diaryTaskStore, memoryStore,
        DiaryTriggerConfig(serverConfig.diaryDeltaThreshold, serverConfig.diaryElapsedHours * 60L * 60L * 1000L),
    )
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = persona, llmClient = llmClient,
        llmGenerationPolicyConfig = serverConfig.llmGenerationPolicy,
        store = store,
        incarnationStateStore = incarnationStore,
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
        relationshipEventEvaluator = relationshipEventEvaluator,
        userAffectAnalyzer = models.userAffectAnalyzer,
        diaryTriggerCoordinator = diaryCoordinator,
        transcriptStore = transcriptStore,
        lifecycleGate = lifecycleGate,
        canonicalSubjectResolver = canonicalSubjectResolver,
    )
    attributes.put(PipelineKey, pipeline)
    attributes.put(SessionStateStoreKey, store)
    attributes.put(TranscriptStoreKey, transcriptStore)
    attributes.put(DiagnosticsAccessKey, serverConfig.diagnosticsAccess)

    val applicationContext = this.coroutineContext
    val runtimeJob = SupervisorJob(requireNotNull(applicationContext[Job]))
    val scope = CoroutineScope(applicationContext + runtimeJob + Dispatchers.IO)
    startupClosers.addFirst { runtimeJob.cancelAndJoin() }
    val oneBotAdapter = createOneBotAdapter(serverConfig.oneBot, pipeline, scope)
    if (oneBotAdapter != null) {
        attributes.put(OneBotAdapterKey, oneBotAdapter)
        startupClosers.addFirst { oneBotAdapter.shutdown() }
    }
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
            CheckpointedDiaryDataSource(
                diaryTaskStore,
                memoryStore,
                { session, after, through, limit ->
                    memoryStore.rawMemoryRange(session, after, through, minOf(limit, serverConfig.diaryMaxRawMemories)).map { entry ->
                        io.openeden.memory.MemorySnippet(
                            id = entry.id,
                            content = entry.content,
                            metadata = entry.metadata,
                            createdAtMs = entry.createdAtMs,
                        )
                    }
                },
                { id -> memoryStore.readById(id)?.entry },
            ), models.quantizer, inferenceExecutor, llmClient, models.embeddingModel, serverConfig.diaryMaxRawMemories,
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
            kotlinx.coroutines.delay(serverConfig.diaryScanIntervalMs.milliseconds)
        }
    }
    val scheduler = HeartbeatScheduler(
        pipeline = pipeline,
        store = store,
        writer = writer,
        delivery = oneBotAdapter?.let { OneBotHeartbeatDelivery(it.registry, it.actions) }
            ?: NoopHeartbeatDelivery,
        interval = SecureRandomHeartbeatInterval(),
        routeResolver = OwnerHeartbeatRouteResolver(runtimeConfig.owner),
        onDeliveryDropped = { sessionId, target, failure ->
            log.warn(
                "onebot=HEARTBEAT_DROPPED session=$sessionId platform=${target.platform} user=${target.userId}",
                failure,
            )
        },
        incarnationStore = incarnationStore,
        transcriptStore = transcriptStore,
    )
    val heartbeatJob = scheduler.start(scope)
    val tickJob = RuntimeTickScheduler(
        store = store,
        writer = writer,
        inferenceExecutor = inferenceExecutor,
        config = runtimeConfig,
        startedAtMs = 0L,
        onOmegaCritical = { lifecycleRepository.markCritical() },
        incarnationStore = incarnationStore,
        transcriptStore = transcriptStore,
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
        incarnationStore = incarnationStore,
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
            { oneBotAdapter?.shutdown() },
            { relationshipEvaluatorClient.close() },
            { llmClient.close() },
            { models.close() },
            { inferenceExecutor.close() },
        ),
    )
    monitor.subscribe(ApplicationStopping) {
        shutdown.stopping()
    }
    // Ktor raises ApplicationStopped only after disposeAndJoin has waited for Application children.
    // Close database resources on their owning dispatchers without blocking the event callback.
    val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    monitor.subscribe(ApplicationStopped) {
        shutdownScope.launch {
            shutdown.stopped()?.let { failure ->
                log.error("One or more OpenEden runtime resources failed to close", failure)
            }
            shutdownScope.cancel()
            log.info("OpenEden runtime stopped")
        }
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
    val openAiCache: OpenAiCacheBootstrapConfig,
    val cacheProviderPolicyRevision: String,
    val cacheSystemSchemaRevision: String,
    val cachePersonaRevision: String,
    val cacheDialogueNamespace: String,
    val capabilityProbeRoutingFingerprint: String,
    val capabilityProbeTtlSeconds: Long,
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
    val oneBot: OneBotConfig,
)

internal fun loadOpenAiCachePolicy(
    config: io.ktor.server.config.ApplicationConfig,
): OpenAiCachePolicy = OpenAiCachePolicy.parse(
    config.propertyOrNull("openeden.llm.promptCachingPolicy")
        ?.getString()
        ?.takeIf { it.isNotBlank() }
        ?: "relay_append_only",
)

private fun loadServerRuntimeConfig(config: io.ktor.server.config.ApplicationConfig): ServerRuntimeConfig {
    fun required(path: String): String = config.property(path).getString()
    fun optional(path: String, default: String): String =
        config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: default
    fun rootPath(path: String, default: String): Path =
        resolveFromRoot(Path.of(optional(path, default)))
    val diagnosticsEnabled = optional("openeden.diagnostics.enabled", "false").equals("true", ignoreCase = true)
    val diagnosticsToken = config.propertyOrNull("openeden.diagnostics.token")?.getString()
        ?.takeIf { it.isNotBlank() }
    val hostIdentity = loadHostIdentity(config)
    val oneBot = loadOneBotConfig(config)
    val heartbeatOwner = loadHeartbeatOwner(config)
    validateOneBotHeartbeatOwner(oneBot.enabled, heartbeatOwner)
    val openAiCache = loadOpenAiCacheBootstrapConfig(config)
    return ServerRuntimeConfig(
        apiKey = required("openeden.llm.apiKey"),
        model = required("openeden.llm.model"),
        reasoningEffort = ReasoningEffort.parse(optional("openeden.llm.reasoningEffort", "medium")),
        baseUrl = required("openeden.llm.baseUrl"),
        openAiCache = openAiCache,
        cacheProviderPolicyRevision = optional("openeden.llm.cache.providerPolicyRevision", "responses-v1"),
        cacheSystemSchemaRevision = optional("openeden.llm.cache.systemSchemaRevision", "openeden-output-schema-v1"),
        cachePersonaRevision = optional("openeden.llm.cache.personaRevision", "persona-v1"),
        cacheDialogueNamespace = optional("openeden.llm.cache.dialogueNamespace", "openeden-dialogue-v1"),
        capabilityProbeRoutingFingerprint = optional("openeden.llm.capabilityProbe.routingFingerprint", "default"),
        capabilityProbeTtlSeconds = optional("openeden.llm.capabilityProbe.ttlSeconds", "900")
            .toLong().coerceAtLeast(0L),
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
        heartbeatOwner = heartbeatOwner,
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
        oneBot = oneBot,
    )
}

internal fun relationshipEvaluatorHttpClient(
    engine: HttpClientEngine = CIO.create(),
    requestTimeoutMillis: Long = 30_000L,
): HttpClient = HttpClient(engine) {
    install(HttpTimeout) {
        this.requestTimeoutMillis = requestTimeoutMillis
        connectTimeoutMillis = 10_000L
        socketTimeoutMillis = requestTimeoutMillis
    }
    install(ContentNegotiation) { json() }
}

private fun loadCanonicalSubjectResolver(
    config: io.ktor.server.config.ApplicationConfig,
): CanonicalSubjectResolver {
    val bindings = config.propertyOrNull("openeden.identity.subjectBindings")?.getList().orEmpty().associate { binding ->
        val parts = binding.split("=", limit = 2)
        require(parts.size == 2 && parts[1].isNotBlank()) { "Invalid canonical subject binding" }
        val separator = parts[0].indexOf(':')
        require(separator > 0 && separator < parts[0].lastIndex) { "Invalid canonical subject binding" }
        CanonicalSubjectResolver.PlatformUser(parts[0].substring(0, separator), parts[0].substring(separator + 1)) to
            CanonicalSubjectId(parts[1])
    }
    return CanonicalSubjectResolver(bindings)
}

private fun createOneBotAdapter(
    config: OneBotConfig,
    pipeline: DevelopmentMessagePipeline,
    scope: CoroutineScope,
): OneBotAdapter? {
    if (!config.enabled) return null
    val registry = OneBotConnectionRegistry(config.botSelfId)
    val actions = OneBotActionSender(
        registry = registry,
        timeoutMs = config.actionTimeoutMs,
        maxRetries = config.maxActionRetries,
    )
    return OneBotAdapter(
        config = config,
        registry = registry,
        actions = actions,
        handler = OneBotMessageHandler { request ->
            pipeline.handle(request).let { result ->
                OneBotMessageResult(result.response.takeIf { result.validationErrors.isEmpty() })
            }
        },
        scope = scope,
        onTrace = { tag ->
            org.slf4j.LoggerFactory.getLogger("io.openeden.onebot").info("OneBot trace tag={}", tag)
        },
    )
}
