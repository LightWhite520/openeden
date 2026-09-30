package io.openeden.server.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** One atomic file per provider/account; contains only a model identifier, never credentials. */
class ModelSelectionStore(
    private val directory: Path = Path.of(System.getenv("OPENEDEN_MODEL_SETTINGS_DIR")
        ?: Path.of(System.getProperty("user.home"), ".openeden", "models").toString()),
) {
    suspend fun read(scope: String): String? = withContext(Dispatchers.IO) {
        val file = file(scope)
        if (Files.exists(file)) Files.readString(file).trim().takeIf(String::isNotBlank) else null
    }

    suspend fun write(scope: String, model: String) = withContext(Dispatchers.IO) {
        require(model.isNotBlank() && model.length <= 512 && model.none(Char::isISOControl))
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "selection-", ".tmp")
        try {
            Files.writeString(temporary, model)
            Files.move(temporary, file(scope), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        Unit
    }

    private fun file(scope: String): Path = directory.resolve(
        MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) } + ".txt",
    )
}
