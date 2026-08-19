package io.openeden.compatibility

import io.ktor.client.HttpClient
import io.openeden.codebook.CodebookQuantizer
import io.openeden.llm.LlmClient
import io.openeden.llm.OpenAiResponsesLlmClient
import io.openeden.llm.ReasoningEffort
import io.openeden.memory.MemoryEmbeddingModel
import io.openeden.persona.PersonaConfig
import io.openeden.runtime.diary.LlmDiaryNarrativeGenerator
import io.openeden.runtime.diary.DiaryDataSource
import io.openeden.runtime.inference.InferenceExecutor
import io.openeden.runtime.session.SessionStateStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull

class PublicConstructorBinaryCompatibilityTest {
    @Test
    fun `OpenAiResponsesLlmClient keeps its pre-settings constructor descriptor`() {
        assertNotNull(
            OpenAiResponsesLlmClient::class.java.getConstructor(
                String::class.java,
                String::class.java,
                ReasoningEffort::class.java,
                String::class.java,
                HttpClient::class.java,
                Json::class.java,
            ),
        )
    }

    @Test
    fun `LlmDiaryNarrativeGenerator keeps its pre-settings constructor descriptor`() {
        assertNotNull(
            LlmDiaryNarrativeGenerator::class.java.getConstructor(
                PersonaConfig::class.java,
                SessionStateStore::class.java,
                DiaryDataSource::class.java,
                CodebookQuantizer::class.java,
                InferenceExecutor::class.java,
                LlmClient::class.java,
                MemoryEmbeddingModel::class.java,
                Int::class.javaPrimitiveType,
            ),
        )
    }
}
