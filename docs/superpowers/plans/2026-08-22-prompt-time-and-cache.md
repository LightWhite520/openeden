# System Time And Prompt Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic Shanghai system-time injection and split stable and dynamic prompt layers so OpenAI prompt caching can reuse the stable prefix.

**Architecture:** `DevelopmentMessagePipeline` formats its injected clock through a pure prompt-time formatter and passes the value to `PromptInput`. `DefaultPromptBuilder` renders static system/persona fields separately from dynamic context fields, while `BuiltPrompt` carries the new context layer. `OpenAiResponsesLlmClient` sends system, persona, context, and user in that order; diary prompts keep an empty context.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, kotlinx.datetime, Ktor, kotlinx.serialization, Kotlin Test.

---

### Task 1: Add the prompt time value object and input contract

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/prompt/PromptTime.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptInput.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/PromptTimeTest.kt`

- [ ] **Step 1: Write the failing formatter tests**

Add tests for `PromptTime.format(epochMillis)` using a fixed instant that crosses a UTC day boundary and assert the exact `yyyy-MM-dd HH:mm` output in `Asia/Shanghai`. Add a second test showing seconds are omitted.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew :core:commonTest --tests io.openeden.prompt.PromptTimeTest`.
Expected: FAIL because `PromptTime` does not exist.

- [ ] **Step 3: Implement the formatter and required input field**

Create a pure formatter backed by `kotlinx.datetime.Instant` and `TimeZone.of("Asia/Shanghai")`. Add `systemTime: String` to `PromptInput` as a required field and validate it is non-blank.

- [ ] **Step 4: Run the focused test and verify it passes**

Run `./gradlew :core:commonTest --tests io.openeden.prompt.PromptTimeTest`.
Expected: PASS.

### Task 2: Split stable prompt text from dynamic context

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/BuiltPrompt.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/prompt/PromptTimeTest.kt`

- [ ] **Step 1: Add failing prompt-layer assertions**

Extend the prompt builder tests to assert `systemText` contains only the stable logical contract and output schema, `personaText` contains persona data, `contextText` contains Bio-Core/runtime/memory data, and `system_time` is the final field in `contextText`. Assert `userText` is exactly the user input.

- [ ] **Step 2: Run the focused tests and verify the new assertions fail**

Run `./gradlew :core:commonTest --tests io.openeden.prompt.DefaultPromptBuilderTest`.
Expected: FAIL because `BuiltPrompt` has no context layer and the factory still renders dynamic fields into `systemText`.

- [ ] **Step 3: Implement the stable/dynamic document split**

Add `contextText: String = ""` to `BuiltPrompt`. Make the prompt document factory produce four root fields: `system` with stable logical rules and output schema, `persona` with stable persona sections, `context` with Bio-Core, runtime, observed user, relationship, and memory retrieval fields in fixed order, and `user` with only the input. Render the formatted `systemTime` as the last context field. Keep heartbeat persona sections in `persona` so the selected persona remains in the developer layer.

- [ ] **Step 4: Run prompt tests and verify they pass**

Run `./gradlew :core:commonTest --tests io.openeden.prompt.DefaultPromptBuilderTest`.
Expected: PASS.

### Task 3: Thread the clock through the pipeline

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTest.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/pipeline/MessagePipelineTranscriptTest.kt`

- [ ] **Step 1: Add a pipeline assertion for fixed system time**

Configure the existing test pipeline with `nowMs = { fixedMillis }`, execute one valid turn, and assert the returned prompt context contains the expected Shanghai minute string.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./gradlew :core:commonTest --tests io.openeden.runtime.pipeline.MessagePipelineTest`.
Expected: FAIL because the pipeline does not populate `PromptInput.systemTime`.

- [ ] **Step 3: Inject formatted time at prompt construction**

Pass `PromptTime.format(nowMs())` into `PromptInput` immediately before `promptBuilder.build`. Keep all existing uses of `nowMs` for state and trace persistence unchanged.

- [ ] **Step 4: Run pipeline tests and verify they pass**

Run `./gradlew :core:commonTest --tests io.openeden.runtime.pipeline.MessagePipelineTest`.
Expected: PASS.

### Task 4: Send four stable/dynamic layers to OpenAI and update secondary prompt users

**Files:**
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/context/OpenAiTokenCounter.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt`
- Modify: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`
- Modify: `core/src/jvmTest/kotlin/io/openeden/context/OpenAiTokenCounterTest.kt`

- [ ] **Step 1: Add failing request-layer assertions**

Update the OpenAI client test to build a prompt with a context value and assert request input has system, developer, developer, and user roles in order, with the context as the third message. Update token counting expectations to include `contextText`.

- [ ] **Step 2: Run focused JVM tests and verify they fail**

Run `./gradlew :core:jvmTest --tests io.openeden.llm.OpenAiResponsesLlmClientTest --tests io.openeden.context.OpenAiTokenCounterTest`.
Expected: FAIL because the request currently sends only three messages and the token counter ignores context.

- [ ] **Step 3: Implement request and secondary prompt updates**

Add the context message to `ResponsesRequest.input` after persona and before user. Include `contextText` in `OpenAiTokenCounter`. Set diary `BuiltPrompt.contextText` to its existing dynamic facts only if needed; otherwise preserve its current system/persona/user semantics without changing diary behavior.

- [ ] **Step 4: Run focused JVM tests and verify they pass**

Run `./gradlew :core:jvmTest --tests io.openeden.llm.OpenAiResponsesLlmClientTest --tests io.openeden.context.OpenAiTokenCounterTest`.
Expected: PASS.

### Task 5: Run the complete verification suite

**Files:**
- No source changes expected.

- [ ] **Step 1: Run formatting and whitespace checks**

Run `git diff --check`.
Expected: no output and exit code 0.

- [ ] **Step 2: Run common and JVM tests**

Run `./gradlew :core:allTests`.
Expected: PASS.

- [ ] **Step 3: Inspect the final diff**

Run `git diff --stat` and `git diff -- core/src/commonMain/kotlin/io/openeden/prompt core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`.
Confirm no persona logic was added to Kotlin and the only volatile prompt field is in dynamic context.

