package io.openeden.server.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatGptRefreshProcessTest {
    @Test
    fun `independent JVMs serialize rotation and reload protected replacement`() = runTest {
        withContext(Dispatchers.IO) {
            val directory = Files.createTempDirectory("oauth-process-test-")
            val store = ChatGptAccountStore(directory)
            store.locked { state, save ->
                save(state.copy(activeClientId = "test-client", accounts = listOf(
                    ChatGptAccount("test-client", "test-subject", null, "expired", "test-old-refresh",
                        expiresAtMs = 0, scopes = setOf(ChatGptOAuth.PLAN_SCOPE, "resource.invoke")),
                )))
            }
            val classpath = System.getProperty("openeden.test.runtimeClasspath")
            check(classpath.isNotBlank())
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val children = (1..2).map { index ->
                ProcessBuilder(java, "-cp", classpath, ChatGptRefreshProcessWorker::class.java.name,
                    directory.toString(), index.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("process-$index.log").toFile()).start()
            }
            try {
                withTimeout(30_000) {
                    while ((1..2).any { !Files.exists(directory.resolve("ready-$it")) }) {
                        check(children.all(Process::isAlive)) { "Refresh subprocess exited before ready: $directory" }
                        delay(20)
                    }
                }
                Files.writeString(directory.resolve("start"), "start")
                for (child in children) {
                    assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Refresh subprocess timed out")
                    assertEquals(0, child.exitValue(), "Inspect isolated subprocess logs in $directory")
                }
                assertTrue((1..2).all { Files.exists(directory.resolve("done-$it")) })
                val account = store.read().accounts.single()
                assertEquals("test-replacement", account.refreshToken)
                assertNull(account.pendingRefresh)
            } finally {
                children.filter(Process::isAlive).forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
            }
        }
    }
}
