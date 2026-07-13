# Polished CLI Terminal UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a cross-platform, Codex-quality OpenEden terminal client with default inline conversation, switchable full-screen mode, safe structured streaming, hidden-by-default diagnostics, and reliable Windows Unicode behavior.

**Architecture:** JLine is the only owner of terminal input, raw mode, capabilities, and alternate-screen lifecycle. Mordant renders Markdown into the JLine-owned output path, while one immutable `CliUiState` and reducer drive both inline and full-screen renderers. The existing server runtime remains authoritative: a shared transaction implementation emits coroutine `Flow` events, validates the full LLM schema, and commits vector and memory state only after success.

**Tech Stack:** Kotlin 2.3.21, JDK 21, coroutines/Flow 1.11.0, JLine 4.3.1 with JNI terminal provider, Mordant 3.0.1 plus `mordant-markdown`, Ktor 3.5.0, kotlinx.serialization 1.11.0, pty4j 0.13.12 for pseudo-terminal tests.

---

## Execution Preconditions

- Read `AGENTS.md` and `docs/superpowers/specs/2026-07-13-cli-terminal-ui-design.md` before implementation.
- Use `superpowers:using-git-worktrees` when execution starts. The current checkout contains unrelated user changes in `AGENTS.md` and `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`; preserve and deliberately carry forward overlapping on-disk changes rather than overwriting them.
- Run Gradle with JDK 21. In the current environment:

```powershell
$env:JAVA_HOME = 'F:\SDK\JDK21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Expected: Java 21.

- Keep the existing `POST /api/v1/chat`, `chat`, and `state` behavior passing after every task.
- Do not expose persona data, prompt text, internal reasoning, memories, or raw trace attributes through public stream events.

## File Map

### Build and configuration

- Modify `gradle/libs.versions.toml`: pin JLine, Mordant, and pty4j versions and coordinates.
- Modify `build.gradle.kts`: add terminal/Markdown runtime dependencies and pty4j tests; remove reliance on global JVM encoding flags after explicit streams exist.
- Modify `server/build.gradle.kts`: no SSE plugin is needed; use Ktor's non-blocking byte writer already available from server core.
- Modify `server/src/main/resources/application.yaml`: add disabled-by-default diagnostics configuration.
- Modify `.env.example`: document diagnostics and redirected-stream encoding variables.

### CLI terminal and presentation

- Create `src/main/kotlin/io/openeden/terminal/TerminalEncodingProfile.kt`: explicit redirected-stream charsets.
- Create `src/main/kotlin/io/openeden/terminal/CliTextStreams.kt`: BOM-aware plain input and explicit output writers.
- Create `src/main/kotlin/io/openeden/terminal/CliMode.kt`: inline/full-screen enum.
- Create `src/main/kotlin/io/openeden/terminal/CliMessage.kt`: immutable rendered-message model.
- Create `src/main/kotlin/io/openeden/terminal/CliDiagnostics.kt`: presentation-only diagnostic snapshot.
- Create `src/main/kotlin/io/openeden/terminal/CliUiState.kt`: immutable presentation state.
- Create `src/main/kotlin/io/openeden/terminal/CliEvent.kt`: local input and remote stream events.
- Create `src/main/kotlin/io/openeden/terminal/CliReducer.kt`: pure UI state transitions.
- Create `src/main/kotlin/io/openeden/terminal/CliCommand.kt`: slash-command model.
- Create `src/main/kotlin/io/openeden/terminal/CliCommandParser.kt`: parsing and completion.
- Create `src/main/kotlin/io/openeden/terminal/TerminalSession.kt`: terminal ownership interface.
- Create `src/main/kotlin/io/openeden/terminal/JLineTerminalSession.kt`: JLine terminal, reader, widgets, and lifecycle.
- Create `src/main/kotlin/io/openeden/terminal/MarkdownTextRenderer.kt`: Mordant Markdown adapter.
- Create `src/main/kotlin/io/openeden/terminal/CliRenderer.kt`: renderer contract.
- Create `src/main/kotlin/io/openeden/terminal/InlineCliRenderer.kt`: native-scrollback active-region renderer.
- Create `src/main/kotlin/io/openeden/terminal/FrameDiff.kt`: pure full-screen row diff.
- Create `src/main/kotlin/io/openeden/terminal/FullScreenCliRenderer.kt`: alternate-screen renderer.
- Create `src/main/kotlin/io/openeden/terminal/CliSessionController.kt`: single-owner event loop and request cancellation.
- Modify `src/main/kotlin/io/openeden/Main.kt`, `OpenEdenCli.kt`, `CliInput.kt`, and `StdinCliInput.kt`: route interactive runs through the terminal stack while keeping one-shot compatibility.

### Streaming runtime and provider

- Create `core/src/commonMain/kotlin/io/openeden/llm/LlmStreamEvent.kt`: provider-neutral stream events.
- Create `core/src/commonMain/kotlin/io/openeden/llm/StreamingLlmClient.kt`: strict-stream capability contract.
- Create `core/src/commonMain/kotlin/io/openeden/runtime/DevelopmentMessageEvent.kt`: internal turn stages, response deltas, and completion.
- Modify `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`: one transaction implementation for buffered and streamed callers.
- Create `core/src/jvmMain/kotlin/io/openeden/llm/StrictOutputStreamDecoder.kt`: incremental structured-output decoder.
- Modify `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`: OpenAI SSE request and strict decoder integration, preserving existing user logging changes unless separately revised.

### Server and client protocol

- Create `server/src/main/kotlin/ChatStreamEventDto.kt`: serializable public SSE payload.
- Create `server/src/main/kotlin/SseEventWriter.kt`: non-blocking SSE framing.
- Create `server/src/main/kotlin/DiagnosticsAccess.kt`: enable/token policy.
- Create `server/src/main/kotlin/DiagnosticStateDto.kt`: authorized diagnostic snapshot.
- Modify `server/src/main/kotlin/Routing.kt` and `Runtime.kt`: stream route, diagnostics route, and attributes.
- Create `src/main/kotlin/io/openeden/client/ChatStreamEvent.kt`: client stream model.
- Create `src/main/kotlin/io/openeden/client/SseEventParser.kt`: incremental client parser.
- Create `src/main/kotlin/io/openeden/client/DiagnosticState.kt`: client diagnostic model.
- Modify `src/main/kotlin/io/openeden/client/OpenEdenServerApi.kt` and `OpenEdenServerClient.kt`: `Flow` stream and diagnostics methods.

### Tests and documentation

- Add focused tests beside each new responsibility under `src/test`, `core/src/commonTest`, `core/src/jvmTest`, and `server/src/test`.
- Create `src/test/kotlin/io/openeden/terminal/CliPseudoTerminalTest.kt`: pty4j interaction coverage.
- Create `scripts/verify-cli-unicode.ps1`: Windows redirected-byte and interactive smoke checks.
- Modify `README.md` and `README.zh-CN.md`: modes, commands, encoding contract, diagnostics, and fallback behavior.

---

### Task 1: Lock Terminal Dependencies and Replace Global Encoding Assumptions

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/io/openeden/terminal/TerminalEncodingProfile.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliTextStreams.kt`
- Modify: `src/main/kotlin/io/openeden/StdinCliInput.kt`
- Modify: `src/main/kotlin/io/openeden/Main.kt`
- Test: `src/test/kotlin/io/openeden/terminal/TerminalEncodingProfileTest.kt`
- Test: `src/test/kotlin/io/openeden/terminal/CliTextStreamsTest.kt`

- [ ] **Step 1: Add failing encoding-profile tests**

```kotlin
class TerminalEncodingProfileTest {
    @Test
    fun `redirected streams default to utf8`() {
        val profile = TerminalEncodingProfile.fromEnvironment(emptyMap())
        assertEquals(StandardCharsets.UTF_8, profile.stdin)
        assertEquals(StandardCharsets.UTF_8, profile.stdout)
        assertEquals(StandardCharsets.UTF_8, profile.stderr)
    }

    @Test
    fun `legacy encodings require an explicit supported name`() {
        val profile = TerminalEncodingProfile.fromEnvironment(
            mapOf("OPENEDEN_STDIN_ENCODING" to "GBK", "OPENEDEN_STDOUT_ENCODING" to "GBK"),
        )
        assertEquals(Charset.forName("GBK"), profile.stdin)
        assertFailsWith<IllegalArgumentException> {
            TerminalEncodingProfile.fromEnvironment(mapOf("OPENEDEN_STDOUT_ENCODING" to "not-a-charset"))
        }
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.TerminalEncodingProfileTest"
```

Expected: FAIL because `TerminalEncodingProfile` does not exist.

- [ ] **Step 3: Add pinned dependencies and the encoding profile**

Add to `gradle/libs.versions.toml`:

```toml
[versions]
jline = "4.3.1"
mordant = "3.0.1"
pty4j = "0.13.12"

[libraries]
jline-terminal = { module = "org.jline:jline-terminal", version.ref = "jline" }
jline-terminal-jni = { module = "org.jline:jline-terminal-jni", version.ref = "jline" }
jline-reader = { module = "org.jline:jline-reader", version.ref = "jline" }
mordant = { module = "com.github.ajalt.mordant:mordant", version.ref = "mordant" }
mordant-markdown = { module = "com.github.ajalt.mordant:mordant-markdown", version.ref = "mordant" }
pty4j = { module = "org.jetbrains.pty4j:pty4j", version.ref = "pty4j" }
```

Add to root `build.gradle.kts`:

```kotlin
implementation(libs.jline.terminal)
implementation(libs.jline.terminal.jni)
implementation(libs.jline.reader)
implementation(libs.mordant)
implementation(libs.mordant.markdown)
testImplementation(libs.pty4j)
```

Create `TerminalEncodingProfile.kt`:

```kotlin
data class TerminalEncodingProfile(
    val stdin: Charset,
    val stdout: Charset,
    val stderr: Charset,
) {
    companion object {
        fun utf8() = TerminalEncodingProfile(StandardCharsets.UTF_8, StandardCharsets.UTF_8, StandardCharsets.UTF_8)

        fun fromEnvironment(environment: Map<String, String>): TerminalEncodingProfile {
            fun charset(name: String): Charset {
                val configured = environment[name]?.takeIf(String::isNotBlank) ?: "UTF-8"
                return runCatching { Charset.forName(configured) }
                    .getOrElse { throw IllegalArgumentException("Unsupported $name charset: $configured", it) }
            }
            return TerminalEncodingProfile(
                stdin = charset("OPENEDEN_STDIN_ENCODING"),
                stdout = charset("OPENEDEN_STDOUT_ENCODING"),
                stderr = charset("OPENEDEN_STDERR_ENCODING"),
            )
        }
    }
}
```

- [ ] **Step 4: Add failing BOM and exact-byte stream tests**

```kotlin
@Test
fun `utf8 input consumes one bom and output never writes a bom`() {
    val input = ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "中文".encodeToByteArray())
    val output = ByteArrayOutputStream()
    val streams = CliTextStreams.create(input, output, ByteArrayOutputStream(), TerminalEncodingProfile.utf8())
    assertEquals("中文", streams.reader.readText())
    streams.out.print("中文")
    streams.out.flush()
    assertContentEquals("中文".encodeToByteArray(), output.toByteArray())
}
```

- [ ] **Step 5: Implement explicit plain streams and inject the reader**

Create `CliTextStreams.kt` with a `PushbackInputStream` that checks exactly the first three bytes for a UTF-8 BOM when `stdin == UTF_8`, unread all non-BOM bytes, and constructs `InputStreamReader`/`PrintWriter` with the resolved charsets. Add:

```kotlin
data class CliTextStreams(val reader: Reader, val out: PrintWriter, val err: PrintWriter) {
    companion object {
        fun create(input: InputStream, output: OutputStream, error: OutputStream, profile: TerminalEncodingProfile): CliTextStreams {
            val source = if (profile.stdin == StandardCharsets.UTF_8) input.withOptionalUtf8BomConsumed() else input
            return CliTextStreams(
                reader = BufferedReader(InputStreamReader(source, profile.stdin)),
                out = PrintWriter(OutputStreamWriter(output, profile.stdout), true),
                err = PrintWriter(OutputStreamWriter(error, profile.stderr), true),
            )
        }
    }
}
```

Change `StdinCliInput` to accept a `Reader`, and change `Main.kt` to construct `CliTextStreams` from the environment. Remove `configureUtf8Console()` and all `System.setOut`/`System.setErr` calls. Keep `OpenEdenCli` behavior unchanged for this task.

- [ ] **Step 6: Run focused and existing CLI tests**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.*" --tests "io.openeden.OpenEdenCliTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add gradle/libs.versions.toml build.gradle.kts src/main/kotlin/io/openeden/Main.kt src/main/kotlin/io/openeden/StdinCliInput.kt src/main/kotlin/io/openeden/terminal src/test/kotlin/io/openeden/terminal
git commit -m "feat(cli): establish unicode terminal foundation"
```

### Task 2: Define the Shared UI State and Pure Reducer

**Files:**
- Create: `src/main/kotlin/io/openeden/terminal/CliMode.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliMessage.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliDiagnostics.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliUiState.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliEvent.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliReducer.kt`
- Test: `src/test/kotlin/io/openeden/terminal/CliReducerTest.kt`

- [ ] **Step 1: Write reducer tests for vertical turns, streaming, cancellation, and mode toggles**

```kotlin
class CliReducerTest {
    @Test
    fun `stream deltas update only the provisional assistant message`() {
        val initial = CliUiState.initial("local").reduce(CliEvent.Submitted("你好", "turn-1"))
        val updated = initial.reduce(CliEvent.ResponseDelta("回答"))
        assertEquals(listOf(CliRole.USER, CliRole.ASSISTANT), updated.messages.map(CliMessage::role))
        assertEquals("回答", updated.messages.last().markdown)
        assertTrue(updated.messages.last().provisional)
    }

    @Test
    fun `cancel marks provisional text interrupted and never completed`() {
        val state = CliUiState.initial("local")
            .reduce(CliEvent.Submitted("hello", "turn-1"))
            .reduce(CliEvent.ResponseDelta("partial"))
            .reduce(CliEvent.RequestInterrupted)
        assertEquals(CliMessageStatus.INTERRUPTED, state.messages.last().status)
        assertFalse(state.requestActive)
    }

    @Test
    fun `mode toggle never changes session or messages`() {
        val state = CliUiState.initial("local").reduce(CliEvent.Submitted("hello", "turn-1"))
        val toggled = state.reduce(CliEvent.ModeSelected(CliMode.FULL_SCREEN))
        assertEquals(state.sessionId, toggled.sessionId)
        assertEquals(state.messages, toggled.messages)
    }
}
```

- [ ] **Step 2: Verify the reducer tests fail**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.CliReducerTest"
```

Expected: FAIL because the UI model does not exist.

- [ ] **Step 3: Implement focused immutable types**

Use one public primary type per file:

```kotlin
enum class CliMode { INLINE, FULL_SCREEN }
enum class CliRole { USER, ASSISTANT, SYSTEM }
enum class CliMessageStatus { COMPLETE, STREAMING, INTERRUPTED, FAILED }

data class CliMessage(
    val id: String,
    val role: CliRole,
    val markdown: String,
    val status: CliMessageStatus,
) {
    val provisional: Boolean get() = status == CliMessageStatus.STREAMING
}

data class CliDiagnostics(
    val vector: List<Float>,
    val omega: Float,
    val shockActive: Boolean,
    val shockIntensity: Float?,
    val evolutionIndex: Long,
    val derivedDissonance: Float,
)

data class CliUiState(
    val sessionId: String,
    val mode: CliMode = CliMode.INLINE,
    val messages: List<CliMessage> = emptyList(),
    val requestActive: Boolean = false,
    val stage: String? = null,
    val diagnosticsVisible: Boolean = false,
    val diagnostics: CliDiagnostics? = null,
    val notice: String? = null,
    val columns: Int = 80,
    val rows: Int = 24,
) {
    companion object { fun initial(userId: String) = CliUiState(sessionId = "CLI:$userId") }
}
```

Define `CliEvent` cases for submitted text, accepted request, safe stage, response delta, completed response, interruption, failure, mode selection, diagnostics visibility/data, resize, and clear-visible-history.

```kotlin
sealed interface CliEvent {
    data class Submitted(val text: String, val id: String) : CliEvent
    data class RequestAccepted(val requestId: String) : CliEvent
    data class StageChanged(val value: String) : CliEvent
    data class ResponseDelta(val text: String) : CliEvent
    data class RequestCompleted(val response: String) : CliEvent
    data object RequestInterrupted : CliEvent
    data class RequestFailed(val message: String) : CliEvent
    data class ModeSelected(val mode: CliMode) : CliEvent
    data class DiagnosticsVisibilityChanged(val visible: Boolean) : CliEvent
    data class DiagnosticsLoaded(val value: CliDiagnostics) : CliEvent
    data class Resized(val columns: Int, val rows: Int) : CliEvent
    data class Notice(val message: String) : CliEvent
    data object ClearVisibleHistory : CliEvent
}
```

- [ ] **Step 4: Implement a pure exhaustive reducer**

Implement `fun CliUiState.reduce(event: CliEvent): CliUiState` with these rules:

```kotlin
when (event) {
    is CliEvent.Submitted -> copy(
        messages = messages + CliMessage(event.id, CliRole.USER, event.text, CliMessageStatus.COMPLETE) +
            CliMessage("${event.id}:assistant", CliRole.ASSISTANT, "", CliMessageStatus.STREAMING),
        requestActive = true,
        stage = "preparing",
        notice = null,
    )
    is CliEvent.ResponseDelta -> updateLastAssistant { it.copy(markdown = it.markdown + event.text) }
    is CliEvent.RequestCompleted -> updateLastAssistant {
        it.copy(markdown = event.response.ifBlank { it.markdown }, status = CliMessageStatus.COMPLETE)
    }.copy(requestActive = false, stage = null)
    CliEvent.RequestInterrupted -> updateLastAssistant { it.copy(status = CliMessageStatus.INTERRUPTED) }
        .copy(requestActive = false, stage = null, notice = "Generation interrupted")
    is CliEvent.ModeSelected -> copy(mode = event.mode)
    is CliEvent.RequestAccepted -> copy(stage = "preparing")
    is CliEvent.StageChanged -> copy(stage = event.value)
    is CliEvent.RequestFailed -> updateLastAssistant { it.copy(status = CliMessageStatus.FAILED) }
        .copy(requestActive = false, stage = null, notice = event.message)
    is CliEvent.DiagnosticsVisibilityChanged -> copy(diagnosticsVisible = event.visible, diagnostics = diagnostics.takeIf { event.visible })
    is CliEvent.DiagnosticsLoaded -> copy(diagnostics = event.value)
    is CliEvent.Resized -> copy(columns = event.columns, rows = event.rows)
    is CliEvent.Notice -> copy(notice = event.message)
    CliEvent.ClearVisibleHistory -> copy(messages = emptyList(), notice = "Visible conversation cleared")
}
```

Keep update helpers private and allocation-conscious; copy the message list only for events that change it.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.CliReducerTest"
git add src/main/kotlin/io/openeden/terminal src/test/kotlin/io/openeden/terminal/CliReducerTest.kt
git commit -m "feat(cli): add shared terminal ui state"
```

Expected: PASS and a focused UI-state commit.

### Task 3: Add Slash Commands and Completion

**Files:**
- Create: `src/main/kotlin/io/openeden/terminal/CliCommand.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliCommandParser.kt`
- Test: `src/test/kotlin/io/openeden/terminal/CliCommandParserTest.kt`

- [ ] **Step 1: Write parser and completion tests**

```kotlin
@Test
fun `parses mode inspect clear and exit commands`() {
    assertEquals(CliCommand.Mode(CliMode.FULL_SCREEN), parser.parse("/mode full"))
    assertEquals(CliCommand.Inspect(true), parser.parse("/inspect on"))
    assertEquals(CliCommand.Clear, parser.parse("/clear"))
    assertEquals(CliCommand.Exit, parser.parse("/exit"))
}

@Test
fun `completion is stable and prefix filtered`() {
    assertEquals(listOf("/mode"), parser.complete("/mo").map(CommandCandidate::value))
    assertEquals(listOf("full", "inline"), parser.complete("/mode ").map(CommandCandidate::value))
}
```

- [ ] **Step 2: Run the failing test**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.CliCommandParserTest"
```

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement an explicit command model and parser**

```kotlin
sealed interface CliCommand {
    data object Help : CliCommand
    data object State : CliCommand
    data class Mode(val mode: CliMode) : CliCommand
    data class Inspect(val visible: Boolean) : CliCommand
    data object Clear : CliCommand
    data object Exit : CliCommand
    data class Unknown(val name: String) : CliCommand
}

class CliCommandParser {
    fun parse(input: String): CliCommand {
        val parts = input.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return when (parts.firstOrNull()) {
            "/help" -> CliCommand.Help
            "/state" -> CliCommand.State
            "/mode" -> CliCommand.Mode(requireNotNull(parts.getOrNull(1)?.toMode()) { "Usage: /mode inline|full" })
            "/inspect" -> CliCommand.Inspect(requireNotNull(parts.getOrNull(1)?.toSwitch()) { "Usage: /inspect on|off" })
            "/clear" -> CliCommand.Clear
            "/exit" -> CliCommand.Exit
            else -> CliCommand.Unknown(parts.firstOrNull().orEmpty())
        }
    }
}
```

Return immutable `CommandCandidate(value, description, shortcut)` entries for completion. Do not put persona text in command descriptions.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.CliCommandParserTest"
git add src/main/kotlin/io/openeden/terminal/CliCommand.kt src/main/kotlin/io/openeden/terminal/CliCommandParser.kt src/test/kotlin/io/openeden/terminal/CliCommandParserTest.kt
git commit -m "feat(cli): add local command completion"
```

Expected: PASS.

### Task 4: Make JLine the Single Interactive Terminal Owner

**Files:**
- Create: `src/main/kotlin/io/openeden/terminal/TerminalSession.kt`
- Create: `src/main/kotlin/io/openeden/terminal/JLineTerminalSession.kt`
- Test: `src/test/kotlin/io/openeden/terminal/JLineTerminalSessionTest.kt`

- [ ] **Step 1: Write terminal lifecycle and key-widget tests**

Create a JLine `DumbTerminal` backed by byte-array streams and assert:

```kotlin
@Test
fun `terminal builder uses utf8 and configured jni provider`() {
    JLineTerminalSession.createForTest(input, output).use { session ->
        assertEquals(StandardCharsets.UTF_8, session.terminal.inputEncoding())
        assertEquals(StandardCharsets.UTF_8, session.terminal.outputEncoding())
        assertEquals(CliTerminalEvent.Cancel, session.translateWidget("openeden-cancel"))
        assertEquals(CliTerminalEvent.ToggleMode, session.translateWidget("openeden-toggle-mode"))
    }
}

@Test
fun `close restores cursor raw mode and alternate screen once`() {
    val terminal = RecordingTerminal()
    val session = JLineTerminalSession.fromTerminal(terminal)
    session.enterFullScreen()
    session.close()
    session.close()
    assertEquals(1, terminal.exitAlternateScreenCalls)
    assertEquals(1, terminal.restoreAttributesCalls)
}
```

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.JLineTerminalSessionTest"
```

Expected: FAIL because the terminal session does not exist.

- [ ] **Step 3: Define the terminal contract**

```kotlin
sealed interface CliTerminalEvent {
    data class Submit(val text: String) : CliTerminalEvent
    data object Cancel : CliTerminalEvent
    data object ToggleMode : CliTerminalEvent
    data object ToggleDiagnostics : CliTerminalEvent
    data object EndOfFile : CliTerminalEvent
}

interface TerminalSession : AutoCloseable {
    val terminal: Terminal
    val lineReader: LineReader
    fun events(): Flow<CliTerminalEvent>
    fun enterFullScreen(): Boolean
    fun exitFullScreen()
    fun redisplay()
}
```

- [ ] **Step 4: Implement JLine creation, widgets, and structured cleanup**

Build the system terminal with:

```kotlin
TerminalBuilder.builder()
    .name("openeden")
    .system(true)
    .encoding(StandardCharsets.UTF_8)
    .stdinEncoding(StandardCharsets.UTF_8)
    .stdoutEncoding(StandardCharsets.UTF_8)
    .stderrEncoding(StandardCharsets.UTF_8)
    .providers("jni,exec")
    .build()
```

On Windows, inspect the selected provider and disable rich/full-screen capabilities with a warning if JNI did not initialize. Configure `LineReaderBuilder` with history file, bracketed paste, a `StringsCompleter` adapter over `CliCommandParser`, and named widgets:

```kotlin
widgets["openeden-cancel"] = Widget { eventChannel.trySend(CliTerminalEvent.Cancel).isSuccess }
widgets["openeden-toggle-mode"] = Widget { eventChannel.trySend(CliTerminalEvent.ToggleMode).isSuccess }
widgets["openeden-toggle-diagnostics"] = Widget { eventChannel.trySend(CliTerminalEvent.ToggleDiagnostics).isSuccess }
widgets["openeden-newline"] = Widget { lineReader.buffer.write("\n"); true }
```

Bind `Esc`, `Ctrl+T`, `Alt+I`, and `Alt+Enter` in a dedicated keymap. Run `readLine()` only on `Dispatchers.IO.limitedParallelism(1)`, translate `UserInterruptException` and `EndOfFileException`, and close the event channel in `finally`.

- [ ] **Step 5: Run tests and verify no global console mutation remains**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.JLineTerminalSessionTest"
rg -n "System\.set(In|Out|Err)|chcp" src/main/kotlin
```

Expected: tests PASS; `rg` returns no matches.

- [ ] **Step 6: Commit**

```powershell
git add src/main/kotlin/io/openeden/terminal/TerminalSession.kt src/main/kotlin/io/openeden/terminal/JLineTerminalSession.kt src/test/kotlin/io/openeden/terminal/JLineTerminalSessionTest.kt
git commit -m "feat(cli): own interactive input with jline"
```

### Task 5: Render Markdown in Inline and Full-Screen Modes

**Files:**
- Create: `src/main/kotlin/io/openeden/terminal/MarkdownTextRenderer.kt`
- Create: `src/main/kotlin/io/openeden/terminal/CliRenderer.kt`
- Create: `src/main/kotlin/io/openeden/terminal/InlineCliRenderer.kt`
- Create: `src/main/kotlin/io/openeden/terminal/FrameDiff.kt`
- Create: `src/main/kotlin/io/openeden/terminal/FullScreenCliRenderer.kt`
- Test: `src/test/kotlin/io/openeden/terminal/MarkdownTextRendererTest.kt`
- Test: `src/test/kotlin/io/openeden/terminal/InlineCliRendererTest.kt`
- Test: `src/test/kotlin/io/openeden/terminal/FrameDiffTest.kt`
- Test: `src/test/kotlin/io/openeden/terminal/FullScreenCliRendererTest.kt`

- [ ] **Step 1: Write failing Markdown and vertical-layout snapshots**

```kotlin
@Test
fun `renders chinese markdown and fenced code within requested width`() {
    val rendered = renderer.render("# 标题\n\n- 第一项\n\n```kotlin\nval 文本 = \"你好\"\n```", width = 48)
    assertContains(rendered, "标题")
    assertContains(rendered, "第一项")
    assertContains(rendered, "val 文本")
    assertTrue(rendered.lines().all { visibleWidth(it) <= 48 })
}

@Test
fun `inline renderer keeps user and assistant on separate vertical blocks`() {
    val output = renderer.render(stateWithTwoMessages(), width = 80)
    assertTrue(output.indexOf("you\n你好") < output.indexOf("atri\n回答"))
    assertFalse(output.contains("you    你好    atri"))
}
```

- [ ] **Step 2: Write failing frame-diff tests**

```kotlin
@Test
fun `diff writes only changed rows`() {
    assertEquals(listOf(RowChange(1, "changed")), FrameDiff.between(listOf("same", "old"), listOf("same", "changed")))
}

@Test
fun `full screen falls back below minimum dimensions`() {
    assertEquals(RenderDecision.FallbackToInline, renderer.decision(columns = 72, rows = 18))
}
```

- [ ] **Step 3: Run tests and verify failure**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.*RendererTest" --tests "io.openeden.terminal.FrameDiffTest"
```

Expected: FAIL because renderers do not exist.

- [ ] **Step 4: Implement the Mordant adapter and renderer contract**

```kotlin
class MarkdownTextRenderer(private val terminal: com.github.ajalt.mordant.terminal.Terminal) {
    fun render(markdown: String, width: Int): String {
        val lines = Markdown(markdown, showHtml = false).render(terminal, width)
        return terminal.render(lines)
    }
}

interface CliRenderer : AutoCloseable {
    fun render(previous: CliUiState?, current: CliUiState, size: Size)
    override fun close() = Unit
}
```

Construct Mordant with a terminal interface that writes only to the JLine writer. Never let Mordant read stdin or own native terminal state.

- [ ] **Step 5: Implement inline active-region rendering**

Commit completed messages through `LineReader.printAbove`. Use JLine `Display` for only the current streaming block, status row, completion candidates, and editor. Track the last committed message ID so old terminal history is never redrawn. Limit content width to `minOf(columns, 96)` and preserve vertical blocks.

- [ ] **Step 6: Implement full-screen frame rendering**

Use JLine `InfoCmp.Capability.enter_ca_mode`, `exit_ca_mode`, `cursor_invisible`, and `cursor_normal`. Build logical rows for header, bounded conversation, editor, status, compact session rail, and optional diagnostics. Apply `FrameDiff` and move/write only changed rows. Return to inline mode below 80 columns or 24 rows.

- [ ] **Step 7: Run renderer tests and commit**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.*RendererTest" --tests "io.openeden.terminal.FrameDiffTest"
git add src/main/kotlin/io/openeden/terminal src/test/kotlin/io/openeden/terminal
git commit -m "feat(cli): render markdown in dual terminal modes"
```

Expected: PASS.

### Task 6: Add a Shared Transactional Runtime Event Flow

**Files:**
- Create: `core/src/commonMain/kotlin/io/openeden/llm/LlmStreamEvent.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/llm/StreamingLlmClient.kt`
- Create: `core/src/commonMain/kotlin/io/openeden/runtime/DevelopmentMessageEvent.kt`
- Modify: `core/src/commonMain/kotlin/io/openeden/runtime/MessagePipeline.kt`
- Test: `core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineStreamingTest.kt`
- Test support: `core/src/commonTest/kotlin/io/openeden/runtime/StreamingTestFixtures.kt`

- [ ] **Step 1: Write failing transaction and cancellation tests**

```kotlin
@Test
fun `streamed turn emits deltas then commits once after validation`() = runTest {
    val store = CountingSessionStateStore()
    val pipeline = pipeline(store, StreamingStub("你", "好", validOutput("你好")))
    val events = pipeline.handleStreaming(request()).toList()
    assertEquals(listOf("你", "好"), events.filterIsInstance<DevelopmentMessageEvent.ResponseDelta>().map { it.text })
    assertIs<DevelopmentMessageEvent.Completed>(events.last())
    assertEquals(1L, store.read("CLI:local").evolutionIndex)
}

@Test
fun `cancelling collection before completion performs no state write`() = runTest {
    val store = CountingSessionStateStore()
    pipeline(store, SuspendedStreamingStub()).handleStreaming(request()).take(2).collect()
    assertEquals(0L, store.readOrCreate("CLI:local").evolutionIndex)
    assertEquals(0, store.writeCount)
}
```

Create `StreamingTestFixtures.kt` in the same task with concrete fixtures used above:

```kotlin
internal class StreamingStub(
    private val deltas: List<String>,
    private val output: LlmOutput,
) : StreamingLlmClient {
    override val supportsStrictStructuredStreaming = true
    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = flow {
        deltas.forEach { emit(LlmStreamEvent.ResponseDelta(it)) }
        emit(LlmStreamEvent.Completed(output))
    }
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = output
}

internal class SuspendedStreamingStub : StreamingLlmClient {
    override val supportsStrictStructuredStreaming = true
    override fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent> = flow { awaitCancellation() }
    override suspend fun complete(prompt: BuiltPrompt): LlmOutput = awaitCancellation()
}

internal fun validOutput(response: String) = LlmOutput(
    internalLogic = "logic",
    vectorDelta = listOf("L", "P", "E", "S", "tau", "V", "M", "F").associateWith { 0.0f },
    response = response,
)

internal class CountingSessionStateStore : SessionStateStore {
    private val delegate = MutableSessionStateStore()
    var writeCount = 0
        private set
    override suspend fun read(sessionId: String) = delegate.read(sessionId)
    override suspend fun readOrCreate(sessionId: String) = delegate.readOrCreate(sessionId)
    override suspend fun write(state: SessionState) { writeCount += 1; delegate.write(state) }
    override suspend fun sessionIds() = delegate.sessionIds()
}
```

Define `request()` as `DevelopmentMessageRequest("CLI", "local", "local", "hello")` and copy the focused `PersonaConfig` fixture from `MessagePipelineTest` with all required prompt-section keys. The local `pipeline(store, llmClient)` helper must pass that persona and store into `DevelopmentMessagePipeline.create`.

- [ ] **Step 2: Run and verify failure**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.MessagePipelineStreamingTest"
```

Expected: FAIL because streaming contracts do not exist.

- [ ] **Step 3: Define provider and pipeline event contracts**

```kotlin
sealed interface LlmStreamEvent {
    data class ResponseDelta(val text: String) : LlmStreamEvent
    data class Completed(val output: LlmOutput) : LlmStreamEvent
}

interface StreamingLlmClient : LlmClient {
    val supportsStrictStructuredStreaming: Boolean
    fun stream(prompt: BuiltPrompt): Flow<LlmStreamEvent>
}

sealed interface DevelopmentMessageEvent {
    data class Stage(val value: DevelopmentStage) : DevelopmentMessageEvent
    data class ResponseDelta(val text: String) : DevelopmentMessageEvent
    data class Completed(val result: DevelopmentMessageResult) : DevelopmentMessageEvent
}
```

Use `DevelopmentStage.PREPARING`, `GENERATING`, and `FINALIZING`; do not put internal subsystem names in public values.

- [ ] **Step 4: Refactor one transaction implementation**

Add:

```kotlin
fun handleStreaming(request: DevelopmentMessageRequest): Flow<DevelopmentMessageEvent> = flow {
    val sessionId = "${request.platform}:${request.scopeId}"
    turnGate.withSession(sessionId) {
        emit(DevelopmentMessageEvent.Stage(DevelopmentStage.PREPARING))
        handleLocked(request, sessionId) { emit(it) }
    }
}

suspend fun handle(request: DevelopmentMessageRequest): DevelopmentMessageResult =
    handleStreaming(request).filterIsInstance<DevelopmentMessageEvent.Completed>().single().result
```

Inside the existing locked transaction, collect `StreamingLlmClient.stream` when strict streaming is supported; otherwise call `complete` and emit one buffered delta only after validation. Preserve the existing sequence: pre-tick snapshot, quantization/retrieval, prompt, LLM, validator, delta write on the pre-ticked snapshot, shock detection, memory/diary writes, and final result.

Do not catch `CancellationException`. It must leave the function before all write-back code and release the session gate through structured concurrency.

- [ ] **Step 5: Run core invariants and commit**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.MessagePipelineStreamingTest" --tests "io.openeden.runtime.MessagePipelineTest" --tests "io.openeden.runtime.TurnCoordinatorConcurrencyTest"
git add core/src/commonMain/kotlin/io/openeden/llm core/src/commonMain/kotlin/io/openeden/runtime core/src/commonTest/kotlin/io/openeden/runtime/MessagePipelineStreamingTest.kt core/src/commonTest/kotlin/io/openeden/runtime/StreamingTestFixtures.kt
git commit -m "feat(core): expose transactional turn flow"
```

Expected: PASS; buffered callers still receive the same `DevelopmentMessageResult`.

### Task 7: Stream Strict OpenAI Structured Output Safely

**Files:**
- Create: `core/src/jvmMain/kotlin/io/openeden/llm/StrictOutputStreamDecoder.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/llm/OpenAiResponsesLlmClient.kt`
- Test: `core/src/jvmTest/kotlin/io/openeden/llm/StrictOutputStreamDecoderTest.kt`
- Modify test: `src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt`

- [ ] **Step 1: Write decoder tests across arbitrary chunk boundaries**

```kotlin
@Test
fun `emits only decoded response characters after validated prefix`() {
    val json = """{"internal_logic":"logic","vector_delta":{"L":0.0,"P":0.1,"E":0.0,"S":0.0,"tau":0.0,"V":0.0,"M":0.0,"F":0.0},"response":"你\\n好"}"""
    for (split in 1 until json.length) {
        val decoder = StrictOutputStreamDecoder()
        val deltas = decoder.accept(json.take(split)) + decoder.accept(json.drop(split))
        assertEquals("你\n好", deltas.joinToString(""))
        assertEquals("你好".replace("好", "\n好"), decoder.finish().response)
    }
}

@Test
fun `never exposes internal logic or malformed field order`() {
    val decoder = StrictOutputStreamDecoder()
    assertContentEquals(emptyList(), decoder.accept("""{"response":"leak"""))
    assertFailsWith<StructuredStreamException> { decoder.finish() }
}
```

Add cases for escaped quotes, backslashes, split surrogate escapes, all eight exact delta keys, forbidden `D`, missing keys, extra fields, and blank response.

- [ ] **Step 2: Verify decoder tests fail**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.StrictOutputStreamDecoderTest"
```

Expected: FAIL because the decoder does not exist.

- [ ] **Step 3: Implement the incremental decoder**

Implement a focused deterministic scanner that:

```kotlin
class StrictOutputStreamDecoder(private val json: Json = Json) {
    private val raw = StringBuilder()
    private var emittedResponseChars = 0
    private var prefixValidated = false

    fun accept(chunk: String): List<String> {
        raw.append(chunk)
        val responsePrefix = scanRequiredPrefix(raw) ?: return emptyList()
        prefixValidated = true
        val decoded = decodeCompleteResponseCharacters(raw, responsePrefix)
        if (decoded.length <= emittedResponseChars) return emptyList()
        return listOf(decoded.substring(emittedResponseChars).also { emittedResponseChars = decoded.length })
    }

    fun finish(): LlmOutput {
        check(prefixValidated) { "Structured output prefix was not validated" }
        return parseCompleteOutput(raw.toString()).also { output ->
            val validation = LlmOutputValidator.validate(output)
            if (!validation.isValid) throw StructuredStreamException(validation.errors.joinToString("; "))
        }
    }
}
```

`scanRequiredPrefix` must parse strings/escapes and the complete `vector_delta` object structurally; substring or regex matching is forbidden. `decodeCompleteResponseCharacters` releases only complete JSON string characters and retains incomplete escape sequences for the next chunk. `finish` uses kotlinx.serialization for the full object and the existing validator.

- [ ] **Step 4: Add failing OpenAI provider SSE tests**

Mock a `text/event-stream` response with `response.output_text.delta` events containing the structured JSON chunks and a `response.completed` event. Assert `stream=true`, exact schema property order (`internal_logic`, `vector_delta`, `response`), emitted response deltas, and final `LlmOutput`.

- [ ] **Step 5: Implement provider streaming without losing buffered behavior**

Make `OpenAiResponsesLlmClient` implement `StreamingLlmClient`, set `supportsStrictStructuredStreaming = true`, add `stream: Boolean = true` to the provider request, parse provider SSE through `bodyAsChannel`, and feed only `response.output_text.delta` values into `StrictOutputStreamDecoder`. Emit `LlmStreamEvent.Completed(decoder.finish())` on the provider completion event. If an OpenAI-compatible relay ignores `stream=true` and returns normal JSON instead of `text/event-stream`, parse the existing buffered response shape and emit one `Completed` event without public deltas.

Keep `complete` as a buffered collector over `stream` or preserve the current non-stream request behind a shared request builder. Reorder the JSON schema property map to `internal_logic`, `vector_delta`, `response`. Preserve current on-disk prompt logging changes rather than replacing the file wholesale.

- [ ] **Step 6: Run provider and core tests, then commit**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.llm.StrictOutputStreamDecoderTest"
.\gradlew.bat test --tests "io.openeden.llm.OpenAiResponsesLlmClientTest"
git add core/src/jvmMain/kotlin/io/openeden/llm core/src/jvmTest/kotlin/io/openeden/llm src/test/kotlin/io/openeden/llm/OpenAiResponsesLlmClientTest.kt
git commit -m "feat(llm): stream strict structured responses"
```

Expected: PASS.

### Task 8: Expose a Public Non-Blocking SSE Chat Route

**Files:**
- Create: `server/src/main/kotlin/ChatStreamEventDto.kt`
- Create: `server/src/main/kotlin/SseEventWriter.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Modify: `server/src/test/kotlin/ServerApiTest.kt`

- [ ] **Step 1: Write failing SSE route tests**

```kotlin
@Test
fun `stream endpoint emits public ordered events only`() = testApplication {
    val pipeline = DevelopmentMessagePipeline.create(
        personaConfig = testPersona(),
        llmClient = StreamingStub(listOf("你", "好"), validOutput("你好")),
    )
    application {
        attributes.put(PipelineKey, pipeline)
        attributes.put(SessionStateStoreKey, MutableSessionStateStore())
        configureSerialization()
        configureRouting()
    }
    val response = client.post("/api/v1/chat/stream") {
        contentType(ContentType.Application.Json)
        setBody("""{"userId":"local","text":"hello","clientRequestId":"client-1"}""")
    }
    assertEquals(ContentType.Text.EventStream.withCharset(Charsets.UTF_8), response.contentType())
    val text = response.bodyAsText()
    assertTrue(text.indexOf("event: accepted") < text.indexOf("event: response.delta"))
    assertTrue(text.indexOf("event: response.delta") < text.indexOf("event: completed"))
    listOf("internal_logic", "promptPreview", "snapshot_8D", "traceTags").forEach { assertFalse(text.contains(it)) }
}
```

Add blank-input, pipeline-error, and cancellation tests. The cancellation test must assert no completed event and no session write.

- [ ] **Step 2: Verify route tests fail**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.ServerApiTest"
```

Expected: FAIL with 404 for `/api/v1/chat/stream`.

- [ ] **Step 3: Implement serializable public events and SSE framing**

```kotlin
@Serializable
sealed interface ChatStreamEventDto {
    @Serializable @SerialName("accepted") data class Accepted(val requestId: String) : ChatStreamEventDto
    @Serializable @SerialName("stage") data class Stage(val value: String) : ChatStreamEventDto
    @Serializable @SerialName("response.delta") data class ResponseDelta(val text: String) : ChatStreamEventDto
    @Serializable @SerialName("completed") data class Completed(val requestId: String, val status: String = "completed") : ChatStreamEventDto
    @Serializable @SerialName("error") data class Error(val code: String, val message: String, val retryable: Boolean) : ChatStreamEventDto
}
```

`SseEventWriter` writes UTF-8 frames to `ByteWriteChannel`:

```kotlin
suspend fun send(name: String, payload: ChatStreamEventDto) {
    channel.writeStringUtf8("event: $name\n")
    channel.writeStringUtf8("data: ${json.encodeToString(payload)}\n\n")
    channel.flush()
}
```

- [ ] **Step 4: Add the POST stream route**

Use `call.respondBytesWriter(ContentType.Text.EventStream)` and collect `developmentPipeline.handleStreaming`. Map only safe stages, response deltas, and completion. Generate the server request ID independently of the client ID. Let `CancellationException` propagate; map other failures to one safe `error` event without provider bodies or stack traces.

- [ ] **Step 5: Run server compatibility tests and commit**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.ServerApiTest" --tests "io.openeden.server.ServerTest"
git add server/src/main/kotlin/ChatStreamEventDto.kt server/src/main/kotlin/SseEventWriter.kt server/src/main/kotlin/Routing.kt server/src/test/kotlin/ServerApiTest.kt
git commit -m "feat(server): expose public chat event stream"
```

Expected: PASS; old `/api/v1/chat` tests remain unchanged.

### Task 9: Consume SSE as a Client Flow

**Files:**
- Create: `src/main/kotlin/io/openeden/client/ChatStreamEvent.kt`
- Create: `src/main/kotlin/io/openeden/client/SseEventParser.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerApi.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt`
- Modify: `src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt`
- Test: `src/test/kotlin/io/openeden/client/SseEventParserTest.kt`

- [ ] **Step 1: Write parser tests for fragmented UTF-8 and multi-line frames**

```kotlin
@Test
fun `parses arbitrary byte chunks without splitting chinese text`() = runTest {
    val bytes = "event: response.delta\ndata: {\"text\":\"你好\"}\n\n".encodeToByteArray()
    for (split in 1 until bytes.size) {
        val events = SseEventParser().parse(flowOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size))).toList()
        assertEquals(listOf(ChatStreamEvent.ResponseDelta("你好")), events)
    }
}
```

- [ ] **Step 2: Verify parser failure**

```powershell
.\gradlew.bat test --tests "io.openeden.client.SseEventParserTest"
```

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement client events and incremental parser**

Define `ChatStreamEvent` cases matching the public DTO. Parse UTF-8 with a stateful decoder or line reads from Ktor `ByteReadChannel`; collect `event:` and `data:` fields until a blank line, then decode the event-specific payload with kotlinx.serialization. Ignore unknown event names for forward compatibility, but fail with a typed protocol error when a known event has malformed JSON.

- [ ] **Step 4: Add client Flow tests and implementation**

Test that `chatStream("local", "hello", "client-1")` posts to `/api/v1/chat/stream`, sends the client request ID, returns ordered events, and cancels the Ktor response when collection is cancelled.

Add to the API:

```kotlin
fun chatStream(userId: String, text: String, clientRequestId: String): Flow<ChatStreamEvent>
```

Implement with `flow { httpClient.preparePost(...).execute { response -> response.requireSuccess(); parser.parse(response.bodyAsChannel()).collect(::emit) } }`. Do not buffer the entire response.

- [ ] **Step 5: Run client tests and commit**

```powershell
.\gradlew.bat test --tests "io.openeden.client.*"
git add src/main/kotlin/io/openeden/client src/test/kotlin/io/openeden/client
git commit -m "feat(cli): consume server chat stream"
```

Expected: PASS.

### Task 10: Add Disabled-by-Default Authorized Diagnostics

**Files:**
- Create: `server/src/main/kotlin/DiagnosticsAccess.kt`
- Create: `server/src/main/kotlin/DiagnosticStateDto.kt`
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Modify: `server/src/main/resources/application.yaml`
- Modify: `.env.example`
- Create: `src/main/kotlin/io/openeden/client/DiagnosticState.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerApi.kt`
- Modify: `src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt`
- Test: `server/src/test/kotlin/DiagnosticsApiTest.kt`
- Modify test: `src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt`

- [ ] **Step 1: Write server authorization tests**

```kotlin
@Test
fun `diagnostics are not found when disabled`() = testApplication {
    application {
        attributes.put(DiagnosticsAccessKey, DiagnosticsAccess.disabled())
        attributes.put(SessionStateStoreKey, MutableSessionStateStore())
        configureSerialization()
        configureRouting()
    }
    assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/diagnostics?userId=local").status)
}

@Test
fun `enabled diagnostics require bearer token and keep D outside vector`() = testApplication {
    application {
        attributes.put(DiagnosticsAccessKey, DiagnosticsAccess.enabled("secret"))
        attributes.put(SessionStateStoreKey, MutableSessionStateStore())
        configureSerialization()
        configureRouting()
    }
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/diagnostics?userId=local").status)
    val response = client.get("/api/v1/diagnostics?userId=local") { bearerAuth("secret") }
    val body = Json.decodeFromString<DiagnosticStateDto>(response.bodyAsText())
    assertEquals(8, body.vector.size)
    assertNotNull(body.derivedDissonance)
}
```

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.DiagnosticsApiTest"
```

Expected: FAIL because the route does not exist.

- [ ] **Step 3: Implement diagnostics policy and configuration**

```kotlin
data class DiagnosticsAccess private constructor(val enabled: Boolean, private val tokenBytes: ByteArray?) {
    fun authorizes(bearerToken: String?): Boolean = enabled && tokenBytes != null && bearerToken != null &&
        MessageDigest.isEqual(tokenBytes, bearerToken.encodeToByteArray())
    companion object {
        fun disabled() = DiagnosticsAccess(false, null)
        fun enabled(token: String): DiagnosticsAccess {
            require(token.isNotBlank()) { "Diagnostics token is required when diagnostics are enabled" }
            return DiagnosticsAccess(true, token.encodeToByteArray())
        }
    }
}
```

Add `openeden.diagnostics.enabled` and `openeden.diagnostics.token` to `application.yaml`, sourced from `OPENEDEN_ENABLE_CLI_DIAGNOSTICS` and optional `OPENEDEN_CLI_DIAGNOSTICS_TOKEN`. Put the access policy in an application attribute during runtime configuration.

- [ ] **Step 4: Implement the diagnostic DTO and route**

Return only session ID, 8 storage-space values, Omega, Shock active/intensity, evolution index, and `derivedDissonance` as a separate field. Do not return prompts, internal logic, memories, credentials, or unredacted traces. Respond 404 when disabled and 401 when enabled but unauthorized.

- [ ] **Step 5: Add the client method without persisting credentials**

```kotlin
suspend fun diagnostics(userId: String, token: String): DiagnosticState
```

Read the token at application startup from `OPENEDEN_CLI_DIAGNOSTICS_TOKEN`; do not add it to `CliConfig` or `~/.openeden/config.json`. Send it only as `Authorization: Bearer` and map 404/401 to a generic diagnostics-unavailable state in the controller.

Map the transport DTO into the presentation type explicitly:

```kotlin
fun DiagnosticState.toCliDiagnostics() = CliDiagnostics(
    vector = vector,
    omega = omega,
    shockActive = shockActive,
    shockIntensity = shockIntensity,
    evolutionIndex = evolutionIndex,
    derivedDissonance = derivedDissonance,
)
```

- [ ] **Step 6: Run tests and commit**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.DiagnosticsApiTest"
.\gradlew.bat test --tests "io.openeden.client.OpenEdenServerClientTest"
git add server/src/main server/src/test/kotlin/DiagnosticsApiTest.kt src/main/kotlin/io/openeden/client src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt .env.example
git commit -m "feat(server): gate cli runtime diagnostics"
```

Expected: PASS.

### Task 11: Integrate the Controller and Preserve One-Shot Commands

**Files:**
- Create: `src/main/kotlin/io/openeden/terminal/CliSessionController.kt`
- Modify: `src/main/kotlin/io/openeden/OpenEdenCli.kt`
- Modify: `src/main/kotlin/io/openeden/Main.kt`
- Modify: `src/test/kotlin/io/openeden/OpenEdenCliTest.kt`
- Test: `src/test/kotlin/io/openeden/terminal/CliSessionControllerTest.kt`

- [ ] **Step 1: Write controller tests for streaming, mode switching, commands, and cancellation**

```kotlin
@Test
fun `controller streams one request and does not duplicate on mode toggle`() = runTest {
    val api = FakeStreamingApi(listOf(Accepted("r1"), ResponseDelta("你"), ResponseDelta("好"), Completed("r1")))
    val controller = controller(api)
    controller.accept(CliTerminalEvent.Submit("hello"))
    controller.accept(CliTerminalEvent.ToggleMode)
    advanceUntilIdle()
    assertEquals(1, api.streamCalls)
    assertEquals("你好", controller.state.value.messages.last().markdown)
    assertEquals(CliMode.FULL_SCREEN, controller.state.value.mode)
}

@Test
fun `cancel stops active collection and does not retry`() = runTest {
    val api = SuspendedStreamingApi()
    val controller = controller(api)
    controller.accept(CliTerminalEvent.Submit("hello"))
    controller.accept(CliTerminalEvent.Cancel)
    advanceUntilIdle()
    assertEquals(1, api.streamCalls)
    assertEquals(CliMessageStatus.INTERRUPTED, controller.state.value.messages.last().status)
}
```

- [ ] **Step 2: Verify failure**

```powershell
.\gradlew.bat test --tests "io.openeden.terminal.CliSessionControllerTest"
```

Expected: FAIL because the controller does not exist.

- [ ] **Step 3: Implement a single-owner controller**

Use a `Channel<CliEvent>` and one reducer coroutine. Store exactly one active request `Job`. Request collection maps `ChatStreamEvent` into reducer events. Cancellation calls `activeRequest.cancelAndJoin()` and never calls the API again. Coalesce renderer updates to at most 30 frames per second and resize events to one update per 50 ms.

Command behavior:

```kotlin
when (val command = commandParser.parse(text)) {
    CliCommand.Help -> dispatch(CliEvent.Notice(helpText()))
    CliCommand.State -> dispatch(CliEvent.Notice(formatPublicState(api.state(userId))))
    is CliCommand.Mode -> dispatch(CliEvent.ModeSelected(command.mode))
    is CliCommand.Inspect -> setDiagnostics(command.visible)
    CliCommand.Clear -> dispatch(CliEvent.ClearVisibleHistory)
    CliCommand.Exit -> stop()
    is CliCommand.Unknown -> dispatch(CliEvent.Notice("Unknown command: ${command.name}"))
}
```

- [ ] **Step 4: Route only interactive mode through the controller**

Refactor `OpenEdenCli` so empty args create `JLineTerminalSession`, controller, and both renderers. Non-empty `chat` and `state` continue to use explicit plain writers and existing response shapes. `run` closes terminal, renderer, controller, and HTTP client in reverse order through `use`/`finally`; `/exit` never stops the server.

- [ ] **Step 5: Update CLI compatibility tests**

Keep the existing assertions and add:

```kotlin
assertFalse(output.contains("\u001B"))
assertEquals(listOf("hello"), client.messages)
assertEquals(1, client.closeCalls)
```

Inject a fake `TerminalSessionFactory` so unit tests do not require a real console.

- [ ] **Step 6: Run root CLI tests and commit**

```powershell
.\gradlew.bat test --tests "io.openeden.OpenEdenCliTest" --tests "io.openeden.terminal.CliSessionControllerTest"
git add src/main/kotlin/io/openeden src/test/kotlin/io/openeden
git commit -m "feat(cli): run polished interactive session"
```

Expected: PASS.

### Task 12: Add Cross-Platform Pseudo-Terminal Verification and Documentation

**Files:**
- Create: `src/test/kotlin/io/openeden/terminal/CliPseudoTerminalTest.kt`
- Create: `scripts/verify-cli-unicode.ps1`
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify: `.github/workflows/ci.yml` if present; otherwise create `.github/workflows/cli-terminal.yml`

- [ ] **Step 1: Write pty4j interaction tests**

Launch the installed CLI distribution under pty4j with a fake local streaming server. Drive `你好`, history-up, `/mode full`, `/mode inline`, multiline input, bracketed paste, and `Ctrl+D`. Assert exact Unicode text, vertical ordering, no `U+FFFD`, no `?` substitution, and process exit 0.

```kotlin
@Test
fun `chinese input streaming and history round trip through a pseudo terminal`() {
    val process = PtyProcessBuilder(cliCommand).setEnvironment(mapOf("TERM" to "xterm-256color")).start()
    process.outputWriter(StandardCharsets.UTF_8).use { input ->
        input.write("你好\n")
        input.flush()
    }
    val transcript = process.inputReader(StandardCharsets.UTF_8).readText()
    assertContains(transcript, "你好")
    assertFalse(transcript.contains('\uFFFD'))
}
```

- [ ] **Step 2: Add deterministic Windows byte verification**

Create `scripts/verify-cli-unicode.ps1` that:

1. sets JDK 21;
2. runs `installDist`;
3. starts a PowerShell `HttpListener` on an ephemeral loopback port that returns a Chinese health/chat response;
4. creates a temporary `user.home/.openeden/config.json` pointing the installed CLI at that mock server;
5. launches `build/install/openeden/bin/openeden.bat` with redirected stdin/stdout and `JAVA_OPTS=-Duser.home=<temp>`;
6. writes `你好`, newline, `/exit`, newline as UTF-8-with-BOM and UTF-8-without-BOM bytes to the child stdin stream;
7. captures child stdout as bytes and asserts exact UTF-8 text, no output BOM, and no replacement characters;
8. repeats with GBK bytes plus `OPENEDEN_STDIN_ENCODING=GBK` and `OPENEDEN_STDOUT_ENCODING=GBK`;
9. stops the listener in `finally`, removes only the verified temporary directory, prints one PASS/FAIL summary, and exits non-zero on mismatch.

The script must not call `chcp`.

- [ ] **Step 3: Add CI matrix coverage**

Run `test`, `:core:jvmTest`, and `:server:test` on `windows-latest`, `ubuntu-latest`, and `macos-latest` with JDK 21. Run `scripts/verify-cli-unicode.ps1` on Windows. Keep provider/network tests mocked.

- [ ] **Step 4: Update English and Chinese documentation**

Document:

- default inline and optional full-screen modes;
- `/help`, `/state`, `/mode`, `/inspect`, `/clear`, `/exit`;
- key bindings and paste behavior;
- diagnostics disabled/hidden defaults and token setup;
- native Windows Unicode behavior without `chcp`;
- UTF-8 redirected-stream contract and explicit legacy encoding overrides;
- buffered fallback when strict structured streaming is unavailable.

- [ ] **Step 5: Run the full verification suite**

```powershell
$env:JAVA_HOME = 'F:\SDK\JDK21'
.\gradlew.bat clean test :core:jvmTest :server:test installDist
.\scripts\verify-cli-unicode.ps1
```

Expected: all Gradle tasks PASS; Unicode verification prints PASS.

- [ ] **Step 6: Run manual terminal smoke checks**

In Windows Terminal PowerShell and CMD, verify:

```text
1. Type and edit: 你好，OpenEden 👋
2. Paste a multiline Chinese Markdown/code block; confirm it does not auto-submit.
3. Stream a response and cancel with Esc and Ctrl+C.
4. Toggle full-screen with Ctrl+T and diagnostics with Alt+I.
5. Resize below 80x24 and confirm safe inline fallback.
6. Exit with Ctrl+D and confirm cursor, echo, and terminal screen are restored.
```

Repeat the same core flow in one macOS/Linux terminal from the CI-supported list.

- [ ] **Step 7: Commit final verification and docs**

```powershell
git add src/test/kotlin/io/openeden/terminal/CliPseudoTerminalTest.kt scripts/verify-cli-unicode.ps1 README.md README.zh-CN.md .github/workflows
git commit -m "test(cli): verify terminal behavior across platforms"
```

### Task 13: Final Invariant and Regression Audit

**Files:**
- Verify only; modify the smallest responsible file if a check exposes a defect.

- [ ] **Step 1: Scan architectural boundaries**

```powershell
rg -n "persona|internal_logic|promptPreview|snapshot_8D|traceTags" src/main/kotlin/io/openeden/terminal src/main/kotlin/io/openeden/client
rg -n "System\.set(In|Out|Err)|chcp" src/main/kotlin scripts
rg -n '"D"' core/src/commonMain/kotlin/io/openeden/runtime server/src/main/kotlin/DiagnosticStateDto.kt
rg -n "WriteConsoleW|CP936|GBK|jline-terminal-jni" docs/superpowers/specs README.md README.zh-CN.md build.gradle.kts gradle/libs.versions.toml
rg -n "VQ-VAE|HEURISTIC_FALLBACK|withSession|Mutex|preTickedSnapshot" core/src server/src
```

Expected:

- no persona behavior or internal reasoning in terminal/client code;
- no global stream mutation or `chcp` command;
- no stored ninth vector dimension; derived D may appear only as a separately named diagnostic value.
- the packaged Windows path includes JLine JNI/`WriteConsoleW`, while redirected CP936/GBK remains an explicit compatibility mode;
- streaming still crosses the VQ-VAE or logged heuristic fallback path and retains the per-session Mutex/turn gate.

- [ ] **Step 2: Re-run state-write and fallback invariants**

```powershell
.\gradlew.bat :core:jvmTest --tests "io.openeden.runtime.MessagePipelineStreamingTest" --tests "io.openeden.runtime.TurnCoordinatorConcurrencyTest" --tests "io.openeden.runtime.RuntimeInvariantTest" --tests "io.openeden.codebook.HeuristicCodebookFallbackTest"
```

Expected: PASS, including cancelled-turn no-write assertions and heuristic fallback trace coverage.

- [ ] **Step 3: Re-run public-boundary tests**

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.ServerApiTest" --tests "io.openeden.server.DiagnosticsApiTest"
.\gradlew.bat test --tests "io.openeden.OpenEdenCliTest" --tests "io.openeden.client.*"
```

Expected: PASS; public SSE and normal chat contain no diagnostic/private fields.

- [ ] **Step 4: Inspect final diff and working tree**

```powershell
git diff --check
git status --short
git log --oneline --decorate -15
```

Expected: no whitespace errors, no generated terminal histories or runtime databases, and only intentional implementation files.

- [ ] **Step 5: Use verification-before-completion and request code review**

Invoke `superpowers:verification-before-completion`, then `superpowers:requesting-code-review`. Resolve findings with focused tests and commits before claiming completion.

---

## Completion Definition

Implementation is complete only when:

- inline mode is the startup default and displays a vertical, native-scrollback conversation;
- full-screen mode switches on the same JLine terminal and restores reliably;
- Markdown, Chinese, emoji, combining text, history, multiline editing, completion, paste, and cancellation pass tests;
- capable providers produce safe public response deltas, while unsupported providers use validated buffered delivery;
- cancellation and invalid output produce no partial runtime write;
- diagnostics are hidden and server-disabled by default, token-gated when enabled, and never expose internal reasoning or prompts;
- Windows interactive Unicode uses the JNI wide-character path without `chcp`, and redirected streams follow the explicit charset contract;
- existing one-shot commands and `/api/v1/chat` remain compatible;
- the full cross-platform test matrix and manual terminal smoke checks pass.
