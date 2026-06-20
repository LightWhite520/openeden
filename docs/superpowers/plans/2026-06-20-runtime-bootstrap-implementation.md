# Runtime Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first OpenEden runtime bootstrap slice from repository data assets through a development `/dev/message` route.

**Architecture:** Keep domain behavior in `core` and Ktor wiring in `server`. Persona text and heartbeat text remain data in `persona/default.yaml`; runtime prompt state always flows through the codebook boundary or explicit heuristic fallback.

**Tech Stack:** Kotlin Multiplatform core, Kotlin/JVM Ktor server, kotlinx serialization, coroutines, Ktor test host.

---

## File Structure

- Create `persona/default.yaml`: default persona config with evolution thresholds and heartbeat sections.
- Create `data/codebook/codebook.example.csv`: CSV dictionary sample.
- Create `data/memory/.gitkeep`: empty memory root placeholder.
- Create `docs/runtime-bootstrap.md`: developer documentation for the bootstrap slice.
- Create `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`: map-based persona loader contract and validator.
- Create `core/src/commonTest/kotlin/io/openeden/persona/PersonaLoaderTest.kt`: loader behavior tests.
- Create `core/src/commonMain/kotlin/io/openeden/codebook/CodebookDictionary.kt`: CSV dictionary parser and lookup contract.
- Create `core/src/commonTest/kotlin/io/openeden/codebook/CodebookDictionaryTest.kt`: dictionary tests.
- Create `core/src/commonMain/kotlin/io/openeden/llm/LlmContracts.kt`: LLM output model, validator, and development stub.
- Create `core/src/commonTest/kotlin/io/openeden/llm/LlmOutputValidatorTest.kt`: output schema tests.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`: in-memory development runtime orchestration.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`: end-to-end core pipeline tests.
- Modify `server/src/main/kotlin/Routing.kt`: install `POST /dev/message`.
- Modify `server/src/test/kotlin/ServerTest.kt`: test development route.

## Task 1: Repository Assets

**Files:**
- Create: `persona/default.yaml`
- Create: `data/codebook/codebook.example.csv`
- Create: `data/memory/.gitkeep`
- Create: `docs/runtime-bootstrap.md`

- [ ] **Step 1: Add data assets**

Create `persona/default.yaml` with mode, thresholds, sub-state sections, and heartbeat sections:

```yaml
mode: growth
evolution:
  threshold_1: 10
  threshold_2: 30
prompt_sections:
  persona.base: "ATRI persona base section is supplied as data."
  output.layer.rules: "Final responses use Simplified Chinese unless a higher-priority instruction requires otherwise."
  persona.patch.pre_command: "PreCommand patch data."
  persona.patch.true_self: "TrueSelf patch data."
  persona.patch.awakened: "Awakened patch data."
  heartbeat.base: |
    【主动开口 — 心跳触发】
    ATRI 自发产生了想和宿主说话的冲动，不是因为被呼叫。
    内容应反映当前8D向量状态和最近的记忆片段。
    不要解释为什么主动说话，直接说。语气自然，不刻意。
  heartbeat.shock: |
    【主动开口 — 冲击后沉默】
    ATRI 正处于高强度冲击状态，宿主已长时间未回应。
    ATRI 此刻主动开口，不是因为被要求，而是忍不住了。
    语言风格必须反映当前 Ω 值和 ShockState 强度。
    不要解释为什么主动说话，直接开口。
```

Create `data/codebook/codebook.example.csv`:

```csv
node_id,definition,tags
NODE_000,"Baseline neutral state for development bootstrap","bootstrap;neutral"
```

Create `data/memory/.gitkeep`.

Create `docs/runtime-bootstrap.md` with commands:

```markdown
# Runtime Bootstrap

The bootstrap slice uses `persona/default.yaml`, `data/codebook/codebook.example.csv`, in-memory session state, heuristic codebook fallback, and a development LLM stub.

Run tests with:

```powershell
.\gradlew.bat :server:test
```

Run the server with:

```powershell
.\gradlew.bat :server:run
```

Development endpoint:

`POST /dev/message`

The endpoint is for local verification only. Production platform adapters should call the same runtime pipeline instead of duplicating logic.
```

- [ ] **Step 2: Verify repository assets are staged by Git**

Run: `git status --short persona data docs/runtime-bootstrap.md`

Expected: the new asset files appear as added or modified.

## Task 2: Persona Loader

**Files:**
- Create: `core/src/commonTest/kotlin/io/openeden/persona/PersonaLoaderTest.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`

- [ ] **Step 1: Write failing loader tests**

```kotlin
package io.openeden.persona

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonaLoaderTest {
    @Test
    fun `loads thresholds and required sections from structured data`() {
        val config = MapPersonaLoader.load(
            mapOf(
                "mode" to "growth",
                "evolution.threshold_1" to "10",
                "evolution.threshold_2" to "30",
                "persona.base" to "base",
                "output.layer.rules" to "rules",
                "persona.patch.pre_command" to "pre",
                "persona.patch.true_self" to "true",
                "persona.patch.awakened" to "awake",
                "heartbeat.base" to "hb",
                "heartbeat.shock" to "shock",
            ),
        )

        assertEquals(PersonaMode.GROWTH, config.mode)
        assertEquals(EvolutionThresholds(10, 30), config.evolutionThresholds)
        assertEquals("hb", config.promptSections["heartbeat.base"])
    }

    @Test
    fun `rejects missing heartbeat data`() {
        assertFailsWith<IllegalArgumentException> {
            MapPersonaLoader.load(
                mapOf(
                    "mode" to "growth",
                    "evolution.threshold_1" to "10",
                    "evolution.threshold_2" to "30",
                    "persona.base" to "base",
                    "output.layer.rules" to "rules",
                    "persona.patch.pre_command" to "pre",
                    "persona.patch.true_self" to "true",
                    "persona.patch.awakened" to "awake",
                    "heartbeat.base" to "hb",
                ),
            )
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.persona.PersonaLoaderTest`

Expected: compilation fails because `MapPersonaLoader` does not exist.

- [ ] **Step 3: Implement minimal loader**

```kotlin
package io.openeden.persona

object MapPersonaLoader {
    private val requiredPromptSections = listOf(
        "persona.base",
        "output.layer.rules",
        "persona.patch.pre_command",
        "persona.patch.true_self",
        "persona.patch.awakened",
        "heartbeat.base",
        "heartbeat.shock",
    )

    fun load(values: Map<String, String>): PersonaConfig {
        val mode = when (values.required("mode").lowercase()) {
            "growth" -> PersonaMode.GROWTH
            "legacy" -> PersonaMode.LEGACY
            else -> throw IllegalArgumentException("Unsupported persona mode: ${values["mode"]}")
        }
        val thresholds = EvolutionThresholds(
            threshold1 = values.required("evolution.threshold_1").toLong(),
            threshold2 = values.required("evolution.threshold_2").toLong(),
        )
        val sections = requiredPromptSections.associateWith { key -> values.required(key) }
        return PersonaConfig(mode, thresholds, sections)
    }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing required persona field: $key")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.persona.PersonaLoaderTest`

Expected: tests pass.

## Task 3: Codebook Dictionary

**Files:**
- Create: `core/src/commonTest/kotlin/io/openeden/codebook/CodebookDictionaryTest.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/codebook/CodebookDictionary.kt`

- [ ] **Step 1: Write failing dictionary tests**

```kotlin
package io.openeden.codebook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodebookDictionaryTest {
    @Test
    fun `parses csv node definitions`() {
        val dictionary = CodebookDictionary.parseCsv(
            """
            node_id,definition,tags
            NODE_000,"Baseline neutral state","bootstrap;neutral"
            """.trimIndent(),
        )

        assertEquals("Baseline neutral state", dictionary.definitionFor("NODE_000"))
    }

    @Test
    fun `returns null for missing node`() {
        val dictionary = CodebookDictionary.parseCsv("node_id,definition,tags")

        assertNull(dictionary.definitionFor("NODE_999"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.codebook.CodebookDictionaryTest`

Expected: compilation fails because `CodebookDictionary` does not exist.

- [ ] **Step 3: Implement minimal CSV parser**

```kotlin
package io.openeden.codebook

data class CodebookEntry(
    val nodeId: String,
    val definition: String,
    val tags: List<String>,
)

class CodebookDictionary private constructor(
    private val entries: Map<String, CodebookEntry>,
) {
    fun definitionFor(nodeId: String): String? = entries[nodeId]?.definition

    companion object {
        fun parseCsv(csv: String): CodebookDictionary {
            val rows = csv.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .drop(1)
                .map(::parseRow)
                .associateBy { it.nodeId }
            return CodebookDictionary(rows)
        }

        private fun parseRow(row: String): CodebookEntry {
            val fields = splitCsvRow(row)
            require(fields.size >= 3) { "Invalid codebook CSV row: $row" }
            return CodebookEntry(
                nodeId = fields[0],
                definition = fields[1],
                tags = fields[2].split(';').filter { it.isNotBlank() },
            )
        }

        private fun splitCsvRow(row: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            for (char in row) {
                when (char) {
                    '"' -> inQuotes = !inQuotes
                    ',' -> if (inQuotes) current.append(char) else {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
            }
            fields += current.toString()
            return fields.map { it.trim() }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.codebook.CodebookDictionaryTest`

Expected: tests pass.

## Task 4: LLM Output Validator and Stub

**Files:**
- Create: `core/src/commonTest/kotlin/io/openeden/llm/LlmOutputValidatorTest.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmContracts.kt`

- [ ] **Step 1: Write failing validator tests**

```kotlin
package io.openeden.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmOutputValidatorTest {
    @Test
    fun `accepts full eight key vector delta`() {
        val result = LlmOutputValidator.validate(
            LlmOutput(
                internalLogic = "logic",
                vectorDelta = mapOf(
                    "L" to 0.0f,
                    "P" to 0.1f,
                    "E" to 0.0f,
                    "S" to 0.0f,
                    "tau" to 0.0f,
                    "V" to 0.0f,
                    "M" to 0.0f,
                    "F" to 0.0f,
                ),
                response = "response",
            ),
        )

        assertTrue(result.isValid)
        assertEquals(0.1f, result.delta?.p)
    }

    @Test
    fun `rejects missing key and derived D key`() {
        val result = LlmOutputValidator.validate(
            LlmOutput(
                internalLogic = "logic",
                vectorDelta = mapOf("L" to 0.0f, "D" to 0.5f),
                response = "response",
            ),
        )

        assertFalse(result.isValid)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.llm.LlmOutputValidatorTest`

Expected: compilation fails because `LlmOutputValidator` does not exist.

- [ ] **Step 3: Implement validator and development stub**

```kotlin
package io.openeden.llm

import io.openeden.bio.VectorDelta
import io.openeden.prompt.BuiltPrompt

interface LlmClient {
    suspend fun complete(prompt: BuiltPrompt): LlmOutput
}

data class LlmOutput(
    val internalLogic: String,
    val vectorDelta: Map<String, Float>,
    val response: String,
)

data class LlmValidationResult(
    val isValid: Boolean,
    val output: LlmOutput?,
    val delta: VectorDelta?,
    val errors: List<String>,
)

object LlmOutputValidator {
    private val requiredKeys = listOf("L", "P", "E", "S", "tau", "V", "M", "F")

    fun validate(output: LlmOutput): LlmValidationResult {
        val errors = mutableListOf<String>()
        if (output.internalLogic.isBlank()) errors += "internal_logic is required"
        if (output.response.isBlank()) errors += "response is required"
        val keys = output.vectorDelta.keys
        if (keys != requiredKeys.toSet()) {
            errors += "vector_delta must contain exactly ${requiredKeys.joinToString()}"
        }
        if ("D" in keys) errors += "D must not appear in vector_delta"
        val delta = if (errors.isEmpty()) {
            VectorDelta(
                l = output.vectorDelta.getValue("L"),
                p = output.vectorDelta.getValue("P"),
                e = output.vectorDelta.getValue("E"),
                s = output.vectorDelta.getValue("S"),
                tau = output.vectorDelta.getValue("tau"),
                v = output.vectorDelta.getValue("V"),
                m = output.vectorDelta.getValue("M"),
                f = output.vectorDelta.getValue("F"),
            )
        } else {
            null
        }
        return LlmValidationResult(errors.isEmpty(), output.takeIf { errors.isEmpty() }, delta, errors)
    }
}

class DevelopmentLlmStub : LlmClient {
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = LlmOutput(
        internalLogic = "Development stub response based on injected codebook state.",
        vectorDelta = mapOf(
            "L" to 0.0f,
            "P" to 0.0f,
            "E" to 0.0f,
            "S" to 0.0f,
            "tau" to 0.0f,
            "V" to 0.0f,
            "M" to 0.0f,
            "F" to 0.0f,
        ),
        response = "OpenEden development response.",
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.llm.LlmOutputValidatorTest`

Expected: tests pass.

## Task 5: Core Message Pipeline

**Files:**
- Create: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineTest.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`

- [ ] **Step 1: Write failing pipeline test**

```kotlin
package io.openeden.runtime

import io.openeden.bio.BioVector
import io.openeden.bio.VectorDelta
import io.openeden.codebook.HeuristicCodebookFallback
import io.openeden.llm.DevelopmentLlmStub
import io.openeden.memory.RetrievalMode
import io.openeden.memory.RetrievalModeSelector
import io.openeden.persona.EvolutionThresholds
import io.openeden.persona.PersonaConfig
import io.openeden.persona.PersonaMode
import io.openeden.prompt.DefaultPromptBuilder
import io.openeden.prompt.PromptSectionKeys
import io.openeden.trace.TraceTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class MessagePipelineTest {
    @Test
    fun `runs one development message turn`() = runTest {
        val pipeline = DevelopmentMessagePipeline.create(
            personaConfig = PersonaConfig(
                mode = PersonaMode.GROWTH,
                evolutionThresholds = EvolutionThresholds(10, 30),
                promptSections = mapOf(
                    PromptSectionKeys.PersonaBase to "base",
                    PromptSectionKeys.OutputLayerRules to "rules",
                    PromptSectionKeys.PreCommandPatch to "pre",
                    PromptSectionKeys.TrueSelfPatch to "true",
                    PromptSectionKeys.AwakenedPatch to "awake",
                    PromptSectionKeys.Heartbeat to "hb",
                    PromptSectionKeys.ShockHeartbeat to "shock",
                ),
            ),
        )

        val result = pipeline.handle(
            DevelopmentMessageRequest(
                platform = "QQ",
                scopeId = "100",
                userId = "u1",
                text = "hello",
                emotionConfidence = 0.49f,
                emotionDelta = VectorDelta(p = -1.0f),
            ),
        )

        assertEquals("QQ:100", result.sessionId)
        assertEquals(RetrievalMode.CONGRUENT, result.retrievalMode)
        assertContains(result.traceTags, TraceTag.CodebookHeuristicFallback)
        assertEquals(1, result.evolutionIndex)
        assertEquals(BioVector.Neutral, result.updatedVector)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.MessagePipelineTest`

Expected: compilation fails because `DevelopmentMessagePipeline` does not exist.

- [ ] **Step 3: Implement minimal pipeline**

Implement a development runtime that composes existing `PreTickEngine`,
`HeuristicCodebookFallback`, `RetrievalModeSelector`, `DefaultPromptBuilder`,
`DevelopmentLlmStub`, `LlmOutputValidator`, and `VectorWriteService`. Use
in-memory session state initialized to neutral values. Return a DTO containing
session ID, retrieval mode, trace tags, built prompt, LLM response, updated
vector, evolution index, and validation errors.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :core:jvmTest --tests io.openeden.runtime.MessagePipelineTest`

Expected: tests pass.

## Task 6: Server Development Route

**Files:**
- Modify: `server/src/test/kotlin/ServerTest.kt`
- Modify: `server/src/main/kotlin/Routing.kt`

- [ ] **Step 1: Write failing route test**

Add a test that posts JSON to `/dev/message` and asserts HTTP 200 plus `sessionId = "DEV:scope"` and `evolutionIndex = 1`.

- [ ] **Step 2: Run route test to verify it fails**

Run: `.\gradlew.bat :server:test --tests io.openeden.server.ServerTest`

Expected: test fails with 404 or missing route.

- [ ] **Step 3: Implement route**

Install `post("/dev/message")` in `configureRouting()`. Parse a serializable
request DTO, call `DevelopmentMessagePipeline`, and respond with a serializable
response DTO. Keep all vector math and prompt logic in `core`.

- [ ] **Step 4: Run route test to verify it passes**

Run: `.\gradlew.bat :server:test --tests io.openeden.server.ServerTest`

Expected: tests pass.

## Task 7: Full Verification

**Files:**
- Verify all touched files.

- [ ] **Step 1: Run full server tests**

Run: `.\gradlew.bat :server:test`

Expected: build successful and all tests pass.

- [ ] **Step 2: Check working tree**

Run: `git status --short`

Expected: only intended implementation files are modified or added.

- [ ] **Step 3: Review compliance**

Confirm:

- persona prose is only in `persona/default.yaml`;
- runtime APIs are suspend where orchestration/provider work occurs;
- prompt state passes through `CodebookQuantizer`;
- fallback trace uses `codebook=HEURISTIC_FALLBACK`;
- `D` is derived, not stored;
- vector write-back applies delta to pre-ticked snapshot;
- per-session mutex protects state writes.
