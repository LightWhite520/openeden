package io.openeden.runtime.pipeline

import io.openeden.llm.LlmOutput
import io.openeden.llm.LlmStreamEvent
import io.openeden.llm.StreamingLlmClient
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.persona.PersonaSubState
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSectionKeys
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.session.SessionState
import io.openeden.runtime.session.SessionStateStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class StreamingStub(
    private val deltas: List<String>,
    private val output: LlmOutput,
) : StreamingLlmClient {
    override val supportsStrictStructuredStreaming: Boolean = true

    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = flow {
        deltas.forEach { emit(LlmStreamEvent.ResponseDelta(it)) }
        emit(LlmStreamEvent.Completed(output))
    }

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = output
}

internal class SuspendedStreamingStub : StreamingLlmClient {
    override val supportsStrictStructuredStreaming: Boolean = true

    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = flow { awaitCancellation() }

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = awaitCancellation()
}

internal class CountingSessionStateStore : SessionStateStore {
    private val delegate = MutableSessionStateStore()

    var writeCount: Int = 0
        private set

    override suspend fun read(sessionId: String): SessionState = delegate.read(sessionId)

    override suspend fun readOrCreate(
        sessionId: String,
        personaMode: io.openeden.persona.PersonaMode?,
        personaStartSubState: io.openeden.persona.PersonaSubState?,
    ): SessionState = delegate.readOrCreate(sessionId, personaMode, personaStartSubState)

    override suspend fun write(state: SessionState) {
        writeCount += 1
        delegate.write(state)
    }

    override suspend fun sessionIds(): Set<String> = delegate.sessionIds()
}

internal fun validStreamingOutput(response: String): LlmOutput = LlmOutput(
    internalLogic = "logic",
    vectorDelta = listOf("L", "P", "E", "S", "tau", "V", "M", "F").associateWith { 0.0f },
    response = response,
)

internal fun streamingTestPersona(): PersonaConfig = PersonaConfig(
    mode = PersonaMode.GROWTH,
    startSubState = PersonaSubState.PRE_COMMAND,
    promptSections = mapOf(
        PromptSectionKeys.PersonaBase to "base",
        PromptSectionKeys.OutputLayerRules to "rules",
        PromptSectionKeys.PreCommandPatch to "pre",
        PromptSectionKeys.TrueSelfPatch to "true",
        PromptSectionKeys.AwakenedPatch to "awake",
        PromptSectionKeys.Heartbeat to "hb",
        PromptSectionKeys.ShockHeartbeat to "shock",
    ),
)
