# CLI Inline Input Ownership Design

## Problem

In interactive inline mode, JLine commits the submitted editor line to native terminal scrollback when Enter is pressed. The CLI reducer also creates a completed user message, and `InlineCliRenderer` commits that message with `printAbove`. Each live user submission is therefore displayed twice.

The renderer cannot simply hide every user message because restored conversation history must still include user turns. It also cannot assume that every live submission is already visible: input submitted from the alternate full-screen buffer disappears when that buffer closes and must later be committed when inline rendering resumes.

## Decision

Terminal submission events carry explicit display ownership captured at submission time. A submission made while the terminal session is in inline mode is marked as already committed to native scrollback. A submission made while full-screen mode owns the display is not.

The controller propagates this presentation-only ownership to the local user message. `InlineCliRenderer` consumes completed messages in chronological order as before, but it records an already-committed inline user message without printing it again. Full-screen rendering ignores inline scrollback ownership and continues to render every message from state.

History loaded from the server has no terminal ownership marker, so both restored user and assistant messages are rendered normally. Message identity remains the canonical `${turnId}:user` and `${turnId}:assistant` form; text matching is never used for deduplication.

## Data Flow

1. `JLineTerminalSession` reads a completed line and snapshots whether its display state is inline.
2. `CliTerminalEvent.Submit` carries the text and whether the terminal already committed the line.
3. `CliSessionController` dispatches the normal submitted state event with that presentation metadata.
4. `CliReducer` creates the local user message with inline ownership and the assistant streaming message without it.
5. `InlineCliRenderer` claims every completed message ID. It suppresses output only for user messages already committed by the inline terminal.
6. Restored history and messages submitted while full-screen remain renderer-owned and are printed when inline rendering needs them.

## Boundaries

- Ownership metadata is a CLI presentation concern only. It is not sent to the server or persisted in the transcript.
- No persona, prompt, VQ-VAE, vector, Omega, ShockState, memory, or relationship behavior changes.
- JLine remains the sole owner of interactive input and terminal I/O.
- No terminal line erasure, text-based deduplication, code-page changes, or new blocking work is introduced.

## Testing

- Strengthen the pseudo-terminal test so each submitted Chinese prompt appears exactly once in the emulated screen and scrollback.
- Add renderer coverage proving an inline-terminal-owned user message is claimed but not printed.
- Verify restored user history is still printed.
- Verify a user message without inline ownership, including a full-screen-origin submission returning to inline mode, is printed.
- Run the CLI unit, renderer, pseudo-terminal, and Windows terminal tests, followed by the complete project test matrix.

## Acceptance Criteria

- A live inline submission such as `> 你好` appears exactly once.
- Assistant responses remain aligned and appear once.
- Restarted history includes both user and assistant turns.
- Switching between inline and full-screen does not lose or duplicate user messages.
- Unicode editing, terminal encoding, and redirected UTF-8 behavior remain unchanged.
