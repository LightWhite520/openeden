# Memory Timestamps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose each retrieved memory's creation time to the LLM as a formatted `created_at` field.

**Architecture:** Carry the durable SQLite `created_at_ms` value through the core `MemoryEntry` and `MemorySnippet` domain types. The Prompt builder formats that value with the existing timezone-stable `PromptTime.format()` function for both recent and semantic memory arrays. Existing callers remain source-compatible through a `0L` default for synthetic fixtures, while production write and persistence paths provide real timestamps.

**Tech Stack:** Kotlin Multiplatform, Ktor server, SQLDelight SQLite, Kotlin test.

---

### Task 1: Carry timestamps through memory domain objects

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryEntry.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemorySnippet.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/memory/MemoryPalace.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/diary/LlmDiaryNarrativeGenerator.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/persistence/sqldelight/SqlDelightMemoryRepository.kt`
- Modify: `server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt`

- [ ] **Step 1: Add compatible timestamp fields**

Add `val createdAtMs: Long = 0L` to both `MemoryEntry` and `MemorySnippet`.

- [ ] **Step 2: Propagate timestamps from entries to snippets**

Every `MemorySnippet` constructed from a `MemoryEntry` must pass `createdAtMs = entry.createdAtMs`. This includes `MemoryPalace` recent and ranked results, the SQLDelight repository recent results, and the server diagnostics mapping.

- [ ] **Step 3: Populate production memory timestamps**

In `MessagePipeline.writeMemories`, capture one `memoryCreatedAtMs = nowMs()` and use it both in the raw memory ID and `MemoryEntry(createdAtMs = memoryCreatedAtMs)`. In `LlmDiaryNarrativeGenerator`, set narrative `createdAtMs = task.availableAtMs` so diary memories retain the task creation time.

- [ ] **Step 4: Preserve the durable database timestamp on reads**

In `SqlDelightMemoryRepository.mapRow`, assign the SQLDelight `createdAtMs` parameter to `MemoryEntry.createdAtMs`. In `writeEntry`, use the explicit field when it is positive and retain `createdAtMsFromId(entry.id)` only as a compatibility fallback for old synthetic entries.

- [ ] **Step 5: Run compilation and existing memory tests**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest :server:test
```

Expected: the build passes; no existing constructor call needs changes because the new fields have defaults.

### Task 2: Inject formatted timestamps into prompts

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`

- [ ] **Step 1: Write the failing prompt test**

Add a `MemorySnippet` fixture with `createdAtMs = 1787381032000L` and assert that the built prompt contains:

```text
"created_at":"2026-08-22 15:43"
```

Use the existing prompt test fixture and assert the field appears in the memory object, without changing the memory content.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.prompt.DefaultPromptBuilderTest
```

Expected: compilation or assertion failure because `created_at` is not yet emitted.

- [ ] **Step 3: Implement the minimal prompt change**

Extend `memorySnippetObject()` with the formatted field:

```kotlin
PromptField("created_at", PromptScalar(PromptTime.format(memory.createdAtMs)))
```

Keep the field in both `recent_turns` and `memories` by placing it in the shared snippet mapper.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same `DefaultPromptBuilderTest` command and expect a successful test task.

### Task 3: Full verification and commit

**Files:**
- Verify: `docs/superpowers/specs/2026-08-22-memory-timestamps-design.md`
- Verify: `docs/superpowers/plans/2026-08-22-memory-timestamps.md`

- [ ] **Step 1: Run the complete relevant test suites**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest :server:test
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :server:shadowJar
```

Expected: both commands exit with code 0.

- [ ] **Step 2: Check formatting and repository state**

Run `git diff --check` and verify only the timestamp implementation, tests, and approved plan/spec changes are present.

- [ ] **Step 3: Commit the implementation**

```text
git add core server docs/superpowers/specs/2026-08-22-memory-timestamps-design.md docs/superpowers/plans/2026-08-22-memory-timestamps.md
git commit -m "feat(memory): expose timestamps in prompt context"
```
