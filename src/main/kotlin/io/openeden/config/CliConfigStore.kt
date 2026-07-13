package io.openeden.config

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class CliConfigStore(
    private val path: Path = defaultPath(),
    private val systemUserName: String = System.getProperty("user.name").orEmpty().ifBlank { "local" },
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    fun loadOrCreate(): CliConfig {
        if (Files.isRegularFile(path)) {
            return json.decodeFromString<CliConfig>(Files.readString(path))
                .let { config ->
                    if (config.userId.isBlank()) config.copy(userId = systemUserName) else config
                }
        }

        val config = CliConfig(
            serverUrl = "http://127.0.0.1:8080",
            userId = systemUserName,
        )
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, json.encodeToString(config) + System.lineSeparator())
        return config
    }

    private companion object {
        fun defaultPath(): Path = Path.of(
            System.getProperty("user.home"),
            ".openeden",
            "config.json",
        )
    }
}
