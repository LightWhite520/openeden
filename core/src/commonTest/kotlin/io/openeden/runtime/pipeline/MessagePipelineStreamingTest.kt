package io.openeden.runtime.pipeline

import io.openeden.bio.VectorDelta
import io.openeden.llm.LlmGenerationPolicyConfig
import io.openeden.llm.LlmGenerationSettings
import io.openeden.llm.LlmCacheMetrics
import io.openeden.llm.LlmVerbosity
import io.openeden.llm.CacheMetricAvailability
import io.openeden.llm.PersonaResponseRewriter
import io.openeden.persona.PersonaOutputPolicy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessagePipelineStreamingTest {
    @Test
    fun `safe streamed turn buffers provider deltas and does not rewrite`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        var rewriteCalls = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(transactionalPolicy()),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(listOf("你", "好"), validStreamingOutput("你好")),
            personaResponseRewriter = PersonaResponseRewriter { output, _ ->
                rewriteCalls += 1
                output.copy(response = "不应改写")
            },
        )

        val events = pipeline.handleStreaming(request()).toList()

        assertEquals(
            listOf("你", "好"),
            events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().map { it.text },
        )
        assertEquals(0, rewriteCalls)
        assertIs<DevelopmentMessageEvent.Completed>(events.last())
        assertEquals(1L, incarnationStore.read("development").evolutionIndex)
        assertTrue(incarnationStore.writeCount >= 1)
    }

    @Test
    fun `first approved chunk is observable only after durable state commit`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(listOf("你", "好"), validStreamingOutput("你好")),
        )
        val writesObservedAtDelivery = mutableListOf<Int>()

        pipeline.handleStreaming(request()).collect { event ->
            if (event is DevelopmentMessageEvent.ResponseDelta) {
                writesObservedAtDelivery += incarnationStore.writeCount
            }
        }

        assertEquals(1, writesObservedAtDelivery.distinct().size)
        assertTrue(writesObservedAtDelivery.first() >= 1)
    }

    @Test
    fun `cancelling after first approved chunk leaves the turn persisted`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(listOf("你", "好"), validStreamingOutput("你好")),
        )

        val firstPublicChunk = pipeline.handleStreaming(request())
            .filterIsInstance<DevelopmentMessageEvent.ResponseDelta>()
            .take(1)
            .toList()

        assertEquals(listOf(DevelopmentMessageEvent.ResponseDelta("你")), firstPublicChunk)
        assertEquals(1L, incarnationStore.read("development").evolutionIndex)
        assertTrue(incarnationStore.writeCount >= 1)
    }

    @Test
    fun `rewrite output copy cannot inherit and double count generation cache metrics`() = runTest {
        val generationMetrics = LlmCacheMetrics(inputTokens = 100, cachedInputTokens = 80)
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(transactionalPolicy()),
            llmClient = StreamingStub(
                listOf("收到"),
                validStreamingOutput("收到").copy(cacheMetrics = generationMetrics),
            ),
            personaResponseRewriter = PersonaResponseRewriter { output, _ ->
                output.copy(response = "我会认真处理。")
            },
        )

        val result = pipeline.handle(request())

        val metrics = assertNotNull(result.cacheMetrics)
        assertEquals(CacheMetricAvailability.UNOBSERVABLE, metrics.availability)
        assertEquals(100L, metrics.inputTokens)
        assertEquals(80L, metrics.cachedInputTokens)
        assertEquals(1, metrics.requestCount)
    }

    @Test
    fun `rewrite without usage makes aggregate cache metrics unobservable`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(transactionalPolicy()),
            llmClient = StreamingStub(
                listOf("收到"),
                validStreamingOutput("收到").copy(
                    cacheMetrics = LlmCacheMetrics(inputTokens = 120, cachedInputTokens = 60),
                ),
            ),
            personaResponseRewriter = PersonaResponseRewriter { output, _ ->
                output.copy(response = "我会认真处理。", cacheMetrics = null)
            },
        )

        val result = pipeline.handle(request())

        val metrics = assertNotNull(result.cacheMetrics)
        assertEquals(CacheMetricAvailability.UNOBSERVABLE, metrics.availability)
        assertEquals(120L, metrics.inputTokens)
        assertEquals(60L, metrics.cachedInputTokens)
    }

    @Test
    fun `violating streamed turn rewrites once before delivery and preserves private fields`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        var rewriteCalls = 0
        val original = validStreamingOutput("收到，任务完成。").copy(
            internalLogic = "original private event references HEURISTIC_FALLBACK",
            vectorDelta = mapOf(
                "L" to 0.0f,
                "P" to -0.5f,
                "E" to 0.0f,
                "S" to 0.0f,
                "tau" to 0.0f,
                "V" to 0.0f,
                "M" to 0.0f,
                "F" to 0.4f,
            ),
        )
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(transactionalPolicy()),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(listOf("收到，", "任务完成。"), original),
            personaResponseRewriter = PersonaResponseRewriter { output, _ ->
                rewriteCalls += 1
                output.copy(
                    internalLogic = "replacement private logic",
                    vectorDelta = output.vectorDelta.mapValues { 0.0f },
                    response = "已经做好了，来检查吧。",
                )
            },
        )

        val events = pipeline.handleStreaming(
            request().copy(emotionConfidence = 0.7f, emotionDelta = VectorDelta.Zero),
        ).toList()

        assertEquals(1, rewriteCalls)
        assertEquals(
            listOf("已经做好了，来检查吧。"),
            events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().map { it.text },
        )
        val result = events.filterIsInstance<DevelopmentMessageEvent.Completed>().single().result
        assertEquals("已经做好了，来检查吧。", result.response)
        val state = incarnationStore.read("development")
        assertTrue(state.vector.p in 0.0f..<0.5f)
        assertTrue(state.vector.f in 0.5f..<1.0f)
        assertTrue(state.shockState?.description?.startsWith("original private event") == true)
    }

    @Test
    fun `still invalid rewritten response is rejected without delivery or state write`() = runTest {
        val store = CountingSessionStateStore()
        val incarnationStore = CountingIncarnationStateStore()
        var rewriteCalls = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(transactionalPolicy()),
            store = store,
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(
                listOf("收到"),
                validStreamingOutput("收到"),
            ),
            personaResponseRewriter = PersonaResponseRewriter { output, _ ->
                rewriteCalls += 1
                output.copy(response = "任务完成。")
            },
        )

        val events = pipeline.handleStreaming(request()).toList()

        assertEquals(1, rewriteCalls)
        assertTrue(events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().toList().isEmpty())
        val result = events.filterIsInstance<DevelopmentMessageEvent.Completed>().single().result
        assertEquals(null, result.response)
        assertTrue(result.validationErrors.isNotEmpty())
        assertEquals(0, incarnationStore.writeCount)
    }

    @Test
    fun `invalid vector delta with high confidence emits no public response`() = runTest {
        val incarnationStore = CountingIncarnationStateStore()
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            store = CountingSessionStateStore(),
            incarnationStateStore = incarnationStore,
            llmClient = StreamingStub(
                listOf("must not escape"),
                validStreamingOutput("must not escape").copy(
                    vectorDelta = validStreamingOutput("unused").vectorDelta.toMutableMap().apply {
                        put("P", Float.NaN)
                    },
                ),
            ),
        )

        val events = pipeline.handleStreaming(request().copy(emotionConfidence = 0.9f)).toList()

        assertTrue(events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().toList().isEmpty())
        val result = events.filterIsInstance<DevelopmentMessageEvent.Completed>().single().result
        assertEquals(null, result.response)
        assertTrue(result.validationErrors.isNotEmpty())
        assertEquals(0, incarnationStore.writeCount)
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

    private fun transactionalPolicy() = PersonaOutputPolicy(
        prohibitedPublicPatterns = setOf(
            "^收到[。！!\\s]*$",
            "^收到[，,。.!！\\s]*.*(?:已记录|确认权限|库存|任务完成)",
            "^(?:已记录|确认权限|库存|任务完成)[。.!！\\s]*$",
        ),
        maximumRepeatedOpening = 1,
    )
}
