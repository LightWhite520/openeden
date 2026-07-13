package io.openeden

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.openeden.client.ChatResponse
import io.openeden.client.OpenEdenServerApi
import io.openeden.client.OpenEdenServerClient
import io.openeden.client.PublicState
import io.openeden.config.CliConfig
import io.openeden.config.CliConfigStore

class OpenEdenCli(
    private val configLoader: () -> CliConfig = { CliConfigStore().loadOrCreate() },
    private val clientFactory: (String) -> OpenEdenServerApi = ::createServerClient,
    private val input: CliInput,
    private val output: (String) -> Unit = ::print,
) {
    suspend fun run(args: List<String>): Int {
        val config = runCatching { configLoader() }.getOrElse {
            output("configuration error: ${it.message}\n")
            return 2
        }
        val client = clientFactory(config.serverUrl)
        return try {
            check(client.health()) {
                "OpenEden server is unavailable at ${config.serverUrl}; start :server:run first"
            }
            if (args.isEmpty()) repl(client, config.userId) else compatibilityCommand(args, client, config.userId)
        } catch (error: Throwable) {
            output("server error: ${error.message ?: "unavailable"}\n")
            1
        } finally {
            client.close()
        }
    }

    private suspend fun repl(client: OpenEdenServerApi, userId: String): Int {
        output("OpenEden connected.\nType /help for commands.\n")
        while (true) {
            output("> ")
            val line = input.readLine() ?: return 0
            when {
                line.isBlank() -> Unit
                line == "/exit" -> return 0
                line == "/help" -> output("/state  /help  /exit\n")
                line == "/state" -> printState(client.state(userId))
                line.startsWith("/") -> output("unknown command: ${line.substringBefore(' ')}\n")
                else -> printChat(client.chat(userId, line))
            }
        }
    }

    private suspend fun compatibilityCommand(
        args: List<String>,
        client: OpenEdenServerApi,
        defaultUserId: String,
    ): Int {
        var parsed: ParsedCommand? = null
        try {
            OpenEdenRootCommand { parsed = it }.main(args)
        } catch (error: CliktError) {
            output("${error.message ?: "invalid command"}\n")
            return 2
        }
        return when (val command = parsed) {
            is ParsedCommand.Chat -> {
                printChat(client.chat(command.user ?: defaultUserId, command.message))
                0
            }
            is ParsedCommand.State -> {
                printState(client.state(command.user ?: defaultUserId))
                0
            }
            null -> 2
        }
    }

    private fun printChat(response: ChatResponse) {
        if (response.status == "completed") output("${response.response.orEmpty()}\n")
        else output("${response.error ?: "request failed"}\n")
    }

    private fun printState(state: PublicState) {
        output(
            "sessionId=${state.sessionId} status=${state.status} omega=${state.omega} " +
                "shockActive=${state.shockActive}\n",
        )
    }
}

private fun createServerClient(url: String): OpenEdenServerClient =
    OpenEdenServerClient(
        baseUrl = url,
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json() }
        },
    )

private sealed interface ParsedCommand {
    data class Chat(val message: String, val user: String?) : ParsedCommand
    data class State(val user: String?) : ParsedCommand
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

    override fun run() {
        onParsed(ParsedCommand.Chat(message = message, user = user))
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
