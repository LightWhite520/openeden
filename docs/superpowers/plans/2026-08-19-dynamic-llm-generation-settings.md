# Dynamic LLM Generation Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Derive Responses API temperature and verbosity from the pre-ticked centroid-relative 8D state, pass those settings through buffered and streaming LLM calls, and expose validated deployment bounds without changing persona or VQ-VAE behavior.

**Architecture:** Add a pure common-code generation policy that consumes `InternalBioVector` and returns immutable provider-neutral settings. Resolve it inside the existing `InferenceExecutor` block, pass it through backward-compatible `LlmClient` overloads, and serialize it in the existing Responses API adapter. Server configuration supplies only temperature bounds and an optional static output-token ceiling; DeepSeek uses the same `/responses` request without provider-specific branching.

**Tech Stack:** Kotlin Multiplatform 2.x, coroutines/Flow, Ktor client/server, kotlinx.serialization, kotlin.test, Gradle.

---

## Existing Worktree Guard

The current checkout has user-owned edits in:

- `core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt`;
- `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`;
- `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`.

Preserve the current diagnostic `println` calls, prompt logging, import/formatting changes, and response parsing cleanup in those files. Do not reset, restore, or overwrite them. Before each commit, inspect the staged diff and ensure only intentional combined content is staged.

## File Structure

Create focused common-code types in `io.openeden.llm`:

- `LlmVerbosity.kt`: provider-neutral verbosity enum and API value;
- `LlmGenerationSettings.kt`: immutable resolved request settings;
- `LlmGenerationPolicyConfig.kt`: validated deployment bounds and static settings;
- `LlmGenerationPolicy.kt`: pure 8D-to-settings calculation.

Create one focused server configuration adapter:

- `LlmGenerationConfig.kt`: parse Ktor configuration into `LlmGenerationPolicyConfig`.

Modify existing boundaries only where settings cross them: LLM interfaces, Responses serialization, pipeline inference, diary generation, server composition, configuration files, tests, and operator documentation.

### Task 1: Add The Pure 8D Generation Policy

**Files:**

- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmVerbosity.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationSettings.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationPolicyConfig.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationPolicy.kt`
- Create test: `core/src/commonTest/kotlin/io/openeden/llm/LlmGenerationPolicyTest.kt`

- [ ] **Step 1: Write failing policy and validation tests**

```kotlin
package io.openeden.llm

import io.openeden.bio.InternalBioVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmGenerationPolicyTest {
    private val config = LlmGenerationPolicyConfig(temperatureMin = 0.2f, temperatureMax = 1.0f)

    @Test
    fun `maps entropy and logos endpoints into configured temperature range`() {
        val focused = LlmGenerationPolicy.resolve(vector(l = 1.0f, s = -1.0f), config)
        val divergent = LlmGenerationPolicy.resolve(vector(l = -1.0f, s = 1.0f), config)
        val centered = LlmGenerationPolicy.resolve(vector(), config)

        assertEquals(0.2f, focused.temperature, 0.0001f)
        assertEquals(1.0f, divergent.temperature, 0.0001f)
        assertEquals(0.6f, centered.temperature, 0.0001f)
    }

    @Test
    fun `maps internal vitality into verbosity bands`() {
        assertEquals(LlmVerbosity.LOW, LlmGenerationPolicy.resolve(vector(v = -0.35f), config).verbosity)
        assertEquals(LlmVerbosity.MEDIUM, LlmGenerationPolicy.resolve(vector(v = 0.0f), config).verbosity)
        assertEquals(LlmVerbosity.HIGH, LlmGenerationPolicy.resolve(vector(v = 0.35f), config).verbosity)
    }

    @Test
    fun `carries configured static output token limit`() {
        val limited = config.copy(maxOutputTokens = 32_000)

        assertEquals(32_000, LlmGenerationPolicy.resolve(vector(), limited).maxOutputTokens)
        assertEquals(
            LlmGenerationSettings(0.6f, LlmVerbosity.MEDIUM, 32_000),
            limited.staticSettings(),
        )
    }

    @Test
    fun `rejects invalid policy bounds`() {
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(-0.1f, 1.0f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(0.2f, 2.1f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(1.0f, 0.2f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(Float.NaN, 1.0f) }
        assertFailsWith<IllegalArgumentException> { LlmGenerationPolicyConfig(0.2f, 1.0f, 0) }
    }

    private fun vector(l: Float = 0.0f, s: Float = 0.0f, v: Float = 0.0f) = InternalBioVector(
        l = l, p = 0.0f, e = 0.0f, s = s, tau = 0.0f, v = v, m = 0.0f, f = 0.0f,
    )
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.LlmGenerationPolicyTest"
```

Expected: compilation fails because the generation policy types do not exist.

- [ ] **Step 3: Implement the focused policy types**

`LlmVerbosity.kt`:

```kotlin
package io.openeden.llm

enum class LlmVerbosity(val apiValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}
```

`LlmGenerationSettings.kt`:

```kotlin
package io.openeden.llm

data class LlmGenerationSettings(
    val temperature: Float,
    val verbosity: LlmVerbosity,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(temperature.isFinite() && temperature in 0.0f..2.0f) {
            "temperature must be finite and within [0.0, 2.0]"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be positive when configured"
        }
    }

    companion object {
        val Default = LlmGenerationSettings(0.6f, LlmVerbosity.MEDIUM)
    }
}
```

`LlmGenerationPolicyConfig.kt`:

```kotlin
package io.openeden.llm

data class LlmGenerationPolicyConfig(
    val temperatureMin: Float = 0.2f,
    val temperatureMax: Float = 1.0f,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(temperatureMin.isFinite() && temperatureMin in 0.0f..2.0f) {
            "temperatureMin must be finite and within [0.0, 2.0]"
        }
        require(temperatureMax.isFinite() && temperatureMax in 0.0f..2.0f) {
            "temperatureMax must be finite and within [0.0, 2.0]"
        }
        require(temperatureMin <= temperatureMax) {
            "temperatureMin must not exceed temperatureMax"
        }
        require(maxOutputTokens == null || maxOutputTokens > 0) {
            "maxOutputTokens must be positive when configured"
        }
    }

    fun staticSettings(): LlmGenerationSettings = LlmGenerationSettings(
        temperature = temperatureMin + (temperatureMax - temperatureMin) * 0.5f,
        verbosity = LlmVerbosity.MEDIUM,
        maxOutputTokens = maxOutputTokens,
    )

    companion object {
        val Default = LlmGenerationPolicyConfig()
    }
}
```

`LlmGenerationPolicy.kt`:

```kotlin
package io.openeden.llm

import io.openeden.bio.InternalBioVector

object LlmGenerationPolicy {
    private const val LOW_VITALITY_THRESHOLD = -0.35f
    private const val HIGH_VITALITY_THRESHOLD = 0.35f

    fun resolve(
        internalVector: InternalBioVector,
        config: LlmGenerationPolicyConfig,
    ): LlmGenerationSettings {
        val divergence = ((internalVector.s - internalVector.l + 2.0f) / 4.0f).coerceIn(0.0f, 1.0f)
        val temperature = (
            config.temperatureMin +
                (config.temperatureMax - config.temperatureMin) * divergence
            ).coerceIn(config.temperatureMin, config.temperatureMax)
        val verbosity = when {
            internalVector.v <= LOW_VITALITY_THRESHOLD -> LlmVerbosity.LOW
            internalVector.v >= HIGH_VITALITY_THRESHOLD -> LlmVerbosity.HIGH
            else -> LlmVerbosity.MEDIUM
        }
        return LlmGenerationSettings(temperature, verbosity, config.maxOutputTokens)
    }
}
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.LlmGenerationPolicyTest"
```

Expected: all `LlmGenerationPolicyTest` tests pass.

- [ ] **Step 5: Commit the pure policy**

```powershell
git add core/src/commonMain/kotlin/io/openeden/llm/LlmVerbosity.kt core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationSettings.kt core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationPolicyConfig.kt core/src/commonMain/kotlin/io/openeden/llm/LlmGenerationPolicy.kt core/src/commonTest/kotlin/io/openeden/llm/LlmGenerationPolicyTest.kt
git commit -m "feat: derive LLM settings from internal 8D state"
```

### Task 2: Pass Settings Through The Responses API Boundary

**Files:**

- Modify: `core/src/commonMain/kotlin/io/openeden/llm/LlmClient.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/llm/StreamingLlmClient.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Modify test: `src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] **Step 1: Add failing buffered and streaming serialization assertions**

Extend `OpenAiResponsesLlmClientTest` so the buffered request calls:

```kotlin
val settings = LlmGenerationSettings(
    temperature = 0.85f,
    verbosity = LlmVerbosity.LOW,
    maxOutputTokens = 32_000,
)
val output = client.complete(BuiltPrompt("system", "persona", "user"), settings)

val body = Json.parseToJsonElement(requestBody).jsonObject
assertEquals(0.85f, body.getValue("temperature").jsonPrimitive.content.toFloat())
assertEquals(32_000, body.getValue("max_output_tokens").jsonPrimitive.content.toInt())
assertEquals(
    "low",
    body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content,
)
```

Add a separate omission test:

```kotlin
@Test
fun `omits optional max output tokens when unset`() = runTest {
    var requestBody = ""
    val engine = MockEngine { request ->
        requestBody = request.body.toByteArray().decodeToString()
        respond(
            content = """{"output_text":"{\"internal_logic\":\"logic\",\"vector_delta\":{\"L\":0.0,\"P\":0.0,\"E\":0.0,\"S\":0.0,\"tau\":0.0,\"V\":0.0,\"M\":0.0,\"F\":0.0},\"response\":\"ok\"}"}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val client = OpenAiResponsesLlmClient(
        apiKey = "sk-test",
        model = "gpt-5.5",
        httpClient = OpenAiResponsesLlmClient.httpClient(engine, installTimeout = false),
    )

    client.complete(
        BuiltPrompt("system", "persona", "user"),
        LlmGenerationSettings(0.4f, LlmVerbosity.HIGH),
    )

    val body = Json.parseToJsonElement(requestBody).jsonObject
    assertEquals(false, "max_output_tokens" in body)
    assertEquals("high", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
}
```

Update the streaming test to call `client.stream(prompt, settings)` and assert the same temperature and verbosity fields from `requestBody`.

- [ ] **Step 2: Run the client test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests "io.openeden.llm.OpenAiResponsesLlmClientTest"
```

Expected: compilation fails because settings-aware overloads do not exist.

- [ ] **Step 3: Add backward-compatible interface overloads**

Update `LlmClient` without forcing every existing fake client to change:

```kotlin
interface LlmClient {
    suspend fun complete(prompt: BuiltPrompt): LlmOutput

    suspend fun complete(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
    ): LlmOutput = complete(prompt)
}
```

Update `StreamingLlmClient` similarly:

```kotlin
interface StreamingLlmClient : LlmClient {
    val supportsStrictStructuredStreaming: Boolean

    fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent>

    fun stream(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
    ): Flow<LlmStreamEvent> = stream(prompt)
}
```

The default overloads deliberately preserve existing development/test implementations while production clients override the settings-aware methods.

- [ ] **Step 4: Serialize generation settings in one shared request path**

Add a constructor default to `OpenAiResponsesLlmClient`:

```kotlin
private val defaultGenerationSettings: LlmGenerationSettings = LlmGenerationSettings.Default,
```

Keep the existing prompt logging and parsing cleanup, then route both old and new entry points through settings-aware implementations:

```kotlin
override suspend fun complete(prompt: BuiltPrompt): LlmOutput =
    complete(prompt, defaultGenerationSettings)

override suspend fun complete(
    prompt: BuiltPrompt,
    generationSettings: LlmGenerationSettings,
): LlmOutput {
    log.info("\nPrompt:\n${prompt.systemText}\n${prompt.personaText}\n${prompt.userText}")
    val response = execute(prompt, generationSettings, stream = false)
    requireSuccess(response)
    return parseBufferedResponse(response.bodyAsText())
}

override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> =
    stream(prompt, defaultGenerationSettings)

override fun stream(
    prompt: BuiltPrompt,
    generationSettings: LlmGenerationSettings,
): Flow<LlmStreamEvent> = flow {
    log.info("\nPrompt:\n${prompt.systemText}\n${prompt.personaText}\n${prompt.userText}")
    val response = execute(prompt, generationSettings, stream = true)
    requireSuccess(response)
    if (response.contentType()?.withoutParameters() != ContentType.Text.EventStream) {
        emit(LlmStreamEvent.Completed(parseBufferedResponse(response.bodyAsText())))
        return@flow
    }

    val decoder = StrictOutputStreamDecoder(json)
    val emittedResponse = StringBuilder()
    val data = StringBuilder()
    var completed = false
    suspend fun consumeFrame() {
        if (data.isEmpty()) return
        val payload = data.toString()
        data.clear()
        if (payload == "[DONE]") return
        val event = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (error: Throwable) {
            throw IllegalStateException("OpenAI Responses API returned malformed SSE data", error)
        }
        when (event["type"]?.jsonPrimitive?.content) {
            "response.output_text.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("OpenAI response delta omitted delta text")
                decoder.accept(delta).forEach {
                    emittedResponse.append(it)
                    emit(LlmStreamEvent.ResponseDelta(it))
                }
            }
            "response.completed" -> {
                check(!completed) { "OpenAI response stream completed more than once" }
                val output = decoder.finish()
                check(output.response.startsWith(emittedResponse.toString())) {
                    "Streamed response does not match completed structured output"
                }
                val remaining = output.response.substring(emittedResponse.length)
                if (remaining.isNotEmpty()) emit(LlmStreamEvent.ResponseDelta(remaining))
                emit(LlmStreamEvent.Completed(output))
                completed = true
            }
            "response.failed", "error" -> throw IllegalStateException("OpenAI response stream failed")
        }
    }

    val channel = response.bodyAsChannel()
    while (!channel.isClosedForRead) {
        val line = channel.readLine() ?: break
        when {
            line.isEmpty() -> consumeFrame()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
    }
    consumeFrame()
    check(completed) { "OpenAI response stream ended without response.completed" }
}
```

Change the shared request builder and request DTOs:

```kotlin
private suspend fun execute(
    prompt: BuiltPrompt,
    generationSettings: LlmGenerationSettings,
    stream: Boolean,
): HttpResponse = httpClient.post("${baseUrl.trimEnd('/')}/responses") {
    bearerAuth(apiKey)
    contentType(ContentType.Application.Json)
    setBody(
        ResponsesRequest(
            model = model,
            reasoning = ResponsesReasoning(reasoningEffort.value),
            input = listOf(
                ResponsesInputMessage("system", prompt.systemText),
                ResponsesInputMessage("developer", prompt.personaText),
                ResponsesInputMessage("user", prompt.userText),
            ),
            text = TextFormat(
                format = JsonSchemaFormat(
                    type = "json_schema",
                    name = "openeden_llm_output",
                    schema = llmOutputSchema,
                    strict = true,
                ),
                verbosity = generationSettings.verbosity.apiValue,
            ),
            temperature = generationSettings.temperature,
            maxOutputTokens = generationSettings.maxOutputTokens,
            stream = stream,
        ),
    )
}

@Serializable
private data class ResponsesRequest(
    val model: String,
    val reasoning: ResponsesReasoning,
    val input: List<ResponsesInputMessage>,
    val text: TextFormat,
    val temperature: Float,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val stream: Boolean = false,
)

@Serializable
private data class TextFormat(
    val format: JsonSchemaFormat,
    val verbosity: String,
)
```

Configure request JSON with `explicitNulls = false` while retaining existing defaults:

```kotlin
json(Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
})
```

- [ ] **Step 5: Run client tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests "io.openeden.llm.OpenAiResponsesLlmClientTest"
```

Expected: all buffered, streaming, schema, reasoning, and error tests pass.

- [ ] **Step 6: Commit the protocol boundary**

Inspect the staged diff first because `OpenAiResponsesLlmClient.kt` contains user-owned edits:

```powershell
git add core/src/commonMain/kotlin/io/openeden/llm/LlmClient.kt core/src/commonMain/kotlin/io/openeden/llm/StreamingLlmClient.kt core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "feat: send dynamic Responses API settings"
```

### Task 3: Resolve And Trace Settings In The Runtime Pipeline

**Files:**

- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify test: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt`
- Modify test: `core/src/commonTest/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGeneratorTest.kt`

- [ ] **Step 1: Write a failing pipeline test for pre-ticked settings and trace output**

Add a capture client to `MessagePipelineTest`:

```kotlin
private class GenerationSettingsCaptureClient : io.openeden.llm.LlmClient {
    var settings: io.openeden.llm.LlmGenerationSettings? = null

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = validOutput()

    override suspend fun complete(
        prompt: BuiltPrompt,
        generationSettings: io.openeden.llm.LlmGenerationSettings,
    ): LlmOutput {
        settings = generationSettings
        return validOutput()
    }

    private fun validOutput() = LlmOutput(
        internalLogic = "logic",
        vectorDelta = listOf("L", "P", "E", "S", "tau", "V", "M", "F")
            .associateWith { 0.0f },
        response = "response",
    )
}
```

Add the test:

```kotlin
@Test
fun `derives generation settings from pre-ticked internal vector and traces inputs`() = runTest {
    val client = GenerationSettingsCaptureClient()
    val traces = io.openeden.trace.InMemoryTraceStore()
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = testPersonaConfig(),
        llmClient = client,
        traceStore = traces,
        llmGenerationPolicyConfig = io.openeden.llm.LlmGenerationPolicyConfig(0.2f, 1.0f),
        centroidProvider = io.openeden.runtime.state.HomeostasisCentroidProvider { BioVector.Neutral },
    )

    pipeline.handle(
        testRequest().copy(
            emotionConfidence = 1.0f,
            emotionDelta = VectorDelta(l = -0.25f, s = 0.25f, v = -0.25f),
        ),
    )

    val settings = assertNotNull(client.settings)
    assertEquals(0.8f, settings.temperature, 0.0001f)
    assertEquals(io.openeden.llm.LlmVerbosity.LOW, settings.verbosity)
    val span = traces.snapshot().single { it.stage == "llm_generation_policy" }
    assertEquals("-0.5", span.attributes.getValue("internal_l"))
    assertEquals("0.5", span.attributes.getValue("internal_s"))
    assertEquals("-0.5", span.attributes.getValue("internal_v"))
    assertEquals("0.8", span.attributes.getValue("temperature"))
    assertEquals("low", span.attributes.getValue("verbosity"))
    assertTrue(span.attributes.values.none { "persona" in it || "system" in it })
}
```

- [ ] **Step 2: Write a failing diary test for explicit static settings**

Extend the diary fixture and fake client, then add this test:

```kotlin
@Test
fun `passes explicit static generation settings to diary inference`() = runTest {
    val client = FakeClient()
    val diarySettings = LlmGenerationSettings(0.7f, LlmVerbosity.MEDIUM, 24_000)
    val generator = fixture(
        state = SessionStateStore.neutral("S"),
        capture = {},
        client = client,
        generationSettings = diarySettings,
    )

    generator.generate(DiaryTask("t", "S", null, "vector_delta"))

    assertEquals(diarySettings, client.capturedGenerationSettings)
}

private fun fixture(
    state: SessionState,
    capture: (BuiltPrompt) -> Unit,
    client: FakeClient = FakeClient(),
    rawContent: String = "raw fact",
    generationSettings: LlmGenerationSettings = LlmGenerationSettings.Default,
): LlmDiaryNarrativeGenerator {
    val persona = MapPersonaLoader.load(
        mapOf(
            "mode" to "legacy",
            "start_sub_state" to "awakened",
            "persona.base" to "base",
            "output.layer.rules" to "rules",
            "persona.patch.pre_command" to "pre",
            "persona.patch.true_self" to "true",
            "persona.patch.awakened" to "awake",
            "heartbeat.base" to "hb",
            "heartbeat.shock" to "shock",
            "diary.narrative" to "【叙事日记】 write facts",
        ),
    )
    val store = object : SessionStateStore {
        override suspend fun read(sessionId: String) = state
        override suspend fun write(state: SessionState) = Unit
        override suspend fun sessionIds() = setOf(state.sessionId)
    }
    val source = object : DiaryDataSource {
        override suspend fun uncoveredRawSlice(
            sessionId: String,
            throughMemoryId: String?,
            limit: Int,
        ) = DiaryRawSlice(
            memories = listOf(
                MemorySnippet(
                    id = "raw-1",
                    content = rawContent,
                    metadata = MemoryMetadata(
                        BioVector.Neutral,
                        0.0f,
                        VectorDelta.Zero,
                        BioVector.Neutral,
                        "u",
                    ),
                ),
            ),
            upperBoundMemoryId = "raw-1",
        )
    }
    val quantizer = object : CodebookQuantizer {
        override suspend fun quantize(vector: BioVector, dissonance: Float) =
            QuantizationResult(listOf("NODE_1"), listOf("NODE_1 definition"), 1.0f)
    }
    client.capture = capture
    return LlmDiaryNarrativeGenerator(
        personaConfig = persona,
        sessionStateStore = store,
        dataSource = source,
        quantizer = quantizer,
        inferenceExecutor = DirectInferenceExecutor,
        llmClient = client,
        embeddingModel = DeterministicMemoryEmbeddingModel,
        generationSettings = generationSettings,
    )
}

private class FakeClient(
    var capture: (BuiltPrompt) -> Unit = {},
    var output: LlmOutput = LlmOutput("logic", diaryZeroDelta(), "narrative"),
) : LlmClient {
    var capturedGenerationSettings: LlmGenerationSettings? = null

    override suspend fun complete(prompt: BuiltPrompt): LlmOutput {
        capture(prompt)
        return output
    }

    override suspend fun complete(
        prompt: BuiltPrompt,
        generationSettings: LlmGenerationSettings,
    ): LlmOutput {
        capturedGenerationSettings = generationSettings
        return complete(prompt)
    }
}
```

- [ ] **Step 3: Run focused tests and verify they fail**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineTest" --tests "io.openeden.runtime.diary.LlmDiaryNarrativeGeneratorTest"
```

Expected: compilation fails because pipeline and diary generation settings are not wired.

- [ ] **Step 4: Resolve settings inside the existing inference boundary**

Add `llmGenerationPolicyConfig` to `DevelopmentMessagePipeline` and its `create` factory, defaulting to `LlmGenerationPolicyConfig.Default` so existing call sites remain source-compatible.

Inside the existing `inferenceExecutor.run` block, reuse the single mapped vector:

```kotlin
val internalVector = VectorMapping.toInternal(preTick.preTicked, current.origin)
val generationSettings = LlmGenerationPolicy.resolve(
    internalVector = internalVector,
    config = llmGenerationPolicyConfig,
)
val retrievalMode = RetrievalModeSelector.select(
    internalVector = internalVector,
    omegaState = current.omega,
    shockState = current.shockState,
)
PipelineInferenceResult(
    dissonance = dissonance,
    quantization = quantization,
    retrievalMode = retrievalMode,
    internalVector = internalVector,
    generationSettings = generationSettings,
)
```

Extend `PipelineInferenceResult` with:

```kotlin
val internalVector: InternalBioVector,
val generationSettings: LlmGenerationSettings,
```

Trace immediately before prompt construction or LLM inference:

```kotlin
trace(
    traceContext,
    "llm_generation_policy",
    attributes = buildMap {
        put("internal_l", inference.internalVector.l.toString())
        put("internal_s", inference.internalVector.s.toString())
        put("internal_v", inference.internalVector.v.toString())
        put("temperature", inference.generationSettings.temperature.toString())
        put("verbosity", inference.generationSettings.verbosity.apiValue)
        inference.generationSettings.maxOutputTokens?.let {
            put("max_output_tokens", it.toString())
        }
    },
)
```

Change `collectLlmOutput` to accept settings and use the new overloads:

```kotlin
private suspend fun collectLlmOutput(
    prompt: BuiltPrompt,
    generationSettings: LlmGenerationSettings,
    emitEvent: suspend (DevelopmentMessageEvent) -> Unit,
): LlmOutput {
    val streaming = llmClient as? StreamingLlmClient
    if (streaming == null || !streaming.supportsStrictStructuredStreaming) {
        return llmClient.complete(prompt, generationSettings).also { output ->
            if (LlmOutputValidator.validate(output).isValid) {
                emitEvent(DevelopmentMessageEvent.ResponseDelta(output.response))
            }
        }
    }
    var completed: LlmOutput? = null
    streaming.stream(prompt, generationSettings).collect { event ->
        when (event) {
            is LlmStreamEvent.ResponseDelta -> emitEvent(DevelopmentMessageEvent.ResponseDelta(event.text))
            is LlmStreamEvent.Completed -> {
                check(completed == null) { "LLM stream emitted more than one completion" }
                completed = event.output
            }
        }
    }
    println("\n${completed?.internalLogic}\n${completed?.vectorDelta}\n${completed?.response}")
    return checkNotNull(completed) { "LLM stream ended without a completed output" }
}
```

Call it with `inference.generationSettings`.

- [ ] **Step 5: Give Diary an explicit static settings value**

Add this final constructor property with a source-compatible default:

```kotlin
private val generationSettings: LlmGenerationSettings = LlmGenerationSettings.Default,
```

Call:

```kotlin
val output = llmClient.complete(prompt, generationSettings)
println("\n${output.internalLogic}\n${output.vectorDelta}\n${output.response}")
```

- [ ] **Step 6: Run the focused tests and verify they pass**

Run:

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.pipeline.MessagePipelineTest" --tests "io.openeden.runtime.diary.LlmDiaryNarrativeGeneratorTest"
```

Expected: both suites pass, including the pre-tick, trace, and diary settings assertions.

- [ ] **Step 7: Commit runtime integration**

Inspect the staged diff because both production files contain user-owned edits:

```powershell
git add core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt core/src/commonTest/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGeneratorTest.kt
git diff --cached --check
git diff --cached --stat
git commit -m "feat: apply 8D generation policy per turn"
```

### Task 4: Parse And Wire Deployment Bounds

**Files:**

- Create: `server/src/main/kotlin/io/openeden/server/bootstrap/LlmGenerationConfig.kt`
- Create test: `server/src/test/kotlin/io/openeden/server/bootstrap/LlmGenerationConfigTest.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Modify: `server/src/main/resources/application.yaml`
- Modify: `.env.example`

- [ ] **Step 1: Write failing server configuration tests**

```kotlin
package io.openeden.server.bootstrap

import io.ktor.server.config.MapApplicationConfig
import io.openeden.llm.LlmGenerationPolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmGenerationConfigTest {
    @Test
    fun `loads default generation bounds`() {
        assertEquals(LlmGenerationPolicyConfig.Default, loadLlmGenerationPolicyConfig(MapApplicationConfig()))
    }

    @Test
    fun `loads configured bounds and optional token ceiling`() {
        val config = MapApplicationConfig(
            "openeden.llm.temperatureMin" to "0.1",
            "openeden.llm.temperatureMax" to "1.4",
            "openeden.llm.maxOutputTokens" to "32000",
        )

        assertEquals(
            LlmGenerationPolicyConfig(0.1f, 1.4f, 32_000),
            loadLlmGenerationPolicyConfig(config),
        )
    }

    @Test
    fun `rejects malformed generation configuration`() {
        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(
                MapApplicationConfig("openeden.llm.temperatureMin" to "hot"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            loadLlmGenerationPolicyConfig(
                MapApplicationConfig("openeden.llm.maxOutputTokens" to "0"),
            )
        }
    }
}
```

- [ ] **Step 2: Run the server test and verify it fails**

Run:

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.bootstrap.LlmGenerationConfigTest"
```

Expected: compilation fails because `loadLlmGenerationPolicyConfig` does not exist.

- [ ] **Step 3: Implement the focused Ktor config adapter**

```kotlin
package io.openeden.server.bootstrap

import io.ktor.server.config.ApplicationConfig
import io.openeden.llm.LlmGenerationPolicyConfig

internal fun loadLlmGenerationPolicyConfig(config: ApplicationConfig): LlmGenerationPolicyConfig {
    fun value(path: String): String? = config.propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
    fun float(path: String, default: Float): Float {
        val raw = value(path) ?: return default
        return raw.toFloatOrNull() ?: throw IllegalArgumentException("$path must be a number")
    }
    fun positiveInt(path: String): Int? = value(path)?.let { raw ->
        raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("$path must be a positive integer")
    }
    return LlmGenerationPolicyConfig(
        temperatureMin = float("openeden.llm.temperatureMin", 0.2f),
        temperatureMax = float("openeden.llm.temperatureMax", 1.0f),
        maxOutputTokens = positiveInt("openeden.llm.maxOutputTokens"),
    )
}
```

- [ ] **Step 4: Wire one config object through runtime composition**

Add `llmGenerationPolicy: LlmGenerationPolicyConfig` to `ServerRuntimeConfig` and load it with `loadLlmGenerationPolicyConfig(config)`.

In `startRuntime`, derive one static setting:

```kotlin
val llmGenerationPolicy = serverConfig.llmGenerationPolicy
val staticGenerationSettings = llmGenerationPolicy.staticSettings()
val llmClient = OpenAiResponsesLlmClient(
    apiKey = serverConfig.apiKey,
    model = serverConfig.model,
    reasoningEffort = serverConfig.reasoningEffort,
    baseUrl = serverConfig.baseUrl,
    defaultGenerationSettings = staticGenerationSettings,
)
```

Pass `llmGenerationPolicyConfig = llmGenerationPolicy` to `DevelopmentMessagePipeline.create` and `generationSettings = staticGenerationSettings` to `LlmDiaryNarrativeGenerator`. Keep the current model loading, VQ-VAE, inference executor, heartbeat, and shutdown order unchanged.

- [ ] **Step 5: Add environment-backed application settings**

Under `openeden.llm` in `application.yaml` add:

```yaml
temperatureMin: "$OPENEDEN_LLM_TEMPERATURE_MIN:0.2"
temperatureMax: "$OPENEDEN_LLM_TEMPERATURE_MAX:1.0"
maxOutputTokens: "$?OPENEDEN_LLM_MAX_OUTPUT_TOKENS:"
```

In `.env.example` add:

```dotenv
# Dynamic sampling bounds. Final temperature is derived from Entropy and Logos.
OPENEDEN_LLM_TEMPERATURE_MIN=0.2
OPENEDEN_LLM_TEMPERATURE_MAX=1.0
# Optional static Responses API ceiling; includes reasoning and visible output tokens.
# OPENEDEN_LLM_MAX_OUTPUT_TOKENS=32000
```

- [ ] **Step 6: Run configuration and runtime integration tests**

Run:

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.bootstrap.LlmGenerationConfigTest"
.\gradlew.bat :server:test --tests "io.openeden.server.bootstrap.RuntimeShutdownCoordinatorTest"
```

Expected: both test classes pass.

- [ ] **Step 7: Commit configuration and composition**

```powershell
git add server/src/main/kotlin/io/openeden/server/bootstrap/LlmGenerationConfig.kt server/src/test/kotlin/io/openeden/server/bootstrap/LlmGenerationConfigTest.kt server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt server/src/main/resources/application.yaml .env.example
git diff --cached --check
git commit -m "feat: configure dynamic LLM generation bounds"
```

### Task 5: Document OpenAI-Compatible And DeepSeek Operation

**Files:**

- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `docs/runtime-bootstrap.md`

- [ ] **Step 1: Add the new environment variables to both README tables**

English rows:

```markdown
| `OPENEDEN_LLM_TEMPERATURE_MIN`  | Minimum dynamic temperature; default `0.2`.          |
| `OPENEDEN_LLM_TEMPERATURE_MAX`  | Maximum dynamic temperature; default `1.0`.          |
| `OPENEDEN_LLM_MAX_OUTPUT_TOKENS` | Optional static ceiling including reasoning tokens. |
```

Chinese rows:

```markdown
| `OPENEDEN_LLM_TEMPERATURE_MIN`  | 动态温度下限，默认 `0.2`。                            |
| `OPENEDEN_LLM_TEMPERATURE_MAX`  | 动态温度上限，默认 `1.0`。                            |
| `OPENEDEN_LLM_MAX_OUTPUT_TOKENS` | 可选静态输出上限，包含 reasoning tokens。           |
```

- [ ] **Step 2: Document the actual policy and DeepSeek endpoint**

Add this concise operator guidance to `docs/runtime-bootstrap.md` after the existing relay example:

````markdown
The backend derives each dialogue and heartbeat request's temperature from the
pre-ticked, centroid-relative Entropy and Logos values. Vitality selects the
Responses API text verbosity. The environment variables define bounds, not a
fixed per-turn temperature.

DeepSeek's Responses-compatible endpoint uses the same adapter:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="<deepseek-api-key>"
$env:OPENEDEN_OPENAI_MODEL="deepseek-v4-flash"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.deepseek.com"
```

OpenEden sends the standard Responses API fields without provider-specific
branching. DeepSeek may ignore temperature in thinking mode and currently
accepts text verbosity without applying it.
````

Add this English example to `README.md`:

````markdown
DeepSeek uses the same Responses-compatible adapter:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="<deepseek-api-key>"
$env:OPENEDEN_OPENAI_MODEL="deepseek-v4-flash"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.deepseek.com"
```

DeepSeek may ignore temperature in thinking mode and accepts text verbosity
without applying it. OpenEden does not add provider-specific branching.
````

Add this Chinese example to `README.zh-CN.md`:

````markdown
DeepSeek 复用同一个 Responses 兼容适配器：

```powershell
$env:OPENEDEN_OPENAI_API_KEY="<deepseek-api-key>"
$env:OPENEDEN_OPENAI_MODEL="deepseek-v4-flash"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.deepseek.com"
```

DeepSeek 在 thinking mode 下可能忽略 temperature，并且目前接受但不应用
text verbosity；OpenEden 不增加 provider 特判。
````

- [ ] **Step 3: Verify documentation and whitespace**

Run:

```powershell
rg -n "OPENEDEN_LLM_TEMPERATURE|OPENEDEN_LLM_MAX_OUTPUT_TOKENS|deepseek-v4-flash" README.md README.zh-CN.md docs/runtime-bootstrap.md .env.example server/src/main/resources/application.yaml
git diff --check
```

Expected: every new setting appears in application config, example environment, and operator documentation; no whitespace errors are reported.

- [ ] **Step 4: Commit documentation**

```powershell
git add README.md README.zh-CN.md docs/runtime-bootstrap.md
git commit -m "docs: explain dynamic LLM generation settings"
```

### Task 6: Run Cross-Module Verification

**Files:**

- Verify only; no planned production edits.

- [ ] **Step 1: Run all focused policy, pipeline, diary, client, and config tests**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.LlmGenerationPolicyTest" --tests "io.openeden.runtime.pipeline.MessagePipelineTest" --tests "io.openeden.runtime.diary.LlmDiaryNarrativeGeneratorTest"
.\gradlew.bat test --tests "io.openeden.llm.OpenAiResponsesLlmClientTest"
.\gradlew.bat :server:test --tests "io.openeden.server.bootstrap.LlmGenerationConfigTest"
```

Expected: every focused test passes.

- [ ] **Step 2: Run the broader affected-module suites**

```powershell
.\gradlew.bat :core:jvmTest
.\gradlew.bat :server:test
.\gradlew.bat test
```

Expected: all JVM core, server, and root tests pass.

- [ ] **Step 3: Verify architecture invariants and repository hygiene**

```powershell
rg -n "temperature|verbosity|maxOutputTokens" persona core/src/commonMain server/src/main
rg -n "LlmGenerationPolicy.resolve|VectorMapping.toInternal|quantizer.quantize" core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt
git diff --check
git status --short
```

Expected:

- no generation controls appear in `persona/*.yaml`;
- policy evaluation is adjacent to internal mapping and quantization inside `InferenceExecutor`;
- only intended files remain modified;
- no whitespace errors exist.

- [ ] **Step 4: Review final diff against the specification**

```powershell
git diff --stat ac9b890..HEAD
git diff ac9b890..HEAD -- core/src/commonMain/kotlin/io/openeden/llm core/src/commonMain/kotlin/io/openeden/runtime/pipeline core/src/commonMain/kotlin/io/openeden/runtime/diary core/src/jvmMain/kotlin/io/openeden/llm server/src/main README.md README.zh-CN.md docs/runtime-bootstrap.md .env.example
```

Expected: the diff implements every section of `docs/superpowers/specs/2026-08-19-dynamic-llm-generation-settings-design.md` and contains no provider-specific DeepSeek branch, persona logic, VQ-VAE bypass, vector-write change, or unrelated refactor.
