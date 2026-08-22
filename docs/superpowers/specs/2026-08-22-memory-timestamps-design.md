# Memory Timestamps In Prompt Context

## Goal

Expose when each retrieved memory occurred so the model can distinguish recent context from older memories without changing persisted memory data.

## Design

- Treat SQLite `created_at_ms` as the durable source of truth and carry it explicitly through `MemoryEntry` and `MemorySnippet`.
- Extend each Prompt memory object with `created_at`.
- Format the value with the existing Prompt time formatter as `yyyy-MM-dd HH:mm`.
- Apply the field to both `recent_turns` and semantic `memories` because both are memory context exposed to the model.
- Keep the raw memory content unchanged; do not duplicate timestamps into text.
- Preserve compatibility for existing test and adapter callers by defaulting the new domain field to `0L`; production persistence and write paths always provide the real timestamp.

## Data Flow

SQLite `created_at_ms` -> `SqlDelightMemoryRepository` -> `MemoryEntry.createdAtMs` -> `MemorySnippet.createdAtMs` -> `OpenEdenPromptBuilder.memorySnippetObject()` -> `created_at` in the JSON prompt object.

## Verification

- Add a prompt-builder test asserting that a memory timestamp is rendered as `created_at` in the expected format.
- Run the core JVM tests and the server tests.
