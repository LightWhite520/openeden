# OpenAI Prompt Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reuse OpenEden's stable prompt prefix on GPT-5.6+ and expose enough metrics to verify cache economics.

**Architecture:** The OpenAI client owns provider-specific cache serialization and derives a non-sensitive key from stable prompt fields. Prompt construction and the VQ-VAE runtime remain unchanged; server configuration only selects the provider cache mode.

**Tech Stack:** Kotlin Multiplatform, Ktor client, kotlinx.serialization, kotlin.test, Gradle

---

### Task 1: Cache protocol request shape

**Files:**
- Create: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiPromptCachingMode.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] Add failing tests asserting that GPT-5.6 requests use a structured persona `input_text` block with an explicit breakpoint, explicit-only request options, and a deterministic cache key while dynamic content remains after the breakpoint.
- [ ] Run `./gradlew :core:jvmTest --tests io.openeden.llm.OpenAiResponsesLlmClientTest` and confirm the cache fields are absent.
- [ ] Add `AUTO`, `EXPLICIT`, and `DISABLED` policy parsing, model-family detection, structured content blocks, and SHA-256 stable-prefix key generation.
- [ ] Re-run the focused JVM client test and confirm it passes.

### Task 2: Cache write observability

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/llm/LlmCacheMetrics.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/api/dto/LlmCacheMetricsDto.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/api/route/Routing.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/llm/LlmCacheMetricsTest.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] Add failing tests for parsing, validating, aggregating, and tracing `cache_write_tokens` and cache-hit request counts.
- [ ] Run the focused common and JVM tests and confirm the new assertions fail.
- [ ] Extend metrics, provider usage decoding, trace attributes, and API mapping with cache-write and ordinary-token fields.
- [ ] Re-run the focused tests and confirm they pass.

### Task 3: Runtime configuration

**Files:**
- Modify: `server/src/main/resources/application.yaml`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] Add a failing mode-parsing test for `auto`, `explicit`, `disabled`, and invalid input.
- [ ] Run the focused test and confirm mode parsing is unavailable.
- [ ] Wire `OPENEDEN_OPENAI_PROMPT_CACHING_MODE` through runtime configuration into the OpenAI client, defaulting to `auto`.
- [ ] Re-run the focused test and confirm it passes.

### Task 4: Regression verification

**Files:**
- Verify only; no planned production edits.

- [ ] Run `./gradlew :core:allTests :server:test`.
- [ ] Inspect `git diff --check` and `git diff` for schema compatibility, prompt ordering, and unrelated changes.
- [ ] Verify the current request fixtures report both `cached_tokens` and `cache_write_tokens` without exposing prompt or key values in traces.
