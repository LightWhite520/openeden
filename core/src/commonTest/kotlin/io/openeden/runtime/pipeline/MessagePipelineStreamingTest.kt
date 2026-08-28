package io.openeden.runtime.pipeline

import io.openeden.bio.VectorDelta
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.LlmGenerationSettings
import io.openeden.llm.LlmVerbosity
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
        val incarnationStore = CountingIncarnationStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(listOf("你", "好"), validStreamingOutput("你好")),
        )

        val events = pipeline.handleStreaming(request()).toList()

        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().map { it.text },
        )
        assertIs<DevelopmentMessageEvent.Completed>(events.last())
        assertEquals(1L, incarnationStore.read("development").evolutionIndex)
        assertEquals(1, incarnationStore.writeCount)
    }

    @Test
    fun `cancelling collection before completion performs no state write`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = SuspendedStreamingStub(),
        )

        pipeline.handleStreaming(request()).take(2).toList()

        assertEquals(0L, incarnationStore.read("development").evolutionIndex)
        assertEquals(0, incarnationStore.writeCount)
    }

    @Test
    fun `strict streaming receives dynamic generation settings`() = runTest {
        val store = CountingSessionStateStore()
        val streaming = SettingsAwareStreamingStub(listOf("你"), validStreamingOutput("你"))
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            llmClient = streaming,
            llmGenerationPolicyConfig = LlmGenerationPolicyConfig(
                temperatureMin = 0.7f,
                temperatureMax = 0.7f,
                maxOutputTokens = 24000,
            ),
        )

        pipeline.handleStreaming(
            request().copy(
                emotionConfidence = 0.49f,
                emotionDelta = VectorDelta.Zero,
            ),
        ).toList()

        assertEquals(
            LlmGenerationSettings(0.7f, LlmVerbosity.MEDIUM, 24000),
            streaming.capturedGenerationSettings,
        )
    }

    private fun request() = DevelopmentMessageRequest(
        turnId = "stream-turn",
        platform = "CLI",
        scopeId = "local",
        userId = "local",
        text = "hello",
    )
}
