# CLI JLine Post Active Renderer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JLine `Status` with a `LineReaderImpl.post` activity layer so same-height streaming deltas redraw without moving transcript rows.

**Architecture:** `OpenEdenLineReader` owns OpenEden activity as a stable `post` supplier under JLine's own reentrant display lock. `JLineTerminalSession` constructs and delegates to that reader, while the renderer clears the post before committing completed assistant history.

**Tech Stack:** Kotlin/JVM, JLine 4.3.1 `LineReaderImpl`, coroutines, pty4j ConPTY tests, Gradle 9.6.1, JDK 21.

---

### Task 1: Reproduce Streaming Scroll In The Pseudo Terminal

**Files:**
- Modify: `src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt`

- [ ] **Step 1: Extend the SSE fixture with controlled same-height deltas**

Replace the first-response gate with two gates. After the `generating` stage, emit `response.delta` events containing `第一段` and `第二段` separately, flushing after each event and waiting for the test to release the next event. Keep both fragments short enough that the active assistant remains one terminal row.

```kotlin
private data class StreamingGates(
    val firstDelta: CountDownLatch = CountDownLatch(1),
    val continueAfterFirst: CountDownLatch = CountDownLatch(1),
    val secondDelta: CountDownLatch = CountDownLatch(1),
    val continueAfterSecond: CountDownLatch = CountDownLatch(1),
)

private fun writeEvent(body: OutputStream, event: String, data: String) {
    body.write("event: $event\ndata: $data\n\n".encodeToByteArray())
    body.flush()
}
```

- [ ] **Step 2: Assert that a fixed transcript row does not move between deltas**

Capture the screen after each delta. Locate the connection banner or the submitted user row by exact text and assert its row index is unchanged while the active row count remains constant.

```kotlin
val firstFrame = transcriptBuffer.awaitScreenState("first streaming delta") { lines ->
    lines.any { it == "ATRI: 第一段" }
}
val fixedRow = firstFrame.lines.indexOf("OpenEden connected. Type /help for commands.")
assertTrue(fixedRow >= 0, firstFrame.raw.boundedForFailure())

gates.continueAfterFirst.countDown()
val secondFrame = transcriptBuffer.awaitScreenState("second streaming delta") { lines ->
    lines.any { it == "ATRI: 第一段第二段" }
}
assertEquals(fixedRow, secondFrame.lines.indexOf("OpenEden connected. Type /help for commands."))
```

- [ ] **Step 3: Run the PTY test and verify RED**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.terminal.CliPseudoTerminalTest" --rerun-tasks
```

Expected: FAIL because the current `Status.update(emptyList(), false)` path changes the scroll region between same-height deltas.

- [ ] **Step 4: Commit the regression test**

```powershell
git add src/test/kotlin/io/openeden/cli/terminal/CliPseudoTerminalTest.kt
git commit -m "test(cli): reproduce streaming activity scroll"
```

### Task 2: Add A LineReader-Owned Activity Post

**Files:**
- Create: `src/main/kotlin/io/openeden/cli/terminal/OpenEdenLineReader.kt`
- Create: `src/test/kotlin/io/openeden/cli/terminal/OpenEdenLineReaderTest.kt`

- [ ] **Step 1: Write failing ownership tests**

Use a dumb JLine terminal backed by byte streams and an inspectable test subclass. Verify that OpenEden activity becomes the post when no JLine post exists, a built-in post remains authoritative, and OpenEden activity returns after the built-in post is cleared.

```kotlin
@Test
fun `built in post takes priority and activity returns afterward`() {
    terminal().use { terminal ->
        val reader = InspectableOpenEdenLineReader(terminal)
        reader.replaceInlineActivity(listOf("[status] generating", "ATRI: partial"))
        assertEquals("[status] generating\nATRI: partial", reader.visiblePost())

        reader.installBuiltInPost("completion menu")
        assertEquals("completion menu", reader.visiblePost())

        reader.clearBuiltInPostAndRedisplay()
        assertEquals("[status] generating\nATRI: partial", reader.visiblePost())
    }
}
```

The test subclass may expose protected `post` only inside the test source set. It must not add test-only methods to production code.

- [ ] **Step 2: Run the reader test and verify RED**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.terminal.OpenEdenLineReaderTest"
```

Expected: FAIL because `OpenEdenLineReader` does not exist.

- [ ] **Step 3: Implement `OpenEdenLineReader`**

Create a focused subclass with a stable supplier and the same protected JLine lock used by input editing:

```kotlin
package io.openeden.cli.terminal

import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import java.util.function.Supplier
import kotlin.concurrent.withLock

internal open class OpenEdenLineReader(
    terminal: Terminal,
    appName: String = "openeden",
) : LineReaderImpl(terminal, appName) {
    private var activity = AttributedString.EMPTY
    private val activityPost = Supplier { activity }

    fun replaceInlineActivity(lines: List<String>) = lock.withLock {
        activity = AttributedStringBuilder().apply {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append(line)
            }
        }.toAttributedString()

        if (post == null || post === activityPost) {
            post = activity.takeUnless { it.length() == 0 }?.let { activityPost }
        }
        redisplay()
    }

    override fun redisplay(flush: Boolean) = lock.withLock {
        if (post == null && activity.length() > 0) post = activityPost
        super.redisplay(flush)
    }
}
```

Use Kotlin's `kotlin.concurrent.withLock` extension on JLine's protected `ReentrantLock`. Keep JLine-owned non-null `post` suppliers untouched.

- [ ] **Step 4: Run reader tests and verify GREEN**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.terminal.OpenEdenLineReaderTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the reader component**

```powershell
git add src/main/kotlin/io/openeden/cli/terminal/OpenEdenLineReader.kt src/test/kotlin/io/openeden/cli/terminal/OpenEdenLineReaderTest.kt
git commit -m "feat(cli): add line reader activity post"
```

### Task 3: Move Inline Display Ownership Into `TerminalSession`

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/terminal/TerminalSession.kt`
- Modify: `src/main/kotlin/io/openeden/cli/terminal/JLineTerminalSession.kt`
- Modify: `src/main/kotlin/io/openeden/cli/render/JLineInlineActiveSink.kt`
- Modify: `src/test/kotlin/io/openeden/cli/render/JLineInlineActiveSinkTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/MainTest.kt`
- Modify: `src/test/kotlin/io/openeden/cli/application/OpenEdenCliTest.kt`

- [ ] **Step 1: Write a failing sink delegation test**

Replace the width tests with a behavior test that records the rows delivered to the session boundary:

```kotlin
@Test
fun `sink delegates complete activity frames to terminal ownership`() {
    val frames = mutableListOf<List<String>>()
    val sink = JLineInlineActiveSink(frames::add)

    sink.render(listOf("[status] generating", "ATRI: partial"))
    sink.clear()

    assertEquals(
        listOf(listOf("[status] generating", "ATRI: partial"), emptyList()),
        frames,
    )
}
```

- [ ] **Step 2: Run the sink test and verify RED**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.render.JLineInlineActiveSinkTest"
```

Expected: FAIL because the sink still constructs `Status`.

- [ ] **Step 3: Add the terminal activity contract**

Add to `TerminalSession`:

```kotlin
fun replaceInlineActivity(lines: List<String>)
```

Implement it in `JLineTerminalSession` by delegating to its concrete `OpenEdenLineReader`. Test fakes implement it as a no-op or record frames according to their existing purpose.

- [ ] **Step 4: Construct `OpenEdenLineReader` without `LineReaderBuilder`**

In `buildSession`, replace `LineReaderBuilder` with:

```kotlin
val lineReader = OpenEdenLineReader(terminal).apply {
    setCompleter(CliCommandCompleter(CliCommandParser()))
    setVariable(LineReader.HISTORY_FILE, historyPath)
    option(LineReader.Option.BRACKETED_PASTE, true)
    option(LineReader.Option.HISTORY_INCREMENTAL, true)
}
```

Retain history attachment, widgets, keymap installation, read dispatcher, raw mode, encoding, and history save exactly as before.

- [ ] **Step 5: Reduce the sink to a thin adapter**

```kotlin
class JLineInlineActiveSink(
    private val replace: (List<String>) -> Unit,
) : InlineActiveSink {
    constructor(session: TerminalSession) : this(session::replaceInlineActivity)

    override fun render(lines: List<String>) = replace(lines)
}
```

Delete `activeStatusSize`, all `Status` imports, and the width-specific tests. The normal `InlineActiveSink.clear` and `close` defaults send an empty frame through the same delegate.

- [ ] **Step 6: Run terminal and sink tests and verify GREEN**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.render.JLineInlineActiveSinkTest" --tests "io.openeden.cli.terminal.JLineTerminalSessionTest" --tests "io.openeden.cli.MainTest" --tests "io.openeden.cli.application.OpenEdenCliTest"
```

Expected: PASS.

- [ ] **Step 7: Commit terminal integration**

```powershell
git add src/main/kotlin/io/openeden/cli/terminal src/main/kotlin/io/openeden/cli/render/JLineInlineActiveSink.kt src/test/kotlin/io/openeden/cli
git commit -m "refactor(cli): move activity into line reader display"
```

### Task 4: Clear Activity Before Committing Assistant History

**Files:**
- Modify: `src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt`
- Modify: `src/test/kotlin/io/openeden/cli/render/InlineCliRendererTest.kt`

- [ ] **Step 1: Change the existing ordering test to require clear first**

```kotlin
@Test
fun `active rows clear before completed history is printed`() {
    val calls = mutableListOf<String>()
    val renderer = InlineCliRenderer(
        history = InlineHistorySink { calls += "history" },
        active = object : InlineActiveSink {
            override fun render(lines: List<String>) = Unit
            override fun clear() { calls += "clear" }
        },
    )

    renderer.render(
        null,
        CliUiState("s", messages = listOf(CliMessage("a", CliRole.ASSISTANT, "done", CliMessageStatus.COMPLETE))),
        Size(80, 24),
    )

    assertEquals(listOf("clear", "history"), calls)
}
```

- [ ] **Step 2: Run the renderer test and verify RED**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.render.InlineCliRendererTest"
```

Expected: FAIL with current order `history`, then `clear`.

- [ ] **Step 3: Clear only when newly completed messages are about to commit**

Materialize `newCompletedMessages` once. If it is non-empty, call `active?.clear()` before `history?.printAbove`. Avoid a second clear at the end of the same render call; preserve normal clear behavior for interruption, failure, and empty idle state.

- [ ] **Step 4: Run renderer tests and verify GREEN**

Run:

```powershell
./gradlew.bat :test --tests "io.openeden.cli.render.InlineCliRendererTest"
```

Expected: PASS with one clear and one history write.

- [ ] **Step 5: Commit completion handoff**

```powershell
git add src/main/kotlin/io/openeden/cli/render/InlineCliRenderer.kt src/test/kotlin/io/openeden/cli/render/InlineCliRendererTest.kt
git commit -m "fix(cli): hand off completed activity before history"
```

### Task 5: Prove The Integrated Windows Flow

**Files:**
- Modify: `docs/superpowers/specs/2026-07-16-cli-inline-input-ownership-design.md`
- Modify: `docs/superpowers/plans/2026-07-16-cli-inline-input-ownership.md`

- [ ] **Step 1: Run focused PTY verification**

Run the PTY test three times with task reruns:

```powershell
1..3 | ForEach-Object {
    ./gradlew.bat :test --tests "io.openeden.cli.terminal.CliPseudoTerminalTest" --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "PTY run $_ failed" }
}
```

Expected: all three runs pass; fixed transcript row remains stable, labels are complete, and both turns remain in scrollback.

- [ ] **Step 2: Update superseded `Status` documentation**

Replace the old width/cache workaround sections with a concise note that inline activity is rendered by `OpenEdenLineReader.post`; `Status` and scroll-region resizing are no longer part of the CLI.

- [ ] **Step 3: Run the complete matrix from clean task outputs**

Run:

```powershell
./gradlew.bat test --rerun-tasks
```

Expected: 0 failures and 0 errors. The existing Windows symbolic-link permission test may remain skipped.

- [ ] **Step 4: Build the distributable CLI**

Run:

```powershell
./gradlew.bat installDist
```

Expected: `BUILD SUCCESSFUL` and an updated `build/install/openeden` distribution.

- [ ] **Step 5: Commit documentation and verification state**

```powershell
git add docs/superpowers/specs/2026-07-16-cli-inline-input-ownership-design.md docs/superpowers/plans/2026-07-16-cli-inline-input-ownership.md
git commit -m "docs(cli): document line reader activity ownership"
```

- [ ] **Step 6: Request real Windows Terminal acceptance**

Run the rebuilt CLI in the user's existing Windows Terminal and send a response that produces several deltas. Confirm that `[status]` and `ATRI:` appear beneath the prompt, same-height deltas do not move prior transcript rows, Unicode editing remains correct, and natural scrolling occurs only when the activity gains visible lines or exceeds the viewport.
