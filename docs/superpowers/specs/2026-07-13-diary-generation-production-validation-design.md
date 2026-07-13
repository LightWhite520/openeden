# Diary Generation and Production Validation Design

## Scope

This change completes Narrative Diary generation and verifies the production runtime with real DJL models, SQLite, and the configured OpenAI-compatible endpoint. Critical degradation, termination commands, and Omega-triggered shutdown are explicitly excluded and will be implemented last in a separate change.

## Goals

- Replace the server's `Diary event: ...` placeholder with an LLM-generated Chinese narrative.
- Trigger Diary tasks after a significant vector delta, after five hours of real elapsed time with new raw memories, or when a future context compactor reports completion.
- Preserve the existing bounded, per-session, durable Diary queue and retry behavior.
- Keep Diary generation outside the request path and recover pending work after restart.
- Run a production smoke test using real DJL artifacts, file-backed SQLite, and the endpoint configured in `.env`.

## Non-Goals

- Implementing `CRITICAL_DEGRADATION`, termination commands, or process shutdown.
- Adding a general-purpose in-memory event bus.
- Implementing a context compression subsystem. This change provides its typed Diary trigger entry point without fabricating compaction events.
- Changing the stored 8D vector, Omega, or `evolution_index` as a side effect of Diary generation.

## Architecture

### Durable Trigger Coordinator

`DiaryTriggerCoordinator` is a narrow business interface with three entry points:

- `onVectorDelta`: enqueue when the maximum absolute coordinate in `delta_vec` reaches the configured threshold, default `0.25`.
- `onContextCompacted`: enqueue through a typed integration point using the last memory covered by compaction.
- `flushElapsedSessions`: enqueue when at least five wall-clock hours have elapsed since the last successful Diary and the session has newer raw memories.

The coordinator calculates a deterministic deduplication key from session, trigger reason, and upper-bound raw memory ID, then writes directly to `DiaryTaskStore`. It never invokes the LLM. A general event bus is intentionally omitted because Diary delivery requires persistence, ordering, deduplication, bounded depth, and restart recovery; an in-memory bus would not replace those guarantees.

### Diary Checkpoint

SQLite stores one checkpoint per session with:

- last successfully covered raw memory ID;
- last successful Diary timestamp;
- last narrative memory ID.

The checkpoint is advanced only after the narrative memory is successfully persisted. A failed or cancelled generation leaves it unchanged. The five-hour interval is measured from the last successful Diary. For a session with no checkpoint, the first eligible raw memory establishes pending work and may be triggered immediately by delta or compaction; elapsed-time flushing becomes eligible five hours after the first uncovered raw memory.

### Narrative Generation

`LlmDiaryNarrativeGenerator` reads raw memories strictly after the checkpoint and through the task's upper-bound memory ID. It also reads the latest session state and quantizes that state through the configured VQ-VAE path. The Diary prompt contains:

- English logical constraints, output schema, derived D, trigger reason, and VQ-VAE node definitions;
- Chinese narrative instructions loaded from the required `diary.narrative` section in `persona/*.yaml`;
- the bounded raw memory slice to summarize.

Raw 8D coordinates are never exposed to the LLM. If VQ-VAE inference degrades, the existing deterministic heuristic is used and traced with `codebook=HEURISTIC_FALLBACK`. The model must return the standard project JSON schema with all eight `vector_delta` values set to `0.0`; the generator validates this and uses only `response` as Diary content. Diary inference is not a dialogue turn and does not mutate vector state, Omega, ShockState, or `evolution_index`.

The generated `MemoryEntry` is `NARRATIVE` in `EVENT_ROOM`. Its ID is deterministic from the task ID, its embeddings are produced by the configured memory embedding model, and its metadata snapshots the current state without applying a delta. The source user identity is preserved when the covered raw memories have one unambiguous originating user; otherwise it uses the reserved `diary-worker` identity.

## Data Flow

1. The normal message pipeline completes emotion analysis, confidence-gated pre-tick, VQ-VAE quantization, retrieval, LLM validation, serialized vector write-back, and raw memory persistence.
2. After raw memory persistence, it calls `onVectorDelta` with the persisted memory ID and actual output delta. Enqueueing is suspendable but contains no inference and does not wait for Diary generation.
3. A dedicated coroutine invokes `flushElapsedSessions` once per minute. Only sessions with uncovered raw memories can enqueue work.
4. A future context compactor calls `onContextCompacted` after its own durable compression write completes.
5. `DiaryWorkerScheduler` leases one task per session. `DurableDiaryWorker` generates and persists the narrative, then atomically records completion and advances the checkpoint within the existing per-session gate.
6. On restart, expired leases return to `PENDING`, completed checkpoints prevent duplicate coverage, and the worker resumes.

## Persistence and Idempotency

Diary task insertion is idempotent by deterministic task ID. Narrative memory insertion is idempotent by deterministic narrative ID. Completion and checkpoint advancement are performed in a storage operation that cannot expose a completed task without its matching checkpoint. The active queue remains bounded at eight pending or running tasks per session; overflow retains the existing `diary=QUEUE_OVERFLOW` trace tag.

Raw memory range reads are ordered by durable creation sequence and exclude `NARRATIVE` entries. The range has a configured maximum size to bound prompt and allocation cost. If more raw entries exist than fit in one task, the task covers only the returned upper bound and a subsequent elapsed flush can enqueue the remainder.

## Concurrency and Resource Lifecycle

All LLM, SQLite, and embedding operations use suspend APIs and run outside Ktor's request dispatcher where required. DJL inference, quantization, embedding, and vector mapping remain isolated by `InferenceDispatcher`. Diary workers stay serialized per session; independent sessions may progress concurrently.

Application shutdown cancels and joins heartbeat, runtime tick, elapsed Diary trigger, and Diary worker jobs before closing stores. It also closes the OpenAI HTTP client and every DJL-backed predictor owned by runtime models. This makes restart tests meaningful and avoids file, socket, or native model handles leaking across runs.

## Failure Handling

- Endpoint, schema validation, embedding, and persistence failures retain the task with exponential backoff.
- Cancellation propagates and does not mark a task failed during shutdown.
- Invalid Diary output, including any non-zero vector delta, is rejected and retried.
- A missing or empty raw memory slice completes no task and does not advance the checkpoint; the task is failed with a bounded diagnostic.
- Low-confidence or failed VQ-VAE inference follows the existing heuristic fallback and trace requirement rather than blocking.
- DEAD tasks remain inspectable in SQLite after the configured retry limit.

## Configuration

The server exposes non-persona runtime settings:

- `OPENEDEN_DIARY_DELTA_THRESHOLD`, default `0.25`;
- `OPENEDEN_DIARY_ELAPSED_HOURS`, default `5`;
- `OPENEDEN_DIARY_SCAN_INTERVAL_SECONDS`, default `60`;
- `OPENEDEN_DIARY_MAX_RAW_MEMORIES`, a bounded default selected to fit current prompt limits.

Chinese narrative behavior is required in `persona/*.yaml` under `diary.narrative`. No persona language or emotional behavior is hardcoded in Kotlin.

## Production Verification

A repository script launches the real server with:

- a temporary file-backed SQLite database;
- `OPENEDEN_MODEL_BACKEND=djl` and the real exported model directories;
- the OpenAI-compatible base URL, model, and API key already configured in `.env`;
- a reduced Diary delta threshold for deterministic smoke triggering.

The script performs at least three sequential chat requests, waits with a finite timeout for a completed narrative task, captures public state, stops the server gracefully, restarts it against the same database, sends another chat request, and queries SQLite to verify:

- the same session survived;
- `evolution_index` increased rather than resetting;
- vector and Omega state survived unchanged across the restart boundary;
- raw and narrative memories exist;
- the Diary checkpoint matches a completed task;
- no task is stranded in `RUNNING`;
- the post-restart request succeeds through real DJL and the configured endpoint.

The script never prints secrets and always terminates child processes on success, failure, or timeout.

## Test Strategy

Unit and storage tests cover:

- max-absolute delta threshold boundaries;
- elapsed triggering at five hours and suppression when no new raw memory exists;
- context compaction trigger entry;
- deterministic task deduplication and queue overflow;
- raw memory cursor range selection;
- successful checkpoint advancement and narrative idempotency;
- generation failure, retry, lease expiry recovery, and DEAD transition;
- Persona-as-Data prompt construction, VQ-VAE semantic injection, derived D, and zero-delta enforcement;
- application resource closure.

Integration verification covers real DJL loading, real SQLite persistence, the configured endpoint, continuous requests, graceful shutdown, and restart recovery. Omega critical degradation remains untested here because it is outside this change.

## Compliance Review

- Persona-as-Data: all Chinese Diary behavior is loaded from persona YAML.
- Non-blocking: Diary inference runs in worker coroutines; request handling never waits for narrative generation.
- VQ-VAE pipeline: Diary state reaches the LLM only through semantic codebook definitions, with traced heuristic fallback.
- 8D invariant: D remains derived and is never persisted as a ninth coordinate.
- Write serialization: normal vector writes retain the per-session Mutex; Diary generation does not write vector state.
- Durable queue: per-session Diary work stays bounded, serialized, retryable, and restart-safe.
