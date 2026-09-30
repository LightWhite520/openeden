package io.openeden.server.llm

import io.ktor.server.config.yaml.YamlConfig
import io.openeden.server.auth.ChatGptOAuth
import io.openeden.server.auth.ChatGptAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local interactive selector works even before the runtime's first successful startup. */
suspend fun main(args: Array<String>) {
    val config = requireNotNull(YamlConfig("application.yaml"))
    fun setting(name: String) = config.property("openeden.llm.$name").getString()
    val mode = setting("authMode")
    require(mode in setOf("api_key", "chatgpt"))
    val oauth = if (mode == "chatgpt") ChatGptOAuth() else null
    try {
        LlmModelCatalog(if (oauth == null) setting("apiKey") else "", setting("baseUrl"), setting("model"), oauth).use { catalog ->
            var models = catalog.fetch()
            suspend fun show() {
                val current = catalog.current()
                println("Authentication: $mode | Current model: $current")
                models.forEachIndexed { index, (id, name) ->
                    println("${if (id == current) "*" else " "} ${index + 1}. ${name.filterNot(Char::isISOControl)} [$id]")
                }
                if (models.isEmpty()) println("No available models returned by this provider.")
            }
            when (args.firstOrNull() ?: "pick") {
                "fetch", "list" -> show()
                "select" -> {
                    catalog.select(requireNotNull(args.getOrNull(1)) { "Specify a model ID" })
                    println("Selected ${catalog.current()}. New requests use this model.")
                }
                "pick" -> {
                    show()
                    while (true) {
                        print("Model number or ID, r to fetch again, q to cancel: ")
                        val input = withContext(Dispatchers.IO) { readlnOrNull()?.trim() } ?: break
                        if (input == "q") break
                        if (input == "r") { models = catalog.fetch(); show(); continue }
                        val number = input.toIntOrNull()
                        if (number != null && models.getOrNull(number - 1) == null) { println("Choose a number from the list, or enter a model ID."); continue }
                        val chosen = number?.let { models[it - 1].first } ?: input
                        catalog.select(chosen)
                        println("Selected $chosen. New requests use this model.")
                        break
                    }
                }
                else -> error("Use pick, fetch, or select <model-id>")
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: ChatGptAuthException) {
        System.err.println("${failure.code}: ${failure.message}")
        throw failure
    } catch (_: Exception) {
        System.err.println("Model selection failed. Check authentication and provider availability; the saved choice has not been replaced by a fallback.")
        throw IllegalStateException("Unable to fetch or select a model")
    } finally { oauth?.close() }
}
