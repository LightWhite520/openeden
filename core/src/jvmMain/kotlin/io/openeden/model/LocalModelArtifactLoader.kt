package io.openeden.model

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object LocalModelArtifactLoader {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun read(path: Path): LocalModelArtifact =
        json.decodeFromString(LocalModelArtifact.serializer(), Files.readString(path))

    fun write(path: Path, artifact: LocalModelArtifact) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, json.encodeToString(LocalModelArtifact.serializer(), artifact))
    }
}
