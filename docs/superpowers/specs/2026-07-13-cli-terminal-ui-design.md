# CLI Terminal UI Design

## Goal

Upgrade the server-backed CLI from a minimal REPL to a polished interactive terminal client comparable to Codex CLI or Claude Code. The CLI supports two switchable presentation modes:

- Inline mode is the default. It preserves native terminal scrollback and renders a vertical conversation.
- Full-screen mode is optional. It provides a bounded conversation viewport, stable editor, and a compact session rail.

Both modes share the same input engine, commands, immutable UI state, streaming event source, Markdown rendering, and terminal capability model. Switching modes changes presentation only; it must not create a new runtime session or duplicate a turn.

## Non-Goals

- Moving runtime, persona, memory, vector, or VQ-VAE logic into the CLI.
- Replacing the existing server-owned pipeline or persistence model.
- Displaying internal reasoning, prompts, trace payloads, or private state in normal chat responses.
- Persisting full-screen mode or diagnostic visibility across CLI launches. Every launch starts in inline mode with diagnostics hidden.
- Replacing the existing non-streaming HTTP API.

## Architectural Invariants

This feature preserves the repository invariants in `AGENTS.md`:

- Persona remains data in `persona/*.yaml`. Terminal labels and commands are product UI, not persona behavior.
- The CLI is an adapter over the shared server runtime. It never applies vector deltas, computes emotion, performs VQ-VAE inference, or writes memory.
- The server continues to run quantization before prompt construction. Heuristic fallback remains deterministic and retains the `codebook=HEURISTIC_FALLBACK` trace tag.
- Derived dissonance is not stored or added to the 8D vector. If diagnostics expose it later, it is a separate derived field.
- Runtime and HTTP work remains asynchronous. Streaming APIs use coroutine `Flow`; Ktor and inference paths must not block request threads.
- A completed turn still validates the mandatory LLM schema before state write-back. Delta application uses the pre-ticked snapshot and the existing per-session serialization boundary.
- Cancellation, transport failure, and schema rejection do not produce a partial vector, memory, diary, Omega, ShockState, or evolution-index write.

## Dependencies and Terminal Ownership

JLine is the single terminal owner. It provides terminal discovery, raw-mode lifecycle, input editing, history, completion, bracketed paste, keymaps, resize signals, terminal dimensions, and alternate-screen capabilities.

Mordant provides capability-aware styling, Markdown parsing/rendering, layout widgets, tables, and progress presentation. Mordant writes through the JLine-owned writer and terminal-width snapshot. It must not independently own stdin, raw mode, cursor visibility, or alternate-screen lifecycle.

The implementation adds the smallest compatible JLine and Mordant modules needed for JVM operation, coroutine animations, and Markdown. No experimental Compose-style TUI framework is introduced.

On JDK 21, the Windows distribution includes JLine's JNI terminal provider and native library. Rich interactive mode must not depend on the exec provider, a shell command, or an optional library that can silently disappear from the packaged application.

## Windows Unicode and Encoding

The current `configureUtf8Console()` implementation is insufficient and will be replaced. Re-wrapping `System.out` and `System.err` changes Java byte encoding but does not control Windows console input, native console output, JLine terminal ownership, redirected streams, or display width. The production design separates interactive consoles from byte-oriented pipes.

### Interactive Windows console

On Windows Terminal, ConPTY, PowerShell, CMD, and classic conhost, JLine's JNI Windows provider is the required interactive backend. It reads Windows `KEY_EVENT` records as Unicode characters and writes text with the wide-character `WriteConsoleW` API. This path transports JVM `String` data directly and does not depend on OEM code page 437, code page 936, code page 65001, `file.encoding`, or `chcp`.

The CLI must not execute `chcp 65001`, call a shell to mutate code pages, or globally replace `System.in`, `System.out`, or `System.err`. Those approaches leak process-wide state, behave differently under redirection, and still fail to solve width and native input handling.

Terminal creation explicitly requests the JNI provider on supported Windows/JDK 21 builds and records the selected provider, terminal type, and encoding in debug logs. If native initialization fails, the CLI emits one concise warning and falls back to plain line mode; it must not pretend that cursor addressing, wide-character editing, or full-screen mode is available.

JLine owns all interactive input and output. Mordant and application rendering write Unicode characters through `terminal.writer()`. Application code must not mix `println`, raw `System.out` writes, and terminal-writer output while an interactive session is active.

### Redirected input and output

Pipes, files, CI capture, and one-shot commands are byte streams rather than Windows console handles. They use a fixed UTF-8 byte contract:

- Redirected stdin, stdout, stderr, history, and exported text use UTF-8 without an output BOM.
- An optional UTF-8 BOM on redirected stdin is consumed once and is never treated as message content.
- The CLI never emits a BOM.
- Malformed non-UTF-8 input bytes are decoded with deterministic replacement characters.
- JLine builder encoding fields and plain-mode readers/writers receive UTF-8 directly. JVM default charset is not used as an implicit fallback.
- HTTP JSON and SSE remain UTF-8 protocol data and are decoded independently of CLI stream handling.

The UTF-8 pipe contract is deterministic. Non-UTF-8 producers and consumers must transcode outside the CLI rather than changing process-wide or CLI-specific encoding state.

### Unicode display width

Correct transport encoding does not guarantee correct cursor placement. The renderer uses one width service for JLine editing, Mordant layout, inline active-region clearing, full-screen frame diffing, and truncation. It handles:

- CJK wide characters;
- combining sequences;
- surrogate pairs and non-BMP code points;
- emoji variation selectors and supported zero-width joiner sequences;
- ANSI style sequences as zero-width;
- control characters as escaped or rejected content.

Width calculations operate on rendered grapheme/code-point sequences, never UTF-16 `String.length`. When a terminal's ambiguous-width policy cannot be detected reliably, the CLI uses the documented JLine width policy consistently across editor and renderer rather than letting the two layers disagree.

Font glyph availability is outside the CLI's control. Missing glyphs must not corrupt the editor buffer or stream data.

### History and paste

Command and message history is stored as UTF-8 and round-trips exact Unicode code points. Bracketed paste enters the editor as Unicode text, preserves newlines, and does not pass through a second platform-default encode/decode cycle. History and paste tests include Simplified Chinese, mixed Chinese/ASCII, emoji, combining marks, and fenced code.

## Component Boundaries

### TerminalSession

`TerminalSession` owns the JLine `Terminal` and `LineReader`. It exposes input and terminal events to the controller and restores terminal state in `finally` on normal exit, cancellation, JVM shutdown, and renderer failure.

JLine input is inherently blocking. Reads run on a dedicated client-side IO dispatcher and never on a Ktor server or inference dispatcher. Only one coroutine reads terminal input.

`TerminalSession` exists only for interactive operation and is the sole owner of the JLine terminal input and output. Redirected and one-shot operation never constructs a `TerminalSession`; `CliTextStreams` owns that path with fixed UTF-8 readers and writers. No renderer or command handler reads the JVM default charset independently.

### CliSessionController

`CliSessionController` is the single owner of interactive session behavior. It combines input events, resize events, timers, and server stream events, then reduces them into immutable `CliUiState`.

The controller is responsible for:

- dispatching local commands;
- starting and cancelling one active chat request;
- preventing duplicate submissions while a turn is active;
- switching render modes without changing server session identity;
- fetching diagnostics only while the panel is explicitly enabled;
- publishing render updates at a bounded rate;
- keeping transport errors recoverable without replaying a request automatically.

### CliUiState

The presentation state contains only UI concerns:

- active mode: `INLINE` or `FULL_SCREEN`;
- connection state and public session identity;
- ordered user and assistant message blocks;
- active provisional assistant block and stream status;
- editor buffer, cursor, completion candidates, and paste state;
- viewport dimensions and terminal capabilities;
- diagnostics visibility, authorization state, and optional diagnostic snapshot;
- transient notice or recoverable error.

It contains no persona instructions, emotion rules, vector mutation logic, prompt text, or internal LLM reasoning.

### InlineRenderer

Inline mode commits completed message blocks to native terminal scrollback. Completed history is never redrawn. Only the active assistant block, generation status, completion menu, and editor are refreshed.

This keeps copying and terminal search natural and prevents memory and repaint cost from growing with conversation length.

### FullScreenRenderer

Full-screen mode enters the terminal alternate screen through JLine capabilities. It renders the same `CliUiState` as a bounded layout:

- conversation viewport;
- fixed editor and status row;
- compact session rail;
- optional diagnostic panel when explicitly enabled.

The renderer builds logical lines from Mordant widgets, diffs the current frame against the previous frame, and writes only changed rows. It does not maintain a second conversation or command model.

If the terminal is too small for a coherent full-screen layout, the CLI returns to inline mode with a concise notice. Resize events are coalesced before layout and repaint.

## Visual and Content Rules

- Conversation messages flow vertically in chronological order.
- Inline content uses a readable maximum line width when the terminal is wide, without creating a horizontal card layout.
- Labels, separators, and color remain restrained. Color supplements meaning and is never the only signal.
- Default chrome shows product name, connection state, active mode, and user/session label only.
- Omega, ShockState, 8D vector values, retrieval mode, codebook state, evolution index, and traces are not shown by default.
- Markdown rendering supports headings, lists, emphasis, quotes, links, fenced code blocks, and syntax highlighting.
- The stored message text remains plain Markdown. Rendering styles must not contaminate copied text.
- East Asian wide characters, combining marks, emoji, and ANSI-free output are covered by width and snapshot tests.
- Interactive Windows console text uses the JLine native Unicode path as its sole input/output owner; redirected and one-shot text uses fixed UTF-8 `CliTextStreams`.
- Streaming repaint is rate-limited so token arrival cannot cause an unbounded redraw loop.

## Input Model and Commands

The editor supports cursor movement, selection-compatible navigation, persistent local history, multiline input, bracketed paste, and command completion.

Default bindings:

| Input | Behavior |
| --- | --- |
| `Enter` | Submit the current buffer |
| `Alt+Enter` or configured multiline binding | Insert a newline |
| `Up` / `Down` | Navigate history or completion candidates according to context |
| `Tab` | Complete the selected slash command |
| `Esc` | Cancel active generation; otherwise dismiss completion or transient UI |
| `Ctrl+C` | Cancel active generation; otherwise clear the current editor buffer |
| `Ctrl+D` | Exit only when the editor is empty |
| `Ctrl+T` | Toggle inline and full-screen modes |
| `Alt+I` | Toggle diagnostics when authorized |

`Ctrl+I` is indistinguishable from Tab in terminal input, so diagnostics uses the distinct `Alt+I` shortcut and leaves completion intact.

Bracketed paste inserts literal text and never submits automatically. Multiline pasted text remains one editor buffer until the user explicitly submits it.

Supported local commands:

| Command | Behavior |
| --- | --- |
| `/help` | Show commands and active key bindings |
| `/state` | Show the existing public session status on demand |
| `/mode inline|full` | Select a presentation mode |
| `/inspect on|off` | Toggle the authorized diagnostic panel |
| `/clear` | Clear visible client conversation only; do not reset server state or memory |
| `/exit` | Close the CLI HTTP client; do not stop the server |

Existing one-shot `chat` and `state` commands remain script-compatible. When stdout is redirected, they emit plain output without ANSI control sequences.

## Streaming Protocol

### Public endpoint

Add `POST /api/v1/chat/stream` while retaining `POST /api/v1/chat` unchanged. The request contains `userId`, `text`, and a client-generated request ID. The response uses `text/event-stream` and a stable sealed event schema:

| Event | Public payload |
| --- | --- |
| `accepted` | request ID |
| `stage` | safe presentation stage such as `preparing`, `generating`, or `finalizing` |
| `response.delta` | decoded public response text chunk |
| `completed` | request ID and completed status |
| `error` | stable code, safe message, and retryable flag |

Normal stream events never expose prompts, internal logic, trace attributes, vector values, retrieval details, memory contents, or provider payloads.

The client API adds a `chatStream` function returning `Flow<ChatStreamEvent>`. The existing `chat` function remains available for compatibility and buffered fallback.

### Pipeline reuse

Streaming and buffered requests share one turn implementation. The implementation exposes internal turn events through a cold `Flow` or event sink; the buffered API collects them into the existing final response. It must not duplicate prompt construction, quantization, retrieval, validation, state write-back, or memory logic.

The existing per-session turn gate remains held for the lifetime of a collected turn. Collector cancellation propagates through Ktor to the provider request and releases the gate through structured concurrency.

### Structured output safety

True response deltas are enabled only for an LLM client that declares strict structured-streaming capability. The incremental parser:

1. consumes provider text deltas without exposing raw JSON;
2. validates the required `internal_logic` and all eight `vector_delta` keys before releasing a `response` string field;
3. decodes JSON string escapes across chunk boundaries;
4. emits only characters belonging to the public `response` field;
5. runs the existing full `LlmOutputValidator` when the object completes;
6. commits state only after full validation succeeds.

If the provider cannot guarantee the configured JSON schema, field order is unsafe, or incremental parsing loses structural certainty, the server buffers the complete output and emits the response only after validation. This is a visible buffered-delivery degradation, not a bypass around the validator.

If a strict stream fails after provisional response text has been shown, the client marks that provisional block as interrupted and the server performs no state write-back. Automatic request replay is forbidden because it could duplicate a completed runtime turn.

## Diagnostics

Diagnostics have two independent gates:

1. The CLI panel is hidden on every launch and appears only after `/inspect on` or `Alt+I`.
2. The server diagnostic endpoint is disabled by default and requires explicit authorization when enabled.

Add a dedicated diagnostic endpoint rather than expanding normal chat events. Server configuration uses an explicit enable flag and a diagnostic credential supplied separately to the CLI. Credentials are read from environment or a protected secret source and are not written to normal terminal output or chat history.

An authorized diagnostic snapshot may contain:

- the stored 8D vector in storage/prompt space `[0, 1]`;
- Omega and ShockState summary;
- evolution index;
- retrieval mode and codebook mode;
- derived dissonance as a separate non-vector value;
- trace IDs or safe status tags needed for operator inspection.

It must not contain internal LLM reasoning, full prompts, API credentials, or unredacted memory contents. When diagnostics are disabled or unauthorized, the UI shows a generic unavailable state and does not display partial data.

## Error Handling and Recovery

- Startup detects TTY, color, Unicode, and dimensions. Unsupported or redirected terminals use plain mode.
- Failure to initialize the Windows native provider disables rich editing and full-screen mode instead of falling back to an unsafe ANSI/native hybrid.
- Malformed non-UTF-8 pipe input decodes with deterministic replacement; alternate pipe encodings cannot be configured.
- Terminal raw mode, cursor visibility, and alternate-screen state are restored in `finally` on every exit path.
- Full-screen renderer failure falls back to inline mode without ending the chat session.
- Network interruption preserves visible content but never automatically resubmits a chat request.
- Stream parse, schema, and provider-capability failures use the safe buffered path where possible.
- Server 4xx/5xx and protocol errors become recoverable notices; the editor remains available.
- Diagnostics authorization failures do not terminate chat.
- Client rendering failures cannot affect runtime state because the server owns the transaction.
- A cancelled turn is reported as interrupted, not completed.

## Performance and Concurrency

- Terminal input has one reader coroutine on a dedicated IO dispatcher.
- Controller state has one logical writer; renderers consume immutable snapshots.
- HTTP streaming is consumed as `Flow` with structured cancellation and bounded buffering.
- Repaint frequency is capped and resize events are coalesced.
- Inline mode does not retain render trees for committed terminal history.
- Full-screen mode diffs logical rows and writes only changed content.
- Markdown parsing is incremental at the active-block boundary and finalized once per completed message.
- No terminal work runs on the Ktor server event loop or `InferenceDispatcher`.

## Testing Strategy

### Unit tests

- State reducer transitions for submit, stream events, cancellation, mode switching, diagnostics, and errors.
- Slash command parsing and completion.
- SSE framing and JSON string decoding across arbitrary chunk boundaries.
- Strict-stream capability and buffered fallback selection.
- Frame diff behavior and resize coalescing.

### Rendering tests

- Golden or normalized snapshots for inline and full-screen render models.
- Simplified Chinese, East Asian width, combining marks, emoji, long unbroken text, and narrow terminals.
- Exact Unicode round trips without replacement characters or mojibake.
- Markdown headings, lists, links, quotes, fenced code, and syntax highlighting.
- Color, no-color, dumb-terminal, and redirected-output variants.
- Diagnostics hidden, authorized-visible, unauthorized, and unavailable states.

### Pseudo-terminal integration tests

- History navigation, completion, multiline editing, bracketed paste, and key bindings.
- Inline-to-full-screen switching and fallback on insufficient dimensions.
- Cancellation during generation.
- Terminal restoration after normal exit and injected failure.

### Windows encoding integration tests

- Windows Terminal/ConPTY with PowerShell and CMD verifies Simplified Chinese input, streamed output, cursor movement, deletion, history recall, paste, and mode switching.
- Interactive tests inherit the current console code page; native Unicode behavior must not depend on that value, and the test must not invoke `chcp` or mutate shell encoding.
- Redirected UTF-8 stdin/stdout verifies exact bytes, optional input BOM consumption, no output BOM, and ANSI-free output.
- Malformed or non-UTF-8 redirected input verifies deterministic replacement behavior; external producers must transcode to UTF-8 because alternate pipe encodings are unsupported.
- Mixed Chinese, ASCII, emoji, combining characters, and fenced code round-trip without `U+FFFD`, question-mark substitution, cursor drift, or split surrogate pairs.
- Native-provider startup failure verifies the warning and plain-mode fallback.

### Server and runtime tests

- SSE event ordering and public payload boundaries.
- Client disconnect cancellation and per-session gate release.
- Existing non-streaming endpoint compatibility.
- Diagnostic enable and authorization gates.
- Cancelled or invalid turns produce no vector, memory, diary, Omega, ShockState, or evolution-index write.
- Completed streamed turns preserve pre-tick base application, session serialization, VQ-VAE fallback tracing, schema validation, and normal memory writes.

Core terminal tests run on Windows, Linux, and macOS CI. Platform-specific pseudo-terminal tests may use small adapters but must assert the same behavioral contract.

## Delivery Sequence

1. Introduce terminal abstractions, explicit interactive-versus-plain I/O ownership, shared UI state, and plain/inline rendering without changing server behavior.
2. Replace global `System` stream wrapping with JLine's native Windows Unicode path and explicit redirected UTF-8 readers/writers.
3. Add JLine input, history, completion, multiline editing, paste handling, and terminal restoration.
4. Add Mordant Markdown rendering and active-region streaming presentation.
5. Add the shared server streaming model, SSE route, client `Flow`, and buffered compatibility path.
6. Add strict structured response streaming for capable providers.
7. Add full-screen rendering and mode switching on the same terminal engine.
8. Add the disabled-by-default authorized diagnostics endpoint and panel.
9. Complete cross-platform pseudo-terminal, Windows encoding, rendering, server, and invariant tests.

Each sequence step must leave one-shot commands and the existing public chat endpoint working.

## Acceptance Criteria

- Running the CLI with no arguments starts in inline mode with a vertical, copyable conversation.
- The user can switch to and from full-screen mode without losing the session or duplicating content.
- Input supports history, completion, multiline editing, safe paste, cancellation, and documented shortcuts on Windows, macOS, and Linux terminals.
- Simplified Chinese input and output work in Windows Terminal, PowerShell, CMD, and classic console hosts without requiring `chcp` or depending on the active console code page.
- Redirected streams and one-shot commands use fixed deterministic UTF-8, provide no encoding overrides, consume an optional UTF-8 input BOM, and never emit a BOM.
- Interactive rendering and editing share one Unicode width service and do not drift on covered CJK, combining, or emoji cases.
- Supported Markdown renders legibly, including Simplified Chinese and code blocks.
- A capable provider produces visible response deltas through SSE; incapable providers degrade to validated buffered output.
- Cancellation and failed validation never produce partial runtime state writes.
- Diagnostics are hidden and server-disabled by default, and authorized display never leaks internal reasoning or prompts.
- Non-TTY and redirected operation remains stable and ANSI-free.
- Terminal state is restored after every tested exit and failure path.
- Existing one-shot commands and `POST /api/v1/chat` remain compatible.
