package io.openeden.runtime.pipeline

import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.prompt.BuiltPrompt
import io.openeden.bio.VectorDelta
import io.openeden.runtime.lifecycle.IncarnationLifecycleGate
import io.openeden.runtime.lifecycle.IncarnationUnavailableException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessagePipelineLifecycleTest {
    @Test
    fun `terminating incarnation rejects turns before inference`() = runTest {
        val gate = IncarnationLifecycleGate()
        gate.beginTermination()
        var llmCalls = 0
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = streamingTestPersona(),
            lifecycleGate = gate,
            llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    llmCalls += 1
                    return validStreamingOutput("unreachable")
                }
            },
        )

        assertFailsWith<IncarnationUnavailableException> {
            pipeline.handle(
                DevelopmentMessageRequest(
                    turnId = "lifecycle-test",
                    platform = "CLI",
                    scopeId = "test",
                    userId = "u1",
                    text = "hello",
                    emotionConfidence = 0.5f,
                    emotionDelta = VectorDelta.Zero,
                ),
            )
        }
        assertEquals(0, llmCalls)
    }
}
