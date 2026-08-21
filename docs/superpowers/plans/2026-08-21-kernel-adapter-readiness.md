# Kernel Adapter Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing runtime kernel safe to connect to a future OneBot adapter without adding OneBot code.

**Architecture:** The JVM inference boundary owns a dedicated fixed-size coroutine dispatcher and participates in server resource shutdown. Heartbeat delivery exposes a cheap connection check and isolates send races after state write-back. A real local artifact smoke test verifies the full persona/codebook/prompt/write path.

**Tech Stack:** Kotlin 2.x, kotlinx.coroutines, Ktor, Kotlin Multiplatform, Kotlin test, Gradle.

---

### Task 1: Dedicated JVM inference dispatcher

**Files:**
- Modify: `core/src/jvmMain/kotlin/io/openeden/runtime/inference/JvmInferenceExecutor.kt`
- Create: `core/src/jvmTest/kotlin/io/openeden/runtime/inference/JvmInferenceExecutorTest.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`

- [ ] Add tests asserting the default executor runs on an `openeden-inference-*` thread and implements owned close behavior.
- [ ] Run `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.inference.JvmInferenceExecutorTest"` and confirm RED against `Dispatchers.Default`.
- [ ] Implement an owned fixed thread pool, preserve dispatcher injection without ownership, and make close idempotent.
- [ ] Register the executor in startup-failure and normal server shutdown closers.
- [ ] Re-run the focused test and confirm GREEN.

### Task 2: Heartbeat disconnected-drop contract

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatDelivery.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/NoopHeartbeatDelivery.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/LoggingHeartbeatDelivery.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt`
- Modify: `core/src/commonTest/kotlin/io/openeden/runtime/heartbeat/HeartbeatSchedulerTest.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`

- [ ] Add tests showing a disconnected target is not called and a send failure is dropped after state evolution.
- [ ] Run `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.heartbeat.HeartbeatSchedulerTest"` and confirm RED.
- [ ] Add `isConnected`, isolate non-cancellation delivery failures, and keep state write-back before delivery.
- [ ] Use the no-op delivery in production until an adapter owns a live connection.
- [ ] Re-run the focused test and confirm GREEN.

### Task 3: Artifact-backed kernel smoke test

**Files:**
- Create: `core/src/jvmTest/kotlin/io/openeden/runtime/pipeline/ArtifactBackedKernelSmokeTest.kt`

- [ ] Load the checked-in ATRI persona and local model artifact.
- [ ] Quantize the neutral vector to obtain the exact active node used by the test LLM output.
- [ ] Run one local turn through the shared pipeline with the dedicated executor.
- [ ] Assert VQ-VAE trace tagging, selected persona patch injection, grounded validation, and persisted `evolution_index`.
- [ ] Run `./gradlew.bat :core:jvmTest --tests "io.openeden.runtime.pipeline.ArtifactBackedKernelSmokeTest"`.

### Task 4: Regression gate

**Files:**
- Verify only; do not modify unrelated files.

- [ ] Run `./gradlew.bat :core:jvmTest`.
- [ ] Run `./gradlew.bat test`.
- [ ] Inspect `git diff --check`, `git status --short`, and the focused diff.
- [ ] Confirm Persona-as-Data, non-blocking inference, VQ-VAE-first behavior, and heartbeat no-queue behavior remain intact.
