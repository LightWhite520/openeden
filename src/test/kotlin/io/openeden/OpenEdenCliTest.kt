package io.openeden

import io.openeden.bio.BioVector
import io.openeden.memory.RetrievalMode
import io.openeden.runtime.LocalRuntimeRequest
import io.openeden.runtime.LocalRuntimeResult
import io.openeden.runtime.OmegaState
import io.openeden.runtime.SessionStateStore
import io.openeden.runtime.MutableSessionStateStore
import io.openeden.prompt.BuiltPrompt
import io.openeden.config.LocalRuntimeConfig
import io.openeden.config.LlmProviderConfig
import io.openeden.server.db.SqlDelightSessionStateStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class OpenEdenCliTest {
    @Test
    fun `chat prints only response text by default`() = runTest {
        val output = StringBuilder()
        val cli = OpenEdenCli(
            runtimeFactory = { FakeRuntime() },
            storeFactory = { MutableSessionStateStore() },
            output = { output.appendLine(it) },
        )

        val exit = cli.run(listOf("chat", "--message", "hello"))

        assertEquals(0, exit)
        assertEquals("response\n", output.toString())
    }

    @Test
    fun `chat accepts equals style options`() = runTest {
        val output = StringBuilder()
        val cli = OpenEdenCli(
            runtimeFactory = { FakeRuntime() },
            storeFactory = { MutableSessionStateStore() },
            output = { output.appendLine(it) },
        )

        val exit = cli.run(listOf("chat", "--message=hello"))

        assertEquals(0, exit)
        assertEquals("response\n", output.toString())
    }

    @Test
    fun `chat debug prints trace tags and state`() = runTest {
        val output = StringBuilder()
        val cli = OpenEdenCli(
            runtimeFactory = { FakeRuntime() },
            storeFactory = { MutableSessionStateStore() },
            output = { output.appendLine(it) },
        )

        val exit = cli.run(listOf("chat", "--message", "hello", "--debug"))

        assertEquals(0, exit)
        assertContains(output.toString(), "traceTags=[codebook=HEURISTIC_FALLBACK]")
        assertContains(output.toString(), "evolutionIndex=7")
        assertContains(output.toString(), "omega=0.2")
    }

    @Test
    fun `state prints persisted local session state`() = runTest {
        val store = MutableSessionStateStore()
        store.write(SessionStateStore.neutral("CLI:owner").copy(evolutionIndex = 3))
        val output = StringBuilder()
        val cli = OpenEdenCli(
            runtimeFactory = { FakeRuntime() },
            storeFactory = { store },
            output = { output.appendLine(it) },
        )

        val exit = cli.run(listOf("state", "--user", "owner"))

        assertEquals(0, exit)
        assertContains(output.toString(), "sessionId=CLI:owner")
        assertContains(output.toString(), "evolutionIndex=3")
    }

    @Test
    fun `state does not require provider credentials`() = runTest {
        val store = MutableSessionStateStore()
        val output = StringBuilder()
        val cli = OpenEdenCli(
            configLoader = {
                LocalRuntimeConfig(
                    localUserId = "owner",
                    personaPath = Files.createTempFile("persona", ".yaml"),
                    runtimeDbPath = Files.createTempFile("runtime", ".db"),
                    llm = LlmProviderConfig("openai", "gpt-5-mini", "https://api.openai.com/v1", null),
                )
            },
            storeFactory = { store },
            runtimeFactory = RuntimeFactory { _, _ -> FakeRuntime() },
            output = { output.appendLine(it) },
        )

        val exit = cli.run(listOf("state", "--user", "owner"))

        assertEquals(0, exit)
        assertContains(output.toString(), "sessionId=CLI:owner")
    }

    @Test
    fun `state survives store reopen for local session`() = runTest {
        val tempDir = Files.createTempDirectory("openeden-cli-test")
        val dbPath = tempDir.resolve("runtime.db")
        try {
            val config = LocalRuntimeConfig(
                localUserId = "owner",
                personaPath = tempDir.resolve("persona.yaml"),
                runtimeDbPath = dbPath,
                llm = LlmProviderConfig("openai", "gpt-5-mini", "https://api.openai.com/v1", "sk-test"),
            )
            SqlDelightSessionStateStore.open(dbPath).use { store ->
                store.write(SessionStateStore.neutral("CLI:owner").copy(evolutionIndex = 12))
            }
            val output = StringBuilder()
            val cli = OpenEdenCli(
                configLoader = { config },
                storeFactory = { SqlDelightSessionStateStore.open(dbPath) },
                runtimeFactory = RuntimeFactory { _, _ -> FakeRuntime() },
                output = { output.appendLine(it) },
            )

            val exit = cli.run(listOf("state", "--user", "owner"))

            assertEquals(0, exit)
            assertContains(output.toString(), "evolutionIndex=12")
        } finally {
            Files.list(tempDir).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(tempDir)
        }
    }

    private class FakeRuntime : RuntimeHandle {
        override suspend fun handle(request: LocalRuntimeRequest): LocalRuntimeResult =
            LocalRuntimeResult(
                sessionId = "CLI:${request.userId}",
                retrievalMode = RetrievalMode.CONGRUENT,
                traceTags = setOf("codebook=HEURISTIC_FALLBACK"),
                prompt = BuiltPrompt("system", "persona", "user"),
                response = "response",
                updatedVector = BioVector.Neutral,
                evolutionIndex = 7,
                omega = OmegaState(0.2f),
                shockState = null,
                validationErrors = emptyList(),
            )
    }

    private inline fun SqlDelightSessionStateStore.use(block: (SqlDelightSessionStateStore) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
