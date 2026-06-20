# Local Usable Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Stage 1 from the roadmap: a local CLI using a real LLM provider and persistent runtime state.

**Architecture:** Add a production-facing wrapper over the existing runtime pipeline, then compose it from the root CLI with SQLDelight storage and an OpenAI Responses API adapter. Core runtime keeps persona-as-data, codebook fallback, prompt building, and vector write-back invariants.

**Tech Stack:** Kotlin/JVM root app, Kotlin Multiplatform core, Ktor client, SQLDelight SQLite driver, kotlinx serialization, coroutines.

---

## File Structure

- Create `core/src/commonMain/kotlin/io/openeden/runtime/RuntimePipeline.kt`: production-facing request/result contract and adapter over the existing pipeline.
- Create `core/src/commonTest/kotlin/io/openeden/runtime/RuntimePipelineTest.kt`: contract tests.
- Modify `build.gradle.kts`: add Ktor client, SQLite driver, and project dependencies for root CLI.
- Create `src/main/kotlin/io/openeden/config/LocalRuntimeConfig.kt`: environment-backed CLI/provider config.
- Create `src/test/kotlin/io/openeden/config/LocalRuntimeConfigTest.kt`: config tests.
- Create `src/main/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`: OpenAI Responses API adapter.
- Create `src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`: request and parse tests with a fake transport.
- Modify `src/main/kotlin/io/openeden/Main.kt`: CLI command parsing and runtime composition.
- Create `src/test/kotlin/io/openeden/OpenEdenCliTest.kt`: CLI behavior tests.
- Modify `README.md` and `docs/runtime-bootstrap.md`: local CLI run instructions.

## Task 1: Production Runtime Contract

- [ ] Add failing `RuntimePipelineTest`.
- [ ] Implement `LocalRuntimeRequest`, `LocalRuntimeResult`, and `OpenEdenRuntimePipeline`.
- [ ] Verify the wrapper maps local chat to `CLI:<userId>` and preserves trace tags.

## Task 2: Local Config

- [ ] Add failing config tests for defaults and missing OpenAI API key.
- [ ] Implement env-backed config parsing without provider-specific logic in core.

## Task 3: OpenAI LLM Adapter

- [ ] Add failing adapter tests using a fake Ktor engine.
- [ ] Implement suspend Responses API request and JSON output parsing.
- [ ] Keep schema validation in core `LlmOutputValidator`.

## Task 4: CLI Composition

- [ ] Add failing CLI tests for `chat` and `state`.
- [ ] Wire persona loader, SQLDelight store, vector writer, inference executor,
  OpenAI adapter, and runtime pipeline.
- [ ] Ensure `chat --debug` prints trace/state and default chat prints response.

## Task 5: Docs and Verification

- [ ] Update README and runtime docs.
- [ ] Run root and server tests.
- [ ] Scan for AGENTS violations and stale development-only names at the public CLI boundary.
