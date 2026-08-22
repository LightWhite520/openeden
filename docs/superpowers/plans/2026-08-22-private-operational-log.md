# Private Operational Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal dialogue generate a concise, persona-driven private operational log before `vector_delta` and `response`, without changing the output contract or VQ-VAE grounding path.

**Architecture:** Add an optional `internal_logic.private_log` persona section and inject it into the normal persona prompt. Keep protocol semantics in the English logical core, keep ATRI's narrative voice in YAML, and retain the existing string schema, field order, node grounding validator, and ShockState extraction behavior.

**Tech Stack:** Kotlin Multiplatform, Kotlin test, custom YAML persona loader, structured JSON prompt DSL.

---

### Task 1: Transport Private-Log Persona Data

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/PromptSectionKeys.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/persona/PersonaLoader.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/persona/PersonaFileLoader.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/persona/PersonaLoaderTest.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/persona/PersonaFileLoaderTest.kt`

- [ ] **Step 1: Write the failing map-loader test**

Add `"internal_logic.private_log" to "PRIVATE_LOG"` to `voiceSections` in `retains optional structured voice sections`. The existing filtered-map assertion must require the value to survive `MapPersonaLoader.load()`.

- [ ] **Step 2: Run the focused map-loader test and verify it fails**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.persona.PersonaLoaderTest
```

Expected: FAIL because `internal_logic.private_log` is not retained in `promptSections`.

- [ ] **Step 3: Add the optional section key and map-loader support**

Add `const val PrivateOperationalLog = "internal_logic.private_log"` to `PromptSectionKeys`, then add that key to `MapPersonaLoader.optionalPromptSections`. Do not make it required, so existing personas remain compatible.

- [ ] **Step 4: Run the focused map-loader test and verify it passes**

Run the Task 1 Step 2 command again. Expected: PASS.

- [ ] **Step 5: Write the failing file-loader test**

Add this block to `parses style block summary and sequence lists`:

```yaml
internal_logic.private_log: |
  private line 1
  private line 2
```

Then assert:

```kotlin
assertEquals(
    "private line 1\nprivate line 2",
    config.promptSections["internal_logic.private_log"],
)
```

- [ ] **Step 6: Run the focused file-loader test and verify it fails**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.persona.PersonaFileLoaderTest
```

Expected: FAIL because `PersonaFileLoader.isPromptSectionKey()` rejects the new prefix.

- [ ] **Step 7: Allow the new YAML prefix**

Extend `PersonaFileLoader.isPromptSectionKey()` with `key.startsWith("internal_logic.")`.

- [ ] **Step 8: Run both persona-loader tests and verify they pass**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.persona.PersonaLoaderTest --tests io.openeden.persona.PersonaFileLoaderTest
```

Expected: PASS.

### Task 2: Inject Private-Log Semantics Into Normal Dialogue

**Files:**
- Modify: `core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/prompt/DefaultPromptBuilderTest.kt`

- [ ] **Step 1: Write the failing prompt test**

Add `PromptSectionKeys.PrivateOperationalLog to "PRIVATE_OPERATIONAL_LOG_FROM_PERSONA"` to the `promptInput()` persona fixture. Add a test with these assertions:

```kotlin
val built = DefaultPromptBuilder().build(promptInput())
assertContains(built.personaText, "PRIVATE_OPERATIONAL_LOG_FROM_PERSONA")
assertContains(built.systemText, "private operational log")
assertContains(built.systemText, "observable event")
assertContains(built.systemText, "exact active codebook node identifier")
assertTrue("Traceable reasoning process" !in built.systemText)
```

Update the canonical persona-section order assertion to expect `private_operational_log` between `sub_state_patch` and `style`.

- [ ] **Step 2: Run the focused prompt test and verify it fails**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.prompt.DefaultPromptBuilderTest
```

Expected: FAIL because the persona section is not injected and the schema still describes a traceable reasoning process.

- [ ] **Step 3: Implement neutral protocol semantics and persona injection**

In `OpenEdenPromptDocumentFactory`, retain the active-node rule; define `internal_logic` as brief narrative conditioning rather than chain of thought; require the observable event first; prohibit response-writing strategy; change its schema description to `Brief private operational log conditioned on the current Codebook state`; and inject:

```kotlin
personaSection(
    "private_operational_log",
    input.personaConfig,
    PromptSectionKeys.PrivateOperationalLog,
)
```

Place the section after `sub_state_patch` and before shared style guidance.

- [ ] **Step 4: Run the focused prompt test and verify it passes**

Run the Task 2 Step 2 command again. Expected: PASS.

### Task 3: Add Original ATRI Operational-Log Guidance

**Files:**
- Modify: `persona/atri.yaml`
- Test: `core/src/jvmTest/kotlin/io/openeden/persona/AtriPersonaGuardTest.kt`

- [ ] **Step 1: Write the failing ATRI persona guard assertion**

Add `internal_logic.private_log` to `requiredSections`. Add a test:

```kotlin
val privateLog = PersonaFileLoader.load(atriYaml)
    .promptSections.getValue("internal_logic.private_log")
listOf("可观察", "推测", "矛盾", "具体行动").forEach { marker ->
    assertTrue(marker in privateLog, "Missing private-log mechanism: $marker")
}
assertTrue("MUST NOT provide chain-of-thought" in privateLog)
```

- [ ] **Step 2: Run the focused guard test and verify it fails**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.persona.AtriPersonaGuardTest
```

Expected: FAIL because the ATRI persona does not yet contain the section.

- [ ] **Step 3: Add the persona-data section**

Add `internal_logic.private_log: |` before `diary.narrative`. The Chinese descriptive layer defines an original first-person operational-log voice with observable event, tentative inference, contradiction, selected mundane detail, and concrete action tendency. English hard constraints require consistency with the immutable starting-point patch, a concise log, no chain of thought, no system mechanics, and no recognizable source dialogue. Include only original micro-examples.

- [ ] **Step 4: Run the focused guard and prompt tests**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest --tests io.openeden.persona.AtriPersonaGuardTest --tests io.openeden.prompt.DefaultPromptBuilderTest
```

Expected: PASS.

### Task 4: Full Verification And Commit

**Files:**
- Verify: `docs/superpowers/specs/2026-08-22-private-operational-log-design.md`
- Verify: `docs/superpowers/plans/2026-08-22-private-operational-log.md`
- Verify: all files changed in Tasks 1-3

- [ ] **Step 1: Run the relevant test suites**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :core:jvmTest :server:test
```

Expected: BUILD SUCCESSFUL with zero test failures.

- [ ] **Step 2: Run the server package build**

Run:

```text
java -Xmx64m -Xms64m -jar gradle\wrapper\gradle-wrapper.jar :server:shadowJar
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check repository state and prompt boundaries**

Run `git diff --check`, inspect `git diff`, and confirm personality prose exists only in `persona/atri.yaml`; Kotlin contains only protocol semantics and data routing; no blocking calls or VQ-VAE flow changes were introduced; root JSON field order remains `internal_logic`, `vector_delta`, `response`; and `diary.narrative` remains separate.

- [ ] **Step 4: Commit the implementation**

```text
git add core persona docs/superpowers/plans/2026-08-22-private-operational-log.md
git commit -m "feat(persona): condition replies with private logs"
git log -1 --format=%s
```

Expected title: `feat(persona): condition replies with private logs`.
