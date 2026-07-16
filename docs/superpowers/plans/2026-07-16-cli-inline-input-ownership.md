# CLI Inline Input Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display each live inline user submission exactly once while preserving restored history and messages submitted from full-screen mode.

**Architecture:** Capture native scrollback ownership in `JLineTerminalSession` at submission time and propagate it through CLI-only event and message state. `InlineCliRenderer` claims every completed message ID but skips physical output only for user messages already committed by the inline terminal; history and full-screen-origin messages remain renderer-owned.

**Tech Stack:** Kotlin/JVM, coroutines and Flow, JLine 4, pty4j, JLine `ScreenTerminal`, Kotlin test, Gradle.

---

### Task 1: Reproduce And Fix Duplicate Inline Submission

**Files:**
- Modify: `src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt:71-81`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/CliTerminalEvent.kt:3-16`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/JLineTerminalSession.kt:61-70`
- Modify: `src/main/kotlin/io/openeden/cli/application/CliSessionController.kt:54-58,118-130`
- Modify: `src/main/kotlin/io/openeden/cli/state/CliEvent.kt:5-7`
- Modify: `src/main/kotlin/io/openeden/cli/state/CliMessage.kt:16-24`
- Modify: `src/main/kotlin/io/openeden/cli/state/CliReducer.kt:5-20`
- Modify: `src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt:48-62`

- [ ] **Step 1: Strengthen the pseudo-terminal regression test**

Add exact final-screen assertions after both responses are committed:

```kotlin
assertEquals(1, renderedLines.count { it == "> 你好" }, diagnostics)
assertEquals(1, renderedLines.count { it == "> 再见" }, diagnostics)
```

- [ ] **Step 2: Run the regression test and verify RED**

Run:

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
.\gradlew.bat :test --tests io.openeden.cli.terminal.CliPseudoTerminalTest --rerun-tasks --no-daemon --console=plain
```

Expected: FAIL because the emulated screen and scrollback contains each prompt twice.

- [ ] **Step 3: Add explicit inline terminal ownership to CLI presentation contracts**

Change the terminal event and state event signatures, retaining defaults for plain and test callers:

```kotlin
data class Submit(
    val text: String,
    val inlineTerminalCommitted: Boolean = false,
) : CliTerminalEvent
```

```kotlin
data class Submitted(
    val text: String,
    val id: String,
    val inlineTerminalCommitted: Boolean = false,
) : CliEvent
```

Add presentation-only ownership to `CliMessage`:

```kotlin
data class CliMessage(
    val id: String,
    val role: CliRole,
    val markdown: String,
    val status: CliMessageStatus,
    val inlineTerminalCommitted: Boolean = false,
) {
    val provisional: Boolean
        get() = status == CliMessageStatus.STREAMING
}
```

- [ ] **Step 4: Capture ownership at the JLine submission boundary**

When `readLine()` returns, snapshot the synchronized display state:

```kotlin
enqueue(
    CliTerminalEvent.Submit(
        text = line,
        inlineTerminalCommitted = isInlineDisplay(),
    ),
)
```

Add the focused helper:

```kotlin
private fun isInlineDisplay(): Boolean = synchronized(lifecycleLock) {
    displayState == DisplayState.INLINE
}
```

- [ ] **Step 5: Propagate ownership through controller and reducer**

Pass the event flag into `acceptText`, then into `CliEvent.Submitted`:

```kotlin
is CliTerminalEvent.Submit -> acceptText(
    text = event.text,
    inlineTerminalCommitted = event.inlineTerminalCommitted,
)
```

```kotlin
private fun acceptText(
    text: String,
    inlineTerminalCommitted: Boolean,
)
```

Replace the submitted-event dispatch with:

```kotlin
dispatch(
    CliEvent.Submitted(
        text = text,
        id = requestId,
        inlineTerminalCommitted = inlineTerminalCommitted,
    ),
)
```

Set the flag only on the submitted user message:

```kotlin
CliMessage(
    id = "${event.id}:user",
    role = CliRole.USER,
    markdown = event.text,
    status = CliMessageStatus.COMPLETE,
    inlineTerminalCommitted = event.inlineTerminalCommitted,
)
```

- [ ] **Step 6: Make inline rendering honor ownership after claiming the ID**

Keep `CommittedMessageOwnership.newIds` as the single ID claimant, then skip only the already-visible user row:

```kotlin
committed.newIds(completedMessages.keys.toList()).mapNotNull(completedMessages::get).forEach { msg ->
    if (msg.role == CliRole.USER && msg.inlineTerminalCommitted) return@forEach
    val committedState = current.copy(
        messages = listOf(msg),
        requestActive = false,
        stage = null,
        notice = null,
        diagnosticsVisible = false,
    )
    history?.printAbove(rows(committedState, size.columns).joinToString("\n"))
}
```

- [ ] **Step 7: Run the pseudo-terminal regression and verify GREEN**

Run the Step 2 command again.

Expected: PASS; `> 你好` and `> 再见` each occur once in the final emulated screen and scrollback.

- [ ] **Step 8: Commit the behavioral fix**

```powershell
git add src/main/kotlin/io/openeden/cli src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt
git commit -m "fix(cli): prevent duplicate inline user input"
```

### Task 2: Cover Ownership Boundaries And Verify The Project

**Files:**
- Modify: `src/test/kotlin/io/openeden/cli/render/InlineCliRendererTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/state/CliReducerTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/application/CliSessionControllerTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/terminal/JLineTerminalSessionTest.kt`

- [ ] **Step 1: Add renderer boundary tests**

Add this test for a terminal-owned input. Rendering twice proves the suppressed ID was still claimed:

```kotlin
@Test
fun `inline terminal owned user message is claimed without duplicate output`() {
    val history = mutableListOf<String>()
    val renderer = InlineCliRenderer(history = InlineHistorySink { history += it })
    val state = CliUiState(
        sessionId = "s",
        messages = listOf(
            CliMessage(
                id = "turn:user",
                role = CliRole.USER,
                markdown = "你好",
                status = CliMessageStatus.COMPLETE,
                inlineTerminalCommitted = true,
            ),
        ),
    )

    renderer.render(null, state, Size(80, 24))
    renderer.render(state, state, Size(80, 24))

    assertEquals(emptyList(), history)
}
```

Add a second test using the same message with `inlineTerminalCommitted = false` and assert:

```kotlin
assertEquals(listOf("> 你好"), committedRows)
```

This second case represents restored history and messages originating from full-screen mode.

- [ ] **Step 2: Run renderer tests**

Run:

```powershell
.\gradlew.bat :test --tests io.openeden.cli.render.InlineCliRendererTest --rerun-tasks --no-daemon --console=plain
```

Expected: PASS with both ownership paths covered.

- [ ] **Step 3: Add reducer and controller propagation tests**

In `CliReducerTest`, reduce:

```kotlin
CliEvent.Submitted(
    text = "你好",
    id = "turn-1",
    inlineTerminalCommitted = true,
)
```

Assert the resulting user message owns inline scrollback and the assistant message does not.

In `CliSessionControllerTest`, accept:

```kotlin
CliTerminalEvent.Submit(
    text = "你好",
    inlineTerminalCommitted = true,
)
```

Capture the rendered state and assert the user message retains the flag.

- [ ] **Step 4: Add JLine display-state capture tests**

Use `JLineTerminalSession.fromTerminal` with `RecordingLifecycleOperations(capabilities = true)` and a two-line `readLine` lambda. Collect the first `Submit`, call `session.enterFullScreen()`, then release the second read. Assert:

```kotlin
assertEquals(
    CliTerminalEvent.Submit("inline", inlineTerminalCommitted = true),
    first,
)
assertEquals(
    CliTerminalEvent.Submit("full", inlineTerminalCommitted = false),
    second,
)
```

- [ ] **Step 5: Run focused CLI tests**

Run:

```powershell
.\gradlew.bat :test \
  --tests io.openeden.cli.terminal.CliPseudoTerminalTest \
  --tests io.openeden.cli.terminal.JLineTerminalSessionTest \
  --tests io.openeden.cli.render.InlineCliRendererTest \
  --tests io.openeden.cli.state.CliReducerTest \
  --tests io.openeden.cli.application.CliSessionControllerTest \
  --tests io.openeden.cli.application.TranscriptRestartRestorationE2ETest \
  --rerun-tasks --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL with no failed focused tests.

- [ ] **Step 6: Run Windows terminal regression tests**

Run:

```powershell
.\gradlew.bat :test \
  --tests io.openeden.cli.terminal.CliPseudoTerminalTest \
  --tests io.openeden.cli.terminal.WindowsTerminalInputE2ETest \
  --rerun-tasks --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL; ConPTY input/editing and UTF-8 output remain intact without `chcp`.

- [ ] **Step 7: Run the complete project test matrix**

Run:

```powershell
.\gradlew.bat :core:jvmTest :server:test :test --rerun-tasks --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL with zero failures and zero errors. The existing Windows symlink-permission test may be skipped.

- [ ] **Step 8: Verify repository hygiene and commit tests**

Run:

```powershell
git diff --check
git status --short
```

Then commit:

```powershell
git add src/test/kotlin/io/openeden/cli
git commit -m "test(cli): cover inline input ownership"
```
