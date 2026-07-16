# CLI JLine Post Active Renderer Design

## Goal

Replace the inline renderer's use of JLine `Status` with an activity region owned by the same `LineReaderImpl` display that owns the prompt and editable input buffer. Streaming updates must redraw in place without repeatedly changing the terminal scroll region, while preserving Unicode editing, completion, cancellation, history, and full-screen mode.

## Architecture

`OpenEdenLineReader` extends JLine `LineReaderImpl` and owns the current OpenEden activity text. It exposes one internal operation that replaces the activity rows. The operation acquires JLine's protected reentrant display lock, updates a stable `post` supplier, and invokes the normal JLine redisplay path. No production code writes cursor-addressing ANSI sequences or constructs a second `Display`.

JLine already appends `post` beneath the prompt inside its normal display model. Therefore the prompt, partially edited user input, completion UI, and OpenEden activity rows share one cursor model and one output lock. Streaming deltas change the existing display content instead of collapsing and recreating a terminal scroll region.

## Post Ownership

OpenEden owns `post` only while JLine is not using it for an internal interaction. Completion menus, search prompts, and other built-in JLine posts take priority. `OpenEdenLineReader.redisplay` restores the OpenEden post after an internal post is cleared, provided activity rows still exist.

Updating OpenEden activity while a built-in post is visible updates the stored activity text but does not replace the built-in post. Clearing OpenEden activity removes only the OpenEden supplier; it must not clear a JLine-owned post.

## Terminal Boundary

`TerminalSession` gains an operation for replacing inline activity rows. `JLineTerminalSession` delegates that operation to `OpenEdenLineReader`. `JLineInlineActiveSink` remains the renderer adapter but no longer imports or constructs `Status`.

`JLineTerminalSession` constructs `OpenEdenLineReader` directly and applies the same completer, history variables, options, widgets, and keymap configuration currently applied through `LineReaderBuilder`. Public `TerminalSession.lineReader` remains typed as JLine `LineReader` so history printing and existing callers do not depend on the concrete subclass.

## Completion Handoff

When a streaming assistant message becomes complete, the renderer clears the active post before calling `LineReader.printAbove` for the committed response. This prevents the completed response from briefly appearing both below the prompt and in scrollback. Existing message ownership rules remain unchanged: terminal-owned user input is not printed twice, restored history remains renderer-owned, and assistant responses enter scrollback once.

## Resize And Modes

JLine's primary display handles resize events through its existing signal path. The custom activity layer does not reserve rows and does not maintain a separate terminal width. Switching to full-screen clears the inline post before the alternate-screen renderer takes ownership; switching back lets subsequent inline state rebuild it.

Plain/dumb terminal fallback behavior remains unchanged. The custom post renderer is used only by the rich JLine session that currently constructs `JLineInlineActiveSink`.

## Failure Handling

Replacing activity rows is synchronous, allocation-bounded terminal rendering under JLine's display lock. It performs no network, filesystem, inference, or coroutine blocking work. Closing the renderer clears the OpenEden post before the terminal session closes.

If JLine is not currently reading a line, the activity state is retained and the next normal redisplay renders it. The implementation must not start a second reader or call `readLine` recursively.

## Verification

Tests must prove:

- Consecutive response deltas update one JLine display without `Status` or scroll-region control sequences.
- A partially edited Unicode input buffer survives activity updates.
- Built-in JLine post content has priority and OpenEden activity returns afterward.
- Completion clears the activity post before committing assistant history.
- The Windows ConPTY flow retains complete `[status]` and `ATRI:` labels, preserves both committed turns, and does not move a fixed transcript row during same-height streaming deltas.
- Full project tests remain green, with only the existing Windows symbolic-link permission skip allowed.

## Architectural Constraints

This change is confined to CLI terminal infrastructure and rendering. It does not encode persona behavior, touch the runtime message pipeline, perform inference work, or alter VQ-VAE state and prompt construction.
