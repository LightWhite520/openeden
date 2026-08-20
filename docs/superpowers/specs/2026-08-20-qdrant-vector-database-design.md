# Qdrant Vector Database Design

Date: 2026-08-20
Scope: Add Qdrant as the production memory candidate index while retaining SQLite as the authoritative store for a single OpenEden server instance.

## Goal

Replace the production in-memory linear candidate scan with a real vector database without making conversation handling depend on Qdrant availability.

The design must preserve Persona-as-Data, the VQ-VAE prompt pipeline, the three retrieval modes, momentum and identity-aware final ranking, inference isolation, and all current SQLite durability guarantees. Qdrant is a rebuildable search projection. SQLite remains the only authoritative database.

## Operating Assumptions

- One OpenEden server instance runs at a time.
- SQLite remains appropriate for session state, transcripts, relationship state, diary work, traces, memory content, metadata, and stored embeddings.
- Qdrant runs as a local Docker service or an independently operated remote service.
- Qdrant outages must not prevent server startup, memory writes, retrieval, or normal dialogue turns.
- PostgreSQL and pgvector are outside this scope.

## Chosen Approach

Use a recoverable Qdrant projection backed by a SQLite outbox-like synchronization table.

Each memory is committed to SQLite first. The same SQLite transaction records that the current embedding projection is pending. A dedicated coroutine asynchronously upserts pending vectors to Qdrant. Retrieval prefers Qdrant while it is healthy and falls back to the existing rebuildable in-memory index on any availability or protocol failure.

Two alternatives were rejected:

- Best-effort dual writes have less code but can leave outage-period vectors missing indefinitely.
- Making Qdrant authoritative prevents complete recovery from SQLite and conflicts with the required degraded mode.

## Architectural Boundaries

### SQLite

SQLite is the source of truth for:

- memory content and room/kind classification;
- semantic and emotional embeddings;
- `snapshot_8D`, Omega, `delta_vec`, `snapshot_origin`, and sender metadata;
- embedding model identity;
- Qdrant projection status and retry state.

Deleting every Qdrant collection must not lose a memory or alter runtime state. A complete Qdrant projection must be reconstructible from SQLite.

### Qdrant

Qdrant owns only the searchable projection:

- named semantic and emotional vectors;
- stable point identity;
- payload required for filtering and SQLite hydration.

Qdrant must not become a second source of memory content or physiological state.

### Memory Palace

The existing Memory Palace remains responsible for exact final ranking and result composition. Qdrant only narrows the candidate set. It must not implement persona behavior, retrieval-mode selection, homeostasis mapping, momentum semantics, or relationship-role logic.

CONGRUENT, MIXED, and CONTRAST mode selection and final scoring remain in the existing core memory layer. The selected `RetrievalMode` continues to travel with `RetrievalResult` to the Prompt Builder.

## Component Design

### Candidate index contract

Refine the core vector-index boundary so a remote index can return stable candidate references rather than full `MemoryEntry` values. A search hit carries:

- `memoryId`;
- semantic similarity;
- optional emotional similarity when the request uses it.

The SQLDelight repository hydrates candidate IDs in one bounded batch and restores the index result order before passing entries to Memory Palace. The in-memory implementation uses the same contract.

No Qdrant DTO or Ktor type may enter `core`.

### Qdrant client

Add a focused server-side Qdrant REST client using Ktor `HttpClient`. It owns:

- collection inspection and creation;
- payload-index creation;
- batch point upsert;
- semantic vector search with payload filters;
- bounded health probes;
- response validation and error normalization.

The client is fully suspendable. It must rethrow `CancellationException`. Error bodies are truncated and sanitized before logging, and the API key is never included in exceptions, logs, diagnostics, or traces.

### Qdrant vector index

`QdrantVectorIndex` adapts the REST client to the core candidate-index contract. It validates that vectors are finite, non-empty, and dimensionally compatible before network calls.

The first valid projection batch lazily establishes the physical collection dimensions. If an existing collection has incompatible named-vector dimensions, it is not reused.

### Resilient vector index

`ResilientVectorIndex` coordinates a Qdrant primary index and `RebuildableInMemoryVectorIndex` fallback.

- Inserts always update the in-memory fallback.
- Search uses Qdrant only while its circuit is available.
- Qdrant failure returns the fallback result instead of failing the turn.
- A successful recovery probe closes the circuit only after collection validation succeeds.

This coordinator contains infrastructure policy only. It does not inspect personality or choose emotional behavior.

### Projection synchronizer

Add a dedicated `QdrantProjectionSynchronizer` coroutine owned by server runtime startup and shutdown.

- A conflated wake-up signal prompts a near-immediate drain after a memory commit.
- A periodic scan catches missed signals and performs recovery.
- Work is read in bounded batches ordered by availability time and memory ID.
- A successful batch marks exactly those rows ready.
- A failed batch reschedules its rows with bounded exponential backoff.
- Process restart recovers abandoned running rows before work begins.

Only one synchronizer runs because the supported deployment has one server instance. The schema still records status transitions explicitly so restart recovery is deterministic.

## Qdrant Collection Design

### Physical naming

The configured prefix defaults to `openeden_memory`. The physical collection name includes a sanitized embedding `modelId` and a short stable hash of the unsanitized model ID. The hash prevents two distinct IDs from collapsing to the same sanitized name.

Model versions never share a physical collection. This prevents semantically incompatible embeddings with equal dimensions from being compared.

### Named vectors

Each point has two named vectors:

```text
semantic  -> text embedding, Cosine distance
emotional -> 8D-derived emotional embedding, Cosine distance
```

Both dimensions are fixed per physical collection and derived from validated embeddings. Empty, non-finite, or zero-length vectors are rejected before projection.

The initial candidate query uses the semantic vector and requests at most 128 points. Memory Palace performs the existing exact hybrid final score over the hydrated candidates using semantic similarity, emotional similarity, momentum, and identity affinity. Storing the emotional named vector preserves the full dual-key projection and permits a later candidate-recall enhancement without a data migration.

### Payload

Each point payload contains only:

```text
memory_id
session_id
room
kind
model_id
```

Payload indexes are created for `session_id`, `room`, `kind`, and `model_id`. Search always filters by the exact session ID and adds room/kind filters when the request supplies them.

### Point identity

Qdrant point IDs are deterministic UUIDs derived from the complete UTF-8 `memory_id` using a stable SHA-256-based mapping. The original ID remains in payload. Reprocessing the same memory therefore produces an idempotent upsert.

## SQLite Persistence Design

### Projection table

Add `memory_vector_sync` with one current projection row per memory:

```text
memory_id         TEXT PRIMARY KEY REFERENCES memory_entries(id) ON DELETE CASCADE
model_id          TEXT NOT NULL
status            TEXT NOT NULL
attempts          INTEGER NOT NULL
available_at_ms   INTEGER NOT NULL
last_error        TEXT
updated_at_ms     INTEGER NOT NULL
```

Allowed status values are `PENDING`, `RUNNING`, and `READY`. SQLDelight queries select due pending rows through an index on `(status, available_at_ms, memory_id)`.

The existing `memory_embeddings.status` continues to describe whether the local embedding is usable. It is not repurposed as remote synchronization state.

### Atomic memory write

A normal memory write performs one SQLite transaction:

1. Upsert the memory entry.
2. Upsert semantic and emotional embeddings with the configured model ID and local status `READY`.
3. Upsert the projection row as `PENDING`, reset attempts, clear the last error, and set availability to now.

Only after the transaction commits is the in-memory fallback updated and the synchronizer signaled. Qdrant network I/O never runs inside the SQLite transaction.

If the SQLite transaction fails, no projection is attempted and the existing memory-write failure semantics apply. If updating the in-memory fallback fails, it is marked dirty and lazily rebuilt from SQLite as today.

### Candidate hydration

Add a bounded SQLDelight query that loads complete stored memories for candidate IDs. The repository must:

- cap candidate IDs at the configured candidate limit;
- discard missing or wrong-session rows;
- discard rows whose model ID does not match the active projection;
- restore Qdrant result ordering without issuing one query per memory.

### Initial migration

The schema migration creates a pending projection row for every memory with a locally ready embedding. Existing embedding rows whose `model_id` is absent, `unknown`, or different from the configured active model are not projected as compatible vectors. They enter the model-refresh path described below.

## Embedding Model Changes

The server configuration supplies an explicit non-blank `modelId`, defaulting to `local-v1` for the current artifact. The repository no longer writes the literal `unknown` for normal production memories.

When a stored memory uses a different model ID:

1. Load its content and stored 8D snapshot from SQLite.
2. Recompute semantic and emotional embeddings through the configured `MemoryEmbeddingModel` on `InferenceExecutor`.
3. Transactionally replace the local embeddings and reset the projection row to `PENDING` for the active model.
4. Project the refreshed vectors to the active physical collection.

Refresh runs in bounded batches and yields between batches. Model refresh must never execute on the Ktor request dispatcher or delay server readiness.

Old physical collections are not automatically deleted. Automatic deletion would be destructive and is unnecessary for correctness. Operators may remove them after verifying the active projection.

## Write And Read Flows

### Write

```text
MemoryEntry
  -> SQLite transaction: entry + embeddings + PENDING projection
  -> update in-memory fallback
  -> signal synchronizer
  -> return to runtime pipeline

Synchronizer
  -> lease bounded PENDING batch
  -> ensure compatible collection
  -> Qdrant batch upsert
  -> mark READY, or reschedule with backoff
```

The dialogue path never awaits Qdrant upsert.

### Read

```text
query embedding
  -> Qdrant semantic search with exact payload filters
  -> bounded SQLite hydration by candidate IDs
  -> Memory Palace final mode-specific ranking
  -> RetrievalResult with selected mode
```

### Degraded read

```text
Qdrant unavailable, timed out, invalid, or circuit open
  -> ensure session is loaded into the in-memory index
  -> in-memory candidate search
  -> Memory Palace final mode-specific ranking
  -> normal RetrievalResult plus degraded trace tag
```

Qdrant failure must not produce an empty result merely because the remote service failed. An empty healthy Qdrant result remains a valid empty candidate result.

## Failure And Recovery Policy

### Circuit breaker

The Qdrant read circuit has three states: closed, open, and half-open.

- Three consecutive availability or protocol failures open the circuit.
- An open circuit bypasses Qdrant without a network call.
- The background synchronizer performs recovery probes after the configured interval.
- A successful health probe plus collection validation moves the circuit through half-open to closed.
- A failed probe reopens it and retains backoff.

Authentication failures and incompatible collection schemas also open the circuit, but are reported distinctly in diagnostics because repeated retries cannot repair configuration.

### Retry policy

Projection attempts use exponential backoff with jitter, starting at the configured synchronization interval and capped at five minutes. Retry state is durable. There is no terminal failed status because SQLite remains authoritative and Qdrant may recover later.

Error text stored in SQLite is sanitized and capped. It must not include an API key, memory content, embedding values, or a full upstream response body.

### Collection loss

If the active physical collection is absent, the synchronizer recreates its schema and resets all compatible local embeddings to `PENDING` in bounded operations. It then rebuilds the collection from SQLite.

If the collection exists and validates, durable pending rows cover writes missed during an outage. A manual full-rebuild operation is not part of the first public API; collection deletion is the supported operator action for forcing a complete rebuild.

### Cancellation and shutdown

All components rethrow `CancellationException`. Runtime shutdown cancels and joins the synchronizer before closing the Qdrant HTTP client or SQLite repository. Pending rows remain durable and resume after restart.

## Non-Blocking And Inference Isolation

- Qdrant uses Ktor's suspendable HTTP client; no blocking HTTP library is introduced.
- SQLite queries remain on the persistence IO dispatcher.
- Embedding generation, regeneration, vector validation that performs model work, and model-refresh batches run through `InferenceExecutor`.
- Coordinate mapping, symmetry calculations, momentum calculations, VQ-VAE work, and existing final ranking retain their current inference-dispatcher guarantees.
- No Qdrant operation holds a session vector Mutex or a SQLite transaction open.
- Background projection and model refresh run in dedicated child coroutines under the server runtime supervisor.

## Configuration

Add the following server configuration with environment-variable overrides:

```yaml
openeden:
  vectorDatabase:
    enabled: "$OPENEDEN_VECTOR_DB_ENABLED:true"
    url: "$OPENEDEN_QDRANT_URL:http://localhost:6333"
    apiKey: "$OPENEDEN_QDRANT_API_KEY:"
    collectionPrefix: "$OPENEDEN_QDRANT_COLLECTION:openeden_memory"
    modelId: "$OPENEDEN_EMBEDDING_MODEL_ID:local-v1"
    requestTimeoutMs: "$OPENEDEN_QDRANT_TIMEOUT_MS:2000"
    syncIntervalSeconds: "$OPENEDEN_QDRANT_SYNC_INTERVAL_SECONDS:30"
    syncBatchSize: "$OPENEDEN_QDRANT_SYNC_BATCH_SIZE:128"
    failureThreshold: "$OPENEDEN_QDRANT_FAILURE_THRESHOLD:3"
```

Validation requires:

- an HTTP or HTTPS URL without user-info credentials;
- a non-blank collection prefix and model ID;
- positive timeout, interval, batch size, and failure threshold;
- a batch size capped to a conservative operational maximum.

When `enabled` is false, OpenEden uses SQLite plus the in-memory index and does not start the Qdrant client or synchronizer.

## Deployment

Add a root `compose.yaml` with a pinned Qdrant image, a persistent named volume, port `6333`, and a health check. The compose file provides Qdrant only; it does not change how the OpenEden JVM is launched.

Documentation covers:

- starting and stopping Qdrant;
- configuring a remote URL and optional API key;
- persistence volume ownership;
- interpreting degraded status;
- forcing a rebuild by deleting only the active Qdrant collection;
- backing up SQLite as the authoritative recovery artifact.

Qdrant startup is optional. OpenEden starts successfully when Qdrant is absent.

## Observability

Add trace tags or attributes for:

- `vector_db=QDRANT` on successful remote candidate retrieval;
- `vector_db=QDRANT_FALLBACK` when a turn uses the in-memory index;
- `vector_db=QDRANT_RECOVERED` on a circuit recovery transition;
- projection batch success, retry, collection creation, and rebuild progress.

The protected diagnostics response adds:

- configured backend and active physical collection;
- circuit state;
- due and total non-ready projection counts;
- last successful remote operation time;
- last sanitized error category and time.

The public `/health` endpoint remains ready during Qdrant degradation because the in-memory fallback is operational. Logs and diagnostics never expose memory bodies, embeddings, or credentials.

## Package Organization

Production types remain focused and follow the repository package boundaries:

- `core/.../memory`: candidate-index contracts and the in-memory implementation;
- `server/.../vector/qdrant`: Qdrant REST DTOs, client, collection naming, and index adapter;
- `server/.../vector/sync`: projection worker, circuit state, and retry policy;
- `server/.../persistence/sqldelight`: projection-task persistence and candidate hydration;
- `server/.../bootstrap`: configuration and lifecycle wiring only.

Qdrant transport DTOs, synchronization state, API authentication, and Ktor client construction must not be accumulated in `SqlDelightMemoryRepository` or a module-root package.

## Testing Strategy

Implementation follows test-driven development. Each behavior starts with a focused failing test.

### Core tests

- In-memory and remote candidate adapters obey the same ID-based search contract.
- Candidate order and similarity values survive SQLite hydration.
- Existing CONGRUENT, MIXED, CONTRAST, momentum, and identity-affinity tests remain unchanged and pass.
- Resilient search falls back on availability and protocol errors.
- Cancellation propagates without fallback or circuit mutation.
- Circuit transitions are deterministic under a fake clock.

### Qdrant client tests

Use Ktor `MockEngine` to verify:

- collection inspection and named-vector creation;
- payload-index creation;
- idempotent batch upsert request shape;
- semantic search request shape and exact filters;
- API-key header handling without diagnostic leakage;
- timeouts, non-success responses, malformed bodies, and incompatible schemas;
- deterministic point UUID generation.

### SQLDelight tests

- Memory, embeddings, and `PENDING` projection are committed atomically.
- Projection status and retry metadata survive close and reopen.
- Running rows recover to pending after restart.
- Due work is ordered and batch bounded.
- Candidate hydration is one bounded query, rejects wrong-session/model rows, and preserves Qdrant order.
- Initial migration seeds compatible rows without treating `unknown` as current.
- A model change refreshes embeddings and targets a separate collection.

### Synchronizer tests

Use coroutine virtual time and fake clients to verify:

- commit signals trigger a prompt batch drain;
- periodic scans recover missed signals;
- successful batches mark only acknowledged rows ready;
- failures persist sanitized errors and bounded exponential backoff;
- missing collections cause recreation and complete compatible reindexing;
- recovery closes the circuit only after schema validation;
- shutdown leaves unfinished work recoverable.

### Integration test

Provide an opt-in real-Qdrant integration test enabled only when `OPENEDEN_QDRANT_TEST_URL` is set. It creates an isolated collection, upserts vectors, filters by session, verifies search and recovery, and removes only that test collection during cleanup.

Normal `test` tasks and CI do not require Docker or network access.

### Verification gate

Run at minimum:

```powershell
.\gradlew.bat :core:jvmTest :server:test
.\gradlew.bat build
git diff --check
```

When a local Qdrant is available, also run the opt-in integration test and report it separately.

## Acceptance Criteria

- Production retrieval uses Qdrant candidates while Qdrant is healthy.
- Every memory remains durably recoverable from SQLite without Qdrant.
- Memory writes never wait for Qdrant network I/O.
- Qdrant absence at startup or runtime does not fail dialogue turns.
- Failed projections remain durably pending and synchronize after recovery.
- A missing active collection rebuilds automatically from SQLite.
- Model IDs isolate incompatible embeddings and trigger bounded background refresh.
- Qdrant candidate IDs are hydrated from SQLite in a bounded batch before final Memory Palace ranking.
- Existing retrieval modes and prompt behavior remain intact.
- Cancellation, inference isolation, and non-blocking constraints are preserved.
- Diagnostics distinguish healthy, degraded, recovering, and configuration-error states without leaking sensitive data.
- SQLite remains the only authoritative structured database; PostgreSQL is not introduced.
- Persona data, VQ-VAE quantization, derived dissonance, 8D persistence, and evolution behavior are unchanged.
