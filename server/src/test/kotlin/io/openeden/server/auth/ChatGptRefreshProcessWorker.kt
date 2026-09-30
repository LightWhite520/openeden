package io.openeden.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Isolated test subprocess; never reads the user's credential directory or uses the network. */
object ChatGptRefreshProcessWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val directory = Path.of(args[0])
        Files.writeString(directory.resolve("ready-${args[1]}"), "ready", StandardOpenOption.CREATE_NEW)
        while (!Files.exists(directory.resolve("start"))) delay(10)
        ChatGptOAuth(ChatGptAccountStore(directory), HttpClient(MockEngine {
            // Atomic create makes a duplicate refresh across independent JVMs fail the test.
            Files.writeString(directory.resolve("refresh-called"), "once", StandardOpenOption.CREATE_NEW)
            delay(400)
            respond("""{"access_token":"test-access","refresh_token":"test-replacement","token_type":"Bearer","expires_in":3600,"scope":"chatgpt.tokens.use.direct resource.invoke"}""")
        }), { 100_000L }).use { oauth ->
            check(oauth.accessToken() == "test-access")
        }
        Files.writeString(directory.resolve("done-${args[1]}"), "done", StandardOpenOption.CREATE_NEW)
        Unit
    }
}
