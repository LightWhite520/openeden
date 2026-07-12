package io.openeden

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.openeden.config.LocalRuntimeConfig
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.model.LocalModelArtifactLoader
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.JvmInferenceExecutor
import io.openeden.runtime.LocalRuntimeRequest
import io.openeden.runtime.LocalRuntimeResult
import io.openeden.runtime.OpenEdenRuntimePipeline
import io.openeden.runtime.SessionStateStore
import io.openeden.runtime.VectorWriteService
import io.openeden.codebook.CodebookDictionary
import io.openeden.codebook.DjlVqVaeCodebookModelRunner
import io.openeden.codebook.VqVaeCodebookQuantizer
import io.openeden.memory.DjlMemoryEmbeddingModel
import io.openeden.server.db.SqlDelightSessionStateStore
import io.openeden.server.db.SqlDelightDiaryTaskStore
import io.openeden.server.db.SqlDelightMemoryRepository
import io.openeden.server.db.SqlDelightTraceStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun interface RuntimeFactory {
    suspend fun create(store: SessionStateStore, config: LocalRuntimeConfig): RuntimeHandle
}

interface RuntimeHandle {
    suspend fun handle(request: LocalRuntimeRequest): LocalRuntimeResult
}

class PipelineRuntimeHandle(
    private val pipeline: OpenEdenRuntimePipeline,
) : RuntimeHandle {
    override suspend fun handle(request: LocalRuntimeRequest): LocalRuntimeResult = pipeline.handle(request)
}

class OpenEdenCli(
    private val configLoader: () -> LocalRuntimeConfig = { LocalRuntimeConfig.fromEnv() },
    private val storeFactory: (LocalRuntimeConfig) -> SessionStateStore = ::openStore,
    private val runtimeFactory: RuntimeFactory = RuntimeFactory { store, config -> createRuntime(store, config) },
    private val output: (String) -> Unit = ::println,
) {
    constructor(
        runtimeFactory: () -> RuntimeHandle,
        storeFactory: () -> SessionStateStore,
        output: (String) -> Unit,
    ) : this(
        configLoader = { LocalRuntimeConfig.fromEnv(mapOf("OPENEDEN_OPENAI_API_KEY" to "sk-test")) },
        storeFactory = { storeFactory() },
        runtimeFactory = RuntimeFactory { _, _ -> runtimeFactory() },
        output = output,
    )

    suspend fun run(args: List<String>): Int {
        val parsed = parseCommand(args) ?: return usage()
        val config = configLoader()
        return when (parsed) {
            is ParsedCommand.Chat -> chat(parsed, config)
            is ParsedCommand.State -> state(parsed, config)
        }
    }

    private suspend fun chat(command: ParsedCommand.Chat, config: LocalRuntimeConfig): Int {
        val chatConfig = config.requireProviderCredentials()
        val userId = command.user ?: chatConfig.localUserId
        val store = storeFactory(chatConfig)
        val runtime = runtimeFactory.create(store, chatConfig)
        val result = runtime.handle(LocalRuntimeRequest(userId = userId, text = command.message))
        if (command.debug) {
            output("response=${result.response.orEmpty()}")
            output("traceTags=${result.traceTags.sorted()}")
            output("vector=${result.updatedVector.toList()}")
            output("omega=${result.omega.value}")
            output("evolutionIndex=${result.evolutionIndex}")
            if (result.validationErrors.isNotEmpty()) {
                output("validationErrors=${result.validationErrors}")
            }
        } else {
            output(result.response.orEmpty())
        }
        return 0
    }

    private suspend fun state(command: ParsedCommand.State, config: LocalRuntimeConfig): Int {
        val userId = command.user ?: config.localUserId
        val sessionId = "${OpenEdenRuntimePipeline.LOCAL_PLATFORM}:$userId"
        val state = storeFactory(config).read(sessionId)
        output("sessionId=${state.sessionId}")
        output("vector=${state.vector.toList()}")
        output("omega=${state.omega.value}")
        output("shockState=${state.shockState}")
        output("evolutionIndex=${state.evolutionIndex}")
        return 0
    }

    private fun parseCommand(args: List<String>): ParsedCommand? {
        if (args.isEmpty()) return null
        var parsed: ParsedCommand? = null
        return try {
            OpenEdenRootCommand { parsed = it }.main(args)
            parsed
        } catch (error: CliktError) {
            output(error.message ?: "Invalid command")
            null
        }
    }

    private fun usage(): Int = fail("Usage: openeden chat --message <text> [--user <id>] [--debug] | openeden state [--user <id>]")

    private fun fail(message: String): Int {
        output(message)
        return 2
    }
}

suspend fun main(args: Array<String>) {
    exitProcess(OpenEdenCli().run(args.toList()))
}

private fun openStore(config: LocalRuntimeConfig): SqlDelightSessionStateStore {
    createParentDirectory(config.runtimeDbPath)
    return SqlDelightSessionStateStore.open(config.runtimeDbPath)
}

private fun createRuntime(store: SessionStateStore, config: LocalRuntimeConfig): RuntimeHandle {
    val writer = VectorWriteService(store)
    val artifact = config.localModelArtifactPath
        ?.let(::resolvePath)
        ?.let(LocalModelArtifactLoader::read)
    val models = createModels(config, artifact)
    val pipeline = OpenEdenRuntimePipeline.local(
        personaConfig = PersonaFileLoader.load(resolvePath(config.personaPath)),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = JvmInferenceExecutor(),
        quantizer = models.first,
        memoryEmbeddingModel = models.second,
        memoryStore = SqlDelightMemoryRepository.open(config.runtimeDbPath, models.second),
        diaryTaskStore = SqlDelightDiaryTaskStore.open(config.runtimeDbPath),
        traceStore = SqlDelightTraceStore.open(config.runtimeDbPath),
        llmClient = OpenAiResponsesLlmClient(
            apiKey = requireNotNull(config.llm.openAiApiKey),
            model = config.llm.model,
            baseUrl = config.llm.openAiBaseUrl,
        ),
    )
    return PipelineRuntimeHandle(pipeline)
}

private fun createModels(
    config: LocalRuntimeConfig,
    artifact: io.openeden.model.LocalModelArtifact?,
): Pair<io.openeden.codebook.CodebookQuantizer, io.openeden.memory.MemoryEmbeddingModel> = when (config.modelBackend) {
    "artifact" -> artifact?.let { it.codebookQuantizer() to it.memoryEmbeddingModel() }
        ?: error("OPENEDEN_LOCAL_MODEL_ARTIFACT is required when OPENEDEN_MODEL_BACKEND=artifact")
    "djl" -> {
        val local = requireNotNull(artifact) {
            "OPENEDEN_LOCAL_MODEL_ARTIFACT is required when OPENEDEN_MODEL_BACKEND=djl"
        }
        val vqPath = requireExisting(config.djlVqVaeModelPath, "OPENEDEN_DJL_VQVAE_MODEL_PATH")
        val textPath = requireExisting(config.djlTextModelPath, "OPENEDEN_DJL_TEXT_MODEL_PATH")
        val emotionalPath = requireExisting(config.djlEmotionalModelPath, "OPENEDEN_DJL_EMOTIONAL_MODEL_PATH")
        val runner = DjlVqVaeCodebookModelRunner.fromModelPath(
            modelPath = vqPath,
            modelName = config.djlModelName,
            engineName = config.djlEngineName,
            inputDimension = 9,
            codebook = local.vqVae.codebook,
            topK = local.vqVae.topK,
        )
        VqVaeCodebookQuantizer(
            modelRunner = runner,
            dictionary = CodebookDictionary.parseCsv(local.codebookCsv),
        ) to DjlMemoryEmbeddingModel.fromModelPaths(
            textModelPath = textPath,
            emotionalModelPath = emotionalPath,
            textModelName = config.djlModelName,
            emotionalModelName = config.djlModelName,
            engineName = config.djlEngineName,
            textInputDimension = local.textEmbedding.bucketSize,
        )
    }
    else -> error("Unsupported OPENEDEN_MODEL_BACKEND: ${config.modelBackend}")
}

private fun requireExisting(path: Path?, envName: String): Path {
    val configured = requireNotNull(path) { "$envName is required when OPENEDEN_MODEL_BACKEND=djl" }
    val resolved = if (Files.exists(configured)) configured else Path.of("").toAbsolutePath().resolve(configured)
    require(Files.isRegularFile(resolved)) { "$envName does not point to a file: $resolved" }
    return resolved
}

private fun resolvePath(path: Path): Path =
    if (Files.exists(path)) path else Path.of("").toAbsolutePath().resolve(path)

private fun createParentDirectory(path: Path) {
    path.parent?.let { Files.createDirectories(it) }
}

private sealed interface ParsedCommand {
    data class Chat(
        val message: String,
        val user: String?,
        val debug: Boolean,
    ) : ParsedCommand

    data class State(
        val user: String?,
    ) : ParsedCommand
}

private class OpenEdenRootCommand(
    onParsed: (ParsedCommand) -> Unit,
) : CliktCommand(name = "openeden") {
    init {
        subcommands(ChatCliCommand(onParsed), StateCliCommand(onParsed))
    }

    override fun run() = Unit
}

private class ChatCliCommand(
    private val onParsed: (ParsedCommand) -> Unit,
) : CliktCommand(name = "chat") {
    private val message by option("--message").required()
    private val user by option("--user")
    private val debug by option("--debug").flag(default = false)

    override fun run() {
        onParsed(ParsedCommand.Chat(message = message, user = user, debug = debug))
    }
}

private class StateCliCommand(
    private val onParsed: (ParsedCommand) -> Unit,
) : CliktCommand(name = "state") {
    private val user by option("--user")

    override fun run() {
        onParsed(ParsedCommand.State(user = user))
    }
}
