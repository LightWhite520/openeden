# Single-Incarnation Transcript and Terminal History Design

## Goal

OpenEden presents one continuously existing ATRI incarnation across its first-party and adapter surfaces. A CLI restart, server restart, or presentation-mode switch must not create a fresh visible conversation. The terminal restores recent public conversation history, loads older turns on demand, and keeps completed messages in normal terminal scrollback after each submission.

This design also defines the terminal rendering correction selected by the user: generation status occupies its own line and the following `ATRI:` response starts at column zero. Neither row depends on a leading-space workaround.

When ATRI terminates, the active incarnation ends permanently. Only its Narrative Diary survives as a read-only archive. The full transcript and all other runtime state are physically deleted and cannot be inherited by a new incarnation.

## Non-Goals

- Supporting multiple simultaneously selectable ATRI conversations.
- Copying Codex CLI or Claude Code's local per-project session-file model.
- Letting a new incarnation retrieve, import, or inherit a previous incarnation's transcript or diary.
- Exposing internal reasoning, prompts, vectors, raw memories, provider payloads, or traces through history APIs.
- Making `/clear` reset server state, memory, transcript, or incarnation identity.
- Implementing selective legacy import or an operator-only backdoor to deleted data.

## Architectural Invariants

This feature preserves the repository constraints in `AGENTS.md`:

- Persona remains external data. Transcript persistence and terminal labels contain no persona behavior.
- The server remains the source of truth. CLI, Web UI, and platform adapters do not mutate 8D state, Omega, ShockState, Memory Palace, or evolution state.
- All transcript and archive I/O is asynchronous and isolated from Ktor request threads and the inference dispatcher.
- VQ-VAE quantization, heuristic fallback, derived dissonance, pre-tick application, validation, and per-session serialized state commits remain unchanged.
- A transcript entry contains only validated public turn content. It never stores derived D, private reasoning, prompt text, or diagnostic state.
- Existing `sessionId = platform:scopeId` values remain runtime routing, state, and mutex boundaries. `incarnation_id` is a separate global lifecycle identity and does not replace those concurrency boundaries.

## Why Server-Owned Persistence

Codex CLI and Claude Code primarily persist resumable transcripts under local user directories because they support many independent local work sessions. OpenEden has different semantics: one ATRI incarnation is observed through multiple surfaces. A CLI-only JSONL file would diverge from Web and QQ activity and could not define the entity's lifecycle boundary.

The server therefore owns one append-only public transcript for the active incarnation. Clients keep only surface-specific conveniences such as JLine input history.

## Lifecycle Model

### Active incarnation

The runtime stores exactly one active `incarnation_id`. Server restarts reopen that incarnation rather than creating another one. Every completed public turn records its platform and scope metadata but belongs to the same active incarnation.

The existing per-scope runtime state model remains intact. Incarnation identity groups public history and defines the termination/archive boundary; it does not turn the per-session mutex into a global mutex.

### Termination

Termination is an explicit lifecycle transition with the following ordered behavior:

1. Mark the active incarnation as terminating so no new turn can begin.
2. Allow an already committed turn to finish transcript publication; reject or cancel uncommitted turns.
3. Seal all Narrative Diary entries for the incarnation into an immutable diary archive.
4. Verify archive row count and content integrity.
5. Physically delete the incarnation's public transcript, RAW memories, vector index records, 8D state, Omega, ShockState, relationship state, traces, pending diary tasks, and other non-diary runtime data.
6. Mark the incarnation terminated and halt its runtime.

Creating another ATRI produces a new `incarnation_id` and fresh runtime state. The new incarnation cannot query archived diaries through Memory Palace, prompt construction, heartbeat processing, normal history APIs, or runtime tools.

### Diary archive

Only Narrative Diary content survives termination. The archive is read-only and human-readable. Owner-facing reads expose diary text and ordinary chronology. A developer endpoint may additionally expose technical diary metadata and integrity information, but cannot expose transcript or runtime data that was deleted during termination.

Archived diary rows carry at least:

- `archive_entry_id`;
- `incarnation_id`;
- original diary entry identifier;
- diary content;
- original creation time;
- archive time;
- archive reason;
- source diary metadata required for audit;
- content hash or equivalent integrity value.

No archive field grants runtime retrieval eligibility.

## Transcript Data Model

Add a focused server persistence boundary for public conversation turns. A record contains:

- `turn_id`, used as the idempotency key and pagination tiebreaker;
- `incarnation_id`;
- `session_id`, preserving `platform:scopeId` traceability;
- `platform`, `scope_id`, and `user_id`;
- validated user text;
- validated public assistant text;
- completion timestamp.

Only successfully validated and committed turns enter the transcript. Interrupted, cancelled, provider-failed, schema-rejected, and state-write-failed attempts do not become completed history.

The transcript repository has one cohesive responsibility: append completed public turns, page active-incarnation turns, and purge turns during termination. It must not provide Memory Palace retrieval or prompt-building behavior.

## Commit Semantics

Transcript publication belongs to the server turn-commit path. It occurs after LLM schema validation and the existing serialized state commit, using the same stable `turn_id` for idempotency.

If transcript publication fails, the client receives a retryable persistence error rather than a false completed event. A retry with the same `turn_id` must observe or create exactly one transcript row and must not reapply vector state. The implementation plan must choose a transaction or durable outbox boundary that makes this ordering recoverable without duplicating a lived turn.

Transcript failure must never block Ktor or inference threads. Database work runs on the repository's IO dispatcher.

## History API

Add a read-only public history endpoint for the active incarnation. It accepts:

- caller/session identity;
- `limit`, capped at 50 for the first implementation;
- an optional opaque `before` cursor.

The response contains public turns in chronological display order, an optional cursor for the next older page, and whether more history exists. Cursor ordering is stable over `(completed_at, turn_id)` and scoped to the active incarnation. New turns appended between page requests must not duplicate or skip older results.

The normal endpoint never accepts an arbitrary `incarnation_id` and cannot read terminated archives. Owner diary archive and developer diary archive access use separate authenticated endpoints.

History DTOs expose no prompt, internal logic, vectors, Omega, ShockState, retrieval mode, trace tags, raw memory, or provider fields.

## CLI History Behavior

### Startup

After health and configuration checks, the CLI fetches the latest 50 turns before accepting interactive input. It hydrates immutable `CliUiState` with stable message IDs derived from `turn_id`, renders the messages once, and then enters the normal input loop.

History restoration failure is recoverable. The CLI displays one concise notice and starts with an empty visible view while remaining connected to the active incarnation.

### Loading older turns

- Inline mode uses `/history older` to fetch and print the previous 50 turns into native terminal scrollback.
- Full-screen mode requests the previous page when PageUp or supported mouse-wheel scrolling reaches the top boundary.
- Only one history request may run at a time.
- Loaded turns are prepended without changing the active editor buffer or duplicating existing message IDs.
- The CLI announces when no older history remains.

The controller owns paging state and request serialization. Renderers consume immutable history snapshots and never call the server directly.

### Clear behavior

`/clear` clears only the currently visible client conversation. It does not delete transcript rows, reset pagination, mutate server state, or create a new incarnation. A later explicit history load may display server-owned turns again.

JLine input history remains stored under `~/.openeden/history`. It is separate from the public conversation transcript.

## Inline Rendering Correction

The current inline active region is incorrect for two reasons:

- completed content can be erased when JLine `Status.hide()` clears rows that were also used to present the active assistant block;
- `ATRI:` and `[status]` rely on literal leading spaces and are rendered on unrelated rows.

The selected layout is:

```text
> user message
[generating]
ATRI: assistant response
```

Rules:

- `[status]` starts at column zero and occupies its own transient row;
- `ATRI:` starts at column zero on the following row;
- no status or speaker label contains a compensating leading space;
- completed user and assistant blocks are committed to native terminal scrollback before the active region is hidden;
- hiding or resizing the active region cannot erase committed scrollback;
- the transient status row disappears after completion;
- wrapped assistant continuation lines align under the assistant body, not by repeating `ATRI:` on every line;
- Simplified Chinese, emoji, combining marks, and Markdown wrapping use the shared display-width calculation.

Inline mode retains completed messages visually without retaining an unbounded active render tree.

## Full-Screen Behavior

Full-screen mode renders the same hydrated message collection in a bounded viewport. It maintains a scroll offset independent of the editor. Reaching the oldest loaded boundary produces a history-load event; the controller fetches older turns and preserves the user's visual anchor when rows are prepended.

Unsupported mouse tracking does not disable history. PageUp remains the deterministic fallback. Leaving full-screen mode preserves loaded messages and does not refetch or duplicate them.

## Commands

Add:

| Command | Behavior |
| --- | --- |
| `/history older` | Load the previous page into the current view |

Existing commands retain their meaning. In particular, `/clear` is presentation-only and `/exit` does not stop the server or terminate ATRI.

## Error Handling

- A history read failure produces a recoverable notice and leaves current content intact.
- A duplicate page or turn ID is ignored deterministically.
- A malformed or cross-incarnation cursor is rejected without exposing whether an archive exists.
- Transcript writes are idempotent by `turn_id`.
- Archive verification failure aborts destructive termination and leaves the incarnation sealed for operator recovery.
- Purge failure leaves termination incomplete and must not start a new incarnation.
- Owner and developer archive authorization failures return generic unavailable responses.
- Diary archive reads never reactivate, index, or inject archived content.

## Security and Privacy

- Active transcript endpoints return public user and assistant text only.
- Diary archive endpoints are separately authenticated and disabled for ordinary clients.
- Developer access adds metadata, not access to deleted data.
- Archive rows are immutable after sealing.
- Logs contain identifiers and safe error codes, not transcript or diary bodies.
- Termination purge is scoped by `incarnation_id` and verified before execution.

## Testing Strategy

### Persistence and API

- Server restart reopens the same active incarnation and transcript.
- Initial history returns the latest 50 turns in chronological order.
- Cursor paging returns older turns without gaps or duplicates while new turns are appended.
- Repeated publication of one `turn_id` creates one row.
- Invalid, failed, interrupted, and cancelled turns create no completed transcript row.
- History DTOs contain no private runtime fields.

### Lifecycle and archive

- Termination seals Narrative Diary entries before destructive purge.
- Archive integrity failure prevents purge.
- Successful termination leaves diary archive rows and removes transcript, RAW memory, vectors, Omega, ShockState, relationships, traces, and pending tasks.
- A new incarnation cannot retrieve or prompt-inject archived diary content.
- Owner archive reads return diary content; developer reads add only approved metadata.

### CLI state and pagination

- Startup hydration occurs before interactive input.
- Restored history renders once and uses stable IDs.
- `/history older` prepends exactly one page.
- Concurrent older-page requests collapse to one request.
- `/clear` changes only visible state and does not call a destructive server API.
- History-load failure preserves current messages.

### Terminal rendering

- Windows ConPTY pseudo-terminal transcript retains earlier messages after a new submission.
- `[generating]` and `ATRI:` start at column zero on separate rows.
- The status row disappears after completion without erasing committed content.
- Wrapped continuation lines align under assistant content.
- Chinese, emoji, combining sequences, narrow terminals, and long Markdown do not drift or overlap.
- Inline/full-screen switching preserves loaded messages and paging state.

## Delivery Order

1. Add incarnation and transcript persistence contracts with SQLDelight storage and idempotency tests.
2. Publish completed public turns from the server commit path without duplicating state mutation.
3. Add cursor-based active-history API and client DTOs.
4. Add CLI startup hydration, `/history older`, and immutable paging state.
5. Correct JLine inline active-region ownership and implement the selected status/ATRI layout.
6. Add full-screen older-page events and scroll-anchor preservation.
7. Add immutable diary archive storage and authenticated owner/developer reads.
8. Implement verified termination sealing and destructive purge.
9. Complete server restart, archive isolation, cross-platform rendering, and Windows ConPTY tests.

## Acceptance Criteria

- Sending a message does not erase earlier visible terminal conversation.
- Restarting CLI or server restores the latest 50 public turns for the active ATRI incarnation.
- Older turns load in pages of 50 through `/history older` or the full-screen top boundary.
- Inline generation uses a column-zero status row followed by a column-zero `ATRI:` row, with no leading-space workaround.
- Completed content remains in native inline scrollback after status cleanup.
- All first-party and adapter surfaces write to the active server-owned incarnation transcript.
- `/clear` remains presentation-only.
- Termination preserves only immutable Narrative Diary archive content and physically deletes all other incarnation data.
- A new ATRI cannot inherit, retrieve, or prompt-inject any archived content.
- Owner and developer archive reads expose only diary content and approved diary metadata.
- Persona-as-Data, VQ-VAE execution, derived-D rules, non-blocking I/O, and per-session serialization invariants remain intact.
