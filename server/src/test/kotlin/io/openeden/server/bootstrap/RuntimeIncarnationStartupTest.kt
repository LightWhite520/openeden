package io.openeden.server.bootstrap

import io.openeden.bio.BioVector
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.runtime.inference.DirectInferenceExecutor
import io.openeden.runtime.lifecycle.IncarnationLifecycle
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.state.VectorWriteService
import io.openeden.runtime.tick.RuntimeTickScheduler
import io.openeden.server.persistence.sqldelight.SqlDelightIncarnationStateStore
import io.openeden.server.persistence.sqldelight.SqlDelightTranscriptStore
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeIncarnationStartupTest {
    @Test
    fun `fresh database can tick before any conversation exists`() = runTest {
        withStores { transcripts, incarnations, writer ->
            initializeRuntimeIncarnation(
                IncarnationLifecycle.ACTIVE, transcripts, writer, PersonaMode.GROWTH, PersonaSubState.PRE_COMMAND,
            )
            val id = transcripts.activeIncarnation().id
            val initial = incarnations.read(id)
            assertEquals(0L, initial.evolutionIndex)
            assertEquals(PersonaSubState.PRE_COMMAND, initial.personaStartSubState)
            val scheduler = RuntimeTickScheduler(
                store = MutableSessionStateStore(), writer = writer,
                inferenceExecutor = DirectInferenceExecutor,
                incarnationStore = incarnations, transcriptStore = transcripts,
            )
            assertEquals(1, scheduler.evaluateOnce(1_000L).size)
            assertEquals(1, scheduler.evaluateOnce(2_000L).size)
            assertEquals(2_000L, incarnations.read(id).lastRuntimeTickAtMs)
            assertEquals(0L, incarnations.read(id).evolutionIndex)
        }
    }

    @Test
    fun `restart preserves lived state and immutable starting point`() = runTest {
        withStores { transcripts, incarnations, writer ->
            initializeRuntimeIncarnation(
                IncarnationLifecycle.ACTIVE, transcripts, writer, PersonaMode.GROWTH, PersonaSubState.TRUE_SELF,
            )
            val id = transcripts.activeIncarnation().id
            val original = writer.updateIncarnation(id) {
                it.copy(evolutionIndex = 42L, vector = BioVector(0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f))
            }
            initializeRuntimeIncarnation(
                IncarnationLifecycle.CRITICAL, transcripts, writer, PersonaMode.LEGACY, PersonaSubState.AWAKENED,
            )
            assertEquals(original, incarnations.read(id))
        }
    }

    @Test
    fun `startup does not recreate Bio state for terminated incarnations`() = runTest {
        withStores { transcripts, incarnations, writer ->
            for (lifecycle in listOf(IncarnationLifecycle.TERMINATING, IncarnationLifecycle.TERMINATED)) {
                initializeRuntimeIncarnation(
                    lifecycle, transcripts, writer, PersonaMode.GROWTH, PersonaSubState.PRE_COMMAND,
                )
                val error = assertFailsWith<IllegalStateException> {
                    incarnations.read(transcripts.activeIncarnation().id)
                }
                assertTrue(error.message.orEmpty().contains("Bio vector is missing"))
            }
        }
    }

    private suspend fun withStores(
        block: suspend (SqlDelightTranscriptStore, SqlDelightIncarnationStateStore, VectorWriteService) -> Unit,
    ) {
        val directory = Files.createTempDirectory("openeden-startup-bio-")
        try {
            val path = directory.resolve("runtime.db")
            val transcripts = SqlDelightTranscriptStore.open(path)
            try {
                val incarnations = SqlDelightIncarnationStateStore.open(path)
                try {
                    block(transcripts, incarnations, VectorWriteService(incarnationStore = incarnations))
                } finally {
                    incarnations.shutdown()
                }
            } finally {
                transcripts.close()
            }
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
