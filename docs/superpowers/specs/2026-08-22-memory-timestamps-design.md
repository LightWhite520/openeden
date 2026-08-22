# Memory Timestamps In Prompt Context

## Goal

Expose when each retrieved memory occurred so the model can distinguish recent context from older memories without changing persisted memory data.

## Design

- Keep `MemoryEntry.createdAtMs` and SQLite `created_at_ms` as the single source of truth.
- Extend each Prompt memory object with `created_at`.
- Format the value with the existing Prompt time formatter as `yyyy-MM-dd HH:mm`.
- Apply the field to both `recent_turns` and semantic `memories` because both are memory context exposed to the model.
- Keep the raw memory content unchanged; do not duplicate timestamps into text.
- Preserve compatibility for callers that construct `MemorySnippet` values with existing fields.

## Data Flow

`MemoryEntry.createdAtMs` -> `MemorySnippet.createdAtMs` -> `OpenEdenPromptBuilder.memorySnippetObject()` -> `created_at` in the JSON prompt object.

## Verification

- Add a prompt-builder test asserting that a memory timestamp is rendered as `created_at` in the expected format.
- Run the core JVM tests and the server tests.
