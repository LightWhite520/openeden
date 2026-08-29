package io.openeden.runtime.pipeline

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.llm.LlmClient
import io.openeden.llm.LlmOutput
import io.openeden.memory.InMemoryMemoryPalace
import io.openeden.model.LocalModelArtifactLoader
import io.openeden.persona.PersonaFileLoader
import io.openeden.prompt.BuiltPrompt
import io.openeden.prompt.PromptSegmentKind
import io.openeden.runtime.inference.JvmInferenceExecutor
import io.openeden.runtime.inference.RecordingInferenceExecutor
import io.openeden.runtime.incarnation.MutableIncarnationStateStore
import io.openeden.runtime.session.MutableSessionStateStore
import io.openeden.runtime.state.VectorWriteService
import io.openeden.trace.TraceTag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ArtifactBackedKernelSmokeTest {
    @Test
    fun `checked-in artifacts execute an accepted local turn`() = runBlocking {
        val personaConfig = PersonaFileLoader.load(locateCheckedInFile("persona/atri.yaml"))
        val artifact = LocalModelArtifactLoader.read(
            locateCheckedInFile("data/models/local-model-artifact.json"),
        )
        val quantizer = artifact.codebookQuantizer()
        val memoryEmbeddingModel = artifact.memoryEmbeddingModel()
        val executor = JvmInferenceExecutor()
        val recordingExecutor = RecordingInferenceExecutor(executor)

        try {
            var inferenceThreadName = ""
            val neutralQuantization = executor.run {
                inferenceThreadName = Thread.currentThread().name
                memoryEmbeddingModel.embed(BioVector.Neutral)
                quantizer.quantize(BioVector.Neutral, 0.0f)
            }
            assertTrue(neutralQuantization.activeNodes.isNotEmpty())
            assertTrue(neutralQuantization.semanticDefinitions.isNotEmpty())
            assertContains(neutralQuantization.traceTags, TraceTag.CodebookQuantized)
            assertFalse(TraceTag.CodebookHeuristicFallback in neutralQuantization.traceTags)
            assertTrue(
                inferenceThreadName.startsWith("openeden-inference-"),
                "Expected artifact inference on a dedicated executor, but ran on $inferenceThreadName",
            )

            val responseText = "artifact-backed response accepted"
            val expectedDelta = VectorDelta(p = 0.1f)
            var llmCalls = 0
            val receivedPrompts = mutableListOf<BuiltPrompt>()
            val llmClient = object : LlmClient {
                override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
                    llmCalls += 1
                    receivedPrompts += prompt
                    if (llmCalls == 1) {
                        return LlmOutput(
                            internalLogic = "Deliberately ungrounded schema-valid first attempt",
                            vectorDelta = zeroDelta(),
                            response = "discarded ungrounded response",
                        )
                    }
                    return LlmOutput(
                        internalLogic = buildString {
                            append("Grounded in active nodes ")
                            append(neutralQuantization.activeNodes.joinToString(", "))
                            append(" with definitions: ")
                            append(neutralQuantization.semanticDefinitions.joinToString(" | "))
                        },
                        vectorDelta = zeroDelta() + ("P" to expectedDelta.p),
                        response = responseText,
                    )
                }
            }
            val store = MutableSessionStateStore()
            val incarnationStore = MutableIncarnationStateStore(transcriptStore = store.transcript)
            val vectorWriteService = VectorWriteService(
                incarnationStore = incarnationStore,
                inferenceExecutor = recordingExecutor,
            )
            val memoryStore = InMemoryMemoryPalace(
                inferenceExecutor = recordingExecutor,
                embeddingModel = memoryEmbeddingModel,
            )
            val pipeline = OpenEdenRuntimePipeline.local(
                personaConfig = personaConfig,
                llmClient = llmClient,
                store = store,
                incarnationStateStore = incarnationStore,
                vectorWriteService = vectorWriteService,
                inferenceExecutor = recordingExecutor,
                quantizer = quantizer,
                memoryEmbeddingModel = memoryEmbeddingModel,
                memoryStore = memoryStore,
            )
            val userInput = "Run one artifact-backed local turn."

            val result = pipeline.handle(
                LocalRuntimeRequest(
                    turnId = "artifact-backed-kernel-smoke",
                    userId = "artifact-smoke-user",
                    text = userInput,
                    emotionConfidence = 0.49f,
                ),
            )

            assertContains(result.traceTags, TraceTag.CodebookQuantized)
            assertFalse(TraceTag.CodebookHeuristicFallback in result.traceTags)
            assertContains(result.traceTags, TraceTag.LlmGroundingRepaired)
            assertContains(result.traceTags, TraceTag.MemoryWritten)
            assertEquals(emptyList(), result.validationErrors)
            assertEquals(responseText, result.response)
            assertTrue(recordingExecutor.calls > 0, "Expected pipeline inference boundary crossings")
            assertEquals(2, llmCalls)
            assertEquals(result.prompt, receivedPrompts.first())
            assertContains(receivedPrompts[1].segmentText(PromptSegmentKind.BIO), "[Codebook Grounding Repair]")
            assertEquals(BioVector.Neutral, result.updatedVector.copy(p = BioVector.Neutral.p))
            assertTrue(result.updatedVector.p in BioVector.Neutral.p..<0.6f)

            val selectedPatchKey = "persona.patch.${personaConfig.startSubState.name.lowercase()}"
            val selectedPatch = personaConfig.promptSections.getValue(selectedPatchKey).trim()
            val personaPrompt = Json.parseToJsonElement(
                result.prompt.segmentText(PromptSegmentKind.PERSONA),
            ).jsonObject
            assertEquals(
                selectedPatch,
                personaPrompt.getValue("sub_state_patch").jsonPrimitive.content,
            )
            val personaStringValues = personaPrompt.stringValues().toSet()
            personaConfig.promptSections
                .filterKeys { it.startsWith("persona.patch.") && it != selectedPatchKey }
                .forEach { (key, patch) ->
                    assertFalse(
                        patch.trim() in personaStringValues,
                        "Non-selected persona patch $key was injected",
                    )
                }

            val bioPrompt = Json.parseToJsonElement(
                result.prompt.segmentText(PromptSegmentKind.BIO),
            ).jsonObject
            val bioCoreState = bioPrompt.getValue("bio_core_state").jsonObject
            assertEquals(
                neutralQuantization.activeNodes,
                bioCoreState.getValue("active_nodes").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                neutralQuantization.semanticDefinitions,
                bioCoreState.getValue("definitions").jsonArray.map { it.jsonPrimitive.content },
            )
            val mergedPrompt = result.prompt.textPreview()
            assertTrue(
                mergedPrompt.indexOf("\"bio_core_state\"") < mergedPrompt.indexOf(userInput),
                "Expected codebook state before user input",
            )

            assertEquals(1L, result.evolutionIndex)
            val persistedState = incarnationStore.read("development")
            assertEquals(1L, persistedState.evolutionIndex)
            assertEquals(result.updatedVector, persistedState.vector)
            assertEquals(personaConfig.mode, persistedState.personaMode)
            assertEquals(personaConfig.startSubState, persistedState.personaStartSubState)
            val persistedMemory = memoryStore.recent(result.sessionId, limit = 1).single()
            assertEquals(result.updatedVector, persistedMemory.metadata.snapshot8D)
            assertEquals(0.03f, persistedMemory.metadata.deltaVec.p, absoluteTolerance = 1e-6f)
            assertEquals(VectorDelta.Zero, persistedMemory.metadata.deltaVec.copy(p = 0.0f))
        } finally {
            executor.close()
        }
    }

    private fun BuiltPrompt.segmentText(kind: PromptSegmentKind): String =
        segments.single { it.kind == kind }.text

    private fun zeroDelta(): Map<String, Float> = mapOf(
        "L" to 0.0f,
        "P" to 0.0f,
        "E" to 0.0f,
        "S" to 0.0f,
        "tau" to 0.0f,
        "V" to 0.0f,
        "M" to 0.0f,
        "F" to 0.0f,
    )

    private fun JsonElement.stringValues(): Sequence<String> = when (this) {
        is JsonPrimitive -> if (isString) sequenceOf(content) else emptySequence()
        is JsonArray -> asSequence().flatMap { it.stringValues() }
        is JsonObject -> values.asSequence().flatMap { it.stringValues() }
    }

    private fun locateCheckedInFile(relative: String): Path {
        var directory: Path? = Paths.get("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve(relative)
            if (Files.exists(candidate)) return candidate
            directory = directory.parent
        }
        fail("Could not locate $relative by walking up from ${Paths.get("").toAbsolutePath()}")
    }
}
