package io.openeden.runtime.pipeline

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MessagePipelineStreamingTest {
    @Test
    fun `streamed turn emits deltas then commits once after validation`() = runTest {
        val store = CountingSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            llmClient = StreamingStub(listOf("你", "好"), validStreamingOutput("你好")),
        )

        val events = pipeline.handleStreaming(request()).toList()

        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().map { it.text },
        )
        assertIs<DevelopmentMessageEvent.Completed>(events.last())
        assertEquals(1L, store.read("CLI:local").evolutionIndex)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `cancelling collection before completion performs no state write`() = runTest {
        val store = CountingSessionStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            llmClient = SuspendedStreamingStub(),
        )

        pipeline.handleStreaming(request()).take(2).toList()

        assertEquals(0L, store.readOrCreate("CLI:local").evolutionIndex)
        assertEquals(0, store.writeCount)
    }

    private fun request() = DevelopmentMessageRequest(
        turnId = "stream-turn",
        platform = "CLI",
        scopeId = "local",
        userId = "local",
        text = "hello",
    )
}
