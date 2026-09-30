package io.openeden.server.auth

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Owns OpenEden credentials only; Windows records are protected with current-user DPAPI. */
class ChatGptAccountStore(val directory: Path = defaultDirectory()) {
    private val mutex = Mutex()
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val file get() = directory.resolve(if (windows) "accounts.dpapi" else "accounts.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun <T> locked(block: suspend (ChatGptAccounts, suspend (ChatGptAccounts) -> Unit) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (windows) Files.createDirectories(directory)
            else Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file)) { "Credential paths must not be symbolic links" }
            if (!windows) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            FileChannel.open(directory.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val lease = withTimeout(60_000) {
                    var acquired: java.nio.channels.FileLock? = null
                    while (acquired == null) {
                        acquired = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        if (acquired == null) delay(50)
                    }
                    acquired
                }
                lease.use {
                    val state = if (Files.exists(file)) {
                        val bytes = Files.readAllBytes(file)
                        try {
                            json.decodeFromString<ChatGptAccounts>((if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes).decodeToString())
                        } catch (_: Exception) { error("Unable to read protected ChatGPT credentials") }
                    } else ChatGptAccounts("urn:uuid:${UUID.randomUUID()}").also { saveWithRetry(it) }
                    block(state) { saveWithRetry(it) }
                }
            }
        }
    }

    suspend fun read(): ChatGptAccounts = locked { state, _ -> state }

    private suspend fun saveWithRetry(state: ChatGptAccounts) {
        repeat(3) { attempt ->
            try {
                save(state)
                return
            } catch (failure: java.io.IOException) {
                if (attempt == 2) throw ChatGptAuthException(ChatGptAuthException.Reason.CREDENTIAL_STORAGE_FAILED)
                delay(50L * (attempt + 1))
            }
        }
    }

    private fun save(state: ChatGptAccounts) {
        val plaintext = json.encodeToString(state).encodeToByteArray()
        val bytes = if (windows) Crypt32Util.cryptProtectData(plaintext) else plaintext
        val temporary = if (windows) Files.createTempFile(directory, ".accounts-", ".tmp") else
            Files.createTempFile(directory, ".accounts-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun defaultDirectory(): Path = System.getenv("OPENEDEN_CHATGPT_AUTH_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".openeden", "chatgpt")
    }
}
