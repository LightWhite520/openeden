package io.openeden.server.auth

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import java.awt.Desktop
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** Local operator entry point: never exposes credential or account management on the public server. */
suspend fun main(args: Array<String>) {
    val store = ChatGptAccountStore()
    ChatGptOAuth(store, onProgress = ::println).use { oauth ->
        try {
            when (args.firstOrNull() ?: "status") {
                "check" -> oauth.checkConnection()
                "probe" -> check(oauth.probeModel(requireNotNull(args.getOrNull(1))) in 200..299) { "Model probe failed" }
                "login" -> login(oauth, args.getOrNull(1))
                "status", "accounts" -> {
                    val state = store.read()
                    if (state.accounts.isEmpty()) println("No ChatGPT accounts. Run :server:chatgptAuth --args=login")
                    state.accounts.forEach { account ->
                        val status = when {
                            account.pendingRefresh != null -> "refresh validation pending"
                            account.accessToken == null -> "signed out"
                            else -> "connected"
                        }
                        println("${if (account.clientId == state.activeClientId) "*" else " "} ${account.clientId} ${account.email ?: "ChatGPT account"} $status")
                    }
                }
                "select" -> { oauth.select(requireNotNull(args.getOrNull(1)) { "Specify a saved client ID" }); println("ChatGPT account selected") }
                "models" -> oauth.models().forEach { (slug, name) -> println("$slug\t$name") }
                "models-all" -> oauth.models(includeHidden = true).forEach { (slug, name) -> println("$slug\t$name") }
                "logout" -> println(if (oauth.logout()) "Signed out" else "Signed out locally; remote revocation was not confirmed. Disconnect OpenEden in ChatGPT Settings if needed.")
                else -> error("Use login [client-id], status, accounts, select <client-id>, models, or logout")
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: ChatGptAuthException) {
            System.err.println("${failure.code}: ${failure.message}")
            throw failure
        } catch (_: Exception) {
            // OAuth/library exception messages may contain authorization responses; never print them.
            System.err.println("ChatGPT authentication could not complete. Check connectivity, consent and the selected registration, then retry. No API fallback was used.")
            throw IllegalStateException("ChatGPT authentication failed")
        }
    }
}

private suspend fun login(oauth: ChatGptOAuth, clientId: String?) {
    val pending = CompletableDeferred<ChatGptLoginAttempt>()
    val completed = CompletableDeferred<ChatGptAccount>()
    val consumed = AtomicBoolean(false)
    val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            routing {
                get("/auth/callback") {
                    val attempt = pending.await()
                    val query = call.request.queryParameters
                    if (query.getAll("state")?.size != 1 || query["state"] != attempt.state) {
                        call.respondText("Invalid login state.", status = HttpStatusCode.BadRequest)
                        return@get
                    }
                    if (!consumed.compareAndSet(false, true)) {
                        call.respondText("This sign-in attempt has already completed.", status = HttpStatusCode.Conflict)
                        return@get
                    }
                    try {
                        val account = oauth.complete(attempt, query)
                        call.respondText("OpenEden is connected to ChatGPT. You may close this page.")
                        completed.complete(account)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) {
                        call.respondText("Sign-in failed or permission was not granted. Return to OpenEden and try again.", status = HttpStatusCode.BadRequest)
                        completed.completeExceptionally(IllegalStateException("ChatGPT sign-in failed"))
                    }
                }
            }
    }
    withContext(Dispatchers.IO) { server.start(wait = false) }
    try {
        val port = server.engine.resolvedConnectors().single().port
        val attempt = oauth.begin("http://127.0.0.1:$port/auth/callback", clientId)
        pending.complete(attempt)
        println("Continue with ChatGPT:\n${attempt.authorizationUrl}")
        withContext(Dispatchers.IO) {
            runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(attempt.authorizationUrl)) }
        }
        withTimeout(300_000) { completed.await() }
        println("ChatGPT connected. Set OPENEDEN_LLM_AUTH_MODE=chatgpt, then run :server:models to fetch and select an available model.")
        println("Manage subscription usage: https://chatgpt.com/settings/usage")
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { server.stop(0, 1_000) }
    }
}
