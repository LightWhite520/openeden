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
import io.openeden.persona.PersonaFileLoader
import io.openeden.runtime.JvmInferenceExecutor
import io.openeden.runtime.LocalRuntimeRequest
import io.openeden.runtime.LocalRuntimeResult
import io.openeden.runtime.OpenEdenRuntimePipeline
import io.openeden.runtime.SessionStateStore
import io.openeden.runtime.VectorWriteService
import io.openeden.server.db.SqlDelightSessionStateStore
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
    val pipeline = OpenEdenRuntimePipeline.local(
        personaConfig = PersonaFileLoader.load(resolvePath(config.personaPath)),
        store = store,
        vectorWriteService = writer,
        inferenceExecutor = JvmInferenceExecutor(),
        llmClient = OpenAiResponsesLlmClient(
            apiKey = requireNotNull(config.llm.openAiApiKey),
            model = config.llm.model,
            baseUrl = config.llm.openAiBaseUrl,
        ),
    )
    return PipelineRuntimeHandle(pipeline)
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
