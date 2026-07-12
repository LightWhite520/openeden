# OpenEden Production Local Core Design

Date: 2026-07-12
Status: Approved design
Scope: Production-capable local core runtime; Web UI and QQ/OneBot adapters are excluded.

## 1. Objective

Complete the local OpenEden core so that it uses durable Memory Palace storage,
rebuildable vector search, DJL-backed VQ-VAE quantization, local DJL embeddings,
dynamic homeostasis, durable asynchronous Diary processing, and structured
tracing while preserving every invariant in `AGENTS.md`.

The resulting core must remain shared by CLI, Ktor, and future platform
adapters. Persona remains external YAML data. Runtime code implements only
deterministic state mechanics, orchestration, storage, and model boundaries.

## 2. Architecture Decision

Use a layered ports-and-adapters architecture with a lightweight MVI-style
unidirectional state flow. Do not introduce a general Effect bus, global Store,
subscription framework, or event-sourced persistence model.

```text
CLI / Ktor / future adapters
            |
        TurnCommand
            |
     TurnCoordinator
       |         |
 staged effects  pure TurnReducer
       |         |
       +---- StateTransition
                    |
              serialized commit
                    |
                TurnResult
```

The main components are:

- `TurnCoordinator`: sequential `suspend` orchestration for one turn.
- `TurnReducer`: pure pre-tick, delta, ShockState, Omega, and evolution math.
- `SessionTurnGate`: per-session coroutine Mutex ownership.
- `SessionStateStore`: durable session state port.
- `MemoryRepository`: durable memory metadata and content port.
- `VectorIndex`: replaceable, rebuildable similarity-search port.
- `EmbeddingModel`: local semantic and emotional embedding ports.
- `CodebookQuantizer`: VQ-VAE quantization and confidence result port.
- `DiaryTaskStore`: durable bounded work queue port.
- `TraceStore`: structured trace persistence port.

`core/commonMain` owns domain models, pure math, and ports. JVM adapters own
DJL, SQLite/SQLDelight, provider clients, and dispatcher implementations.

## 3. Turn State Flow

The accepted command types are user message, heartbeat, explicit shock
injection, and runtime tick. They share the same session serialization gate.

For a user or heartbeat turn:

1. Validate state-independent input.
2. Acquire the per-session coroutine Mutex.
3. Read the latest `SessionState` inside the lock.
4. Apply the emotion confidence gate and bounded confidence-scaled pre-tick.
5. Capture the exact pre-ticked vector used by subsequent inference.
6. Compute derived D, the current centroid, and retrieval mode.
7. Run quantization, memory retrieval, prompt construction, and LLM inference.
8. Validate the complete LLM output schema.
9. Apply `vector_delta` to the captured pre-ticked vector.
10. Compute ShockState, Omega, and `evolution_index` transitions.
11. Commit session state and raw memory while retaining the same session lock.
12. Release the lock, publish any Diary task, and return `TurnResult`.

Same-session turns are serialized across state-dependent inference. Different
sessions remain concurrent. A coroutine waiting on the Mutex does not block an
OS thread. This strategy simultaneously guarantees that the LLM delta is based
on the pre-ticked state and that no concurrent write can replace the latest
session state.

`SessionStateStore` exposes lock-aware read/write operations without attempting
to acquire the same non-reentrant Mutex again. Heartbeats and runtime ticks use
the same `SessionTurnGate`. Diary inference, re-embedding, and index rebuilding
never hold the session turn lock.

If LLM validation fails, vector, Omega, ShockState, memory, and evolution index
are not changed. Once a short database commit begins, cancellation must not
leave a partial state; the transaction is completed in a bounded
`NonCancellable` section.

## 4. Memory Persistence

SQLite accessed through SQLDelight is the only durable source of truth. The
initial vector index is an in-memory acceleration layer that can always be
rebuilt from SQLite.

Logical tables:

```text
memory_entries
  id, session_id, user_id, platform, room, kind
  content, response_summary, source, created_at, stable_tag
  snapshot_l..f, omega_state
  delta_l..f, origin_l..f

memory_embeddings
  memory_id, model_id, semantic_blob, emotional_blob, status

diary_tasks
  id, session_id, source_memory_id
  status, attempts, created_at, available_at, lease_expires_at, last_error

trace_spans
  trace_id, turn_id, session_id, parent_span_id
  stage, status, tags, attributes, started_at, finished_at, error_summary
```

The eight dimensions are stored explicitly as L, P, E, S, tau, V, M, and F.
Derived D is never stored. Embeddings are separated from memory content so a
model upgrade can re-embed records without rewriting the memory itself.

A memory transaction commits durable content and metadata before an index
update. If the index update fails, the index is marked dirty and rebuilt; the
database transaction remains authoritative. Missing embeddings do not prevent
raw memory persistence and instead create a re-embedding task.

The first release does not automatically delete long-term memories. It exposes
capacity metrics and leaves retention policy for a separately approved design.

## 5. Vector Index And Retrieval

`RebuildableInMemoryVectorIndex` loads embeddings in bounded batches at startup
and supports incremental insert, removal, dirty marking, and full rebuilding.
Search first narrows candidates by session, room, kind, and optional time bounds,
then ranks candidates by:

```text
score =
  alpha * semanticSimilarity +
  beta  * emotionalSimilarity +
  gamma * momentumImpact +
  recencyAdjustment
```

`beta` changes deterministically from configured P/S rules. Momentum derives
from historical `delta_vec`, emphasizes high-impact P and V shifts, and is
bounded so a single extreme memory cannot dominate indefinitely.

Retrieval modes are selected once before retrieval and carried in
`RetrievalResult`:

- `CONGRUENT` uses the current emotional vector.
- `MIXED` combines congruent and positive-skew results at 6:4. Candidate
  shortages may be backfilled, and the actual ratio is traced.
- `CONTRAST` maps the current raw vector to internal space, performs center
  symmetry, and maps the target back to storage space before KNN search.

The Prompt Builder consumes the selected mode and does not re-evaluate it.
Every result records memory ID, component scores, selected mode, and trace ID.

## 6. Dynamic Homeostasis

The centroid provider uses the most recent stable/daily memory snapshots. The
default window is 32 entries and remains configurable in runtime data. When the
minimum sample count is unavailable, the persisted session origin is used.

Centroid computation runs through `InferenceExecutor`. Each update has a
configured maximum movement per dimension so a small burst of records cannot
cause an abrupt homeostasis shift. Trace data records the source window,
previous centroid, candidate centroid, and accepted movement.

## 7. DJL And Codebook Pipeline

DJL is the production inference path for VQ-VAE and local embeddings:

```text
BioVector [0,1]
  -> DJL encoder
  -> latent vector
  -> Top-K codebook search
  -> confidence gate
  -> CSV semantic definitions
  -> Prompt Builder
```

Models and the Codebook CSV load and warm during application startup.
Production startup fails for a missing required model, an invalid dictionary,
duplicate or missing node IDs, incompatible dimensions, or malformed semantic
definitions.

DJL predictors are not concurrently shared across coroutines. The JVM adapter
uses a bounded predictor pool or isolated predictor instances and closes all
DJL resources during shutdown. Inference, embedding, coordinate mapping,
symmetry, and centroid calculations execute through the dedicated
`InferenceExecutor` dispatcher.

Quantization returns active nodes, confidence, model ID, semantic definitions,
and trace tags. Empty output, low confidence, inference exceptions,
dimension mismatch, NaN, or Infinity trigger the deterministic heuristic
fallback. Every such result includes `codebook=HEURISTIC_FALLBACK` and a
machine-readable reason. Raw 8D personality interpretation is never injected
when semantic Codebook output or heuristic fallback text is available.

Memory semantic embeddings use a local DJL text encoder. Emotional embeddings
use the configured 8D encoder. Every embedding is normalized and validated for
dimension and finite values before persistence. Records include `model_id` to
support background re-embedding after model upgrades.

Development configuration may opt into deterministic embedding fallback.
Production configuration fails startup when required embedding models are
missing, while isolated runtime failures may degrade with explicit trace and a
queued re-embedding task.

## 8. Memory Writes And Diary

Every valid user or heartbeat turn stores a RAW memory with sender metadata,
input content, response summary, vector snapshot, delta, origin, Omega, and
source. Heartbeats are explicitly tagged `source=HEARTBEAT` and are never
represented as user messages.

Diary generation is asynchronous and never delays the user response. A
configuration-driven significance evaluator publishes tasks for large vector
shifts, Omega steps, ShockState transitions, or explicit critical events.

The durable Diary worker has these semantics:

- Tasks are serialized per session.
- A session may have at most eight pending or running tasks.
- Overflow is rejected with `diary=QUEUE_OVERFLOW`.
- Database leasing prevents duplicate concurrent processing.
- Expired running leases return to pending after process restart.
- Failures use bounded exponential backoff and eventually enter `DEAD` state.
- Valid Diary output creates a separate NARRATIVE memory linked to its RAW
  source records.
- Diary generation never modifies vector state, Omega, ShockState, or
  `evolution_index`.

## 9. Structured Trace And Errors

Trace uses a shared `TraceContext(traceId, turnId, sessionId)` and structured
events with stage, status, timestamp, tags, bounded attributes, and error code.
Covered stages include state load, pre-tick, centroid, quantization, fallback,
retrieval, prompt construction, LLM inference, validation, state commit, memory
write, ShockState/Omega transition, and Diary publication and consumption.

Errors are classified as:

- `TURN_REJECTED`: invalid input or LLM schema; no state change.
- `DEGRADED`: an approved quantizer, embedding, or index fallback was used.
- `AUXILIARY_FAILED`: Diary, trace persistence, or post-commit index update
  failed after the primary turn completed.
- `FATAL_CONFIG`: database migration, model, or Codebook configuration prevents
  safe startup.
- `CRITICAL_DEGRADATION`: Omega reached the domain threshold and activated the
  existing termination protocol.

Trace storage excludes credentials, complete system prompts, and complete
`internal_logic`. Error summaries are truncated and sanitized.

## 10. Implementation Milestones

1. Refactor the turn state flow while retaining heuristic and in-memory
   adapters. Prove confidence gates, delta base, and serialization invariants.
2. Add SQLDelight Memory Palace schema, migrations, repositories, and recovery.
3. Add the rebuildable in-memory index and all three retrieval modes.
4. Install the DJL VQ-VAE, Codebook CSV, and local embedding production paths.
5. Add bounded centroid drift and the durable Diary worker.
6. Add structured trace storage, production assembly, and removal of stale
   development naming and default stubs from production entry points.

Each milestone must leave the repository buildable and retain the deterministic
heuristic fallback unless production startup validation requires a hard error.

## 11. Verification

Required verification includes:

- Property tests for dual-space round trips, center symmetry, derived D, and
  per-dimension pre-tick caps.
- Concurrency tests with 100 same-session turns showing deterministic ordering
  and no lost deltas, plus cross-session parallelism tests.
- Crash-recovery tests for post-commit index failure, interrupted Diary leases,
  and startup index rebuilding.
- Model tests for missing files, invalid dimensions, NaN, Infinity, low
  confidence, timeouts, and predictor failures.
- Retrieval golden tests for all modes, MIXED ratios, momentum, dynamic beta,
  and centroid drift.
- Database migration and restart continuity tests.
- End-to-end CLI tests for messages, heartbeat, runtime tick, and persisted
  state continuity.
- Dispatcher tests proving model and heavy vector work does not execute on Ktor
  request dispatchers.
- Persona scans proving behavioral text remains in YAML data.
- Fallback scans proving every bypass emits
  `codebook=HEURISTIC_FALLBACK`.

## 12. Completion Criteria

The design is complete when all six milestones pass their tests and production
configuration defaults to SQLite Memory Palace persistence, the rebuildable
vector index, DJL VQ-VAE quantization, and local DJL embeddings. Session state,
memories, pending Diary tasks, and structured traces must survive restart.

The implementation must preserve Persona-as-Data, non-blocking coroutine I/O,
the VQ-VAE semantic boundary, derived-only D, per-session serialization,
confidence-scaled pre-tick behavior, and owner-only heartbeat delivery.
