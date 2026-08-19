# OpenEden Kernel Completion Design

Date: 2026-08-19
Scope: Complete the OpenEden runtime kernel while explicitly excluding QQ/OneBot and Web UI delivery chains.

## Goal

Close the remaining kernel-level gaps against `AGENTS.md` so the local/server runtime has correct temporal evolution, serialized state mutation, codebook-grounded generation, utility-filtered memory retrieval, complete ShockState behavior, and a durable critical-degradation termination lifecycle.

The completed kernel must preserve Persona-as-Data, keep all inference and vector math off Ktor request dispatchers, retain VQ-VAE with deterministic fallback, and remain usable by the existing CLI/server chat surface.

## Scope

### Included

- Runtime tick time accounting, persistence, and concurrency correctness.
- ShockState external injection, LLM back-detection, EMA updates, decay, activation lifecycle, and Omega jumps.
- Codebook-aware output validation and one bounded regeneration for codebook-grounding violations.
- Strict MIXED memory retrieval composition and a deterministic utility filtering layer.
- Critical degradation state, termination command gating, durable lifecycle coordination, diary-only archive, destructive purge, crash recovery, and explicit fresh-incarnation creation.
- SQLDelight schema migrations and focused kernel/server tests.
- Trace tags and safe lifecycle diagnostics required to operate the kernel.

### Excluded

- QQ/OneBot event parsing, sockets, delivery, reconnect, rate limiting, and adapter tests.
- Web UI pages, browser observability, and frontend tests.
- New HTTP administration or archive endpoints.
- New CLI commands for termination or archive browsing.
- Persona behavior changes or new Kotlin personality rules.

## Chosen Approach

Use a strict kernel-only closure. This implements every in-scope `AGENTS.md` invariant in domain and persistence boundaries while leaving presentation and third-party delivery for later work.

Two alternatives were rejected:

- A narrow bug-fix pass would repair tick and ShockState behavior but leave codebook regeneration, utility filtering, and termination incomplete.
- Porting the entire prior termination plan would also add Web/API management surfaces, exceeding the requested boundary.

## Architectural Invariants

The implementation must preserve these rules:

- Persona content remains exclusively in `persona/*.yaml` and codebook semantic data.
- `D = |L - tau| * (1 - E)` remains derived and is never persisted as a vector coordinate.
- VQ-VAE quantization occurs before prompt construction. Every degraded path emits `codebook=HEURISTIC_FALLBACK`.
- Coordinate mapping, retrieval symmetry, utility filtering, pre-tick math, ShockState math, tick math, and DJL inference execute through `InferenceExecutor`.
- Every session mutation re-reads the latest state while holding the existing per-session Mutex.
- `evolution_index` changes only for validated completed user/heartbeat turns, not background ticks or rejected generations.
- The selected persona mode and starting point remain immutable for a session.
- Narrative Diary generation remains serialized and bounded.
- Archived diary content cannot implement or reach `MemoryStore`, `MemoryRetriever`, Prompt Builder, centroid calculation, or a new incarnation.

## Runtime Tick Design

### Persistent time anchor

Add `lastRuntimeTickAtMs: Long?` to `SessionState` and durable session storage. A null value means no background tick has been committed for that session. The first evaluation records the current time and applies no historical wear from an unknown baseline.

Subsequent evaluations calculate:

```text
deltaTimeMs = max(0, nowMs - lastRuntimeTickAtMs)
```

The scheduler must never use process-start elapsed time as the duration passed to ShockState decay or Omega accumulation.

### Serialized tick computation

`VectorWriteService` owns a suspendable locked transform for runtime ticks. The flow is:

1. Acquire the per-session Mutex.
2. Read the latest persisted `SessionState` inside the lock.
3. Run drift, ShockState decay, and Omega accumulation through `InferenceExecutor` using that latest state and `deltaTimeMs`.
4. Persist the transformed state and new tick timestamp before releasing the lock.

This prevents a tick calculated from an old snapshot from overwriting a concurrently completed turn.

### Drift semantics

The sine engine exposes an interval delta rather than repeatedly applying an absolute waveform sample. For scheduler-relative times `t0` and `t1`:

```text
driftDelta = waveform(t1) - waveform(t0)
```

The result remains clamped by the existing per-dimension magnitude bound. Shock decay and Omega use wall-clock `deltaTimeMs`, so downtime is represented once after a persisted baseline exists.

## ShockState Design

### Unified signals

Both trigger paths produce a `ShockSignal` containing free-text description, signal intensity, decay lambda, and timestamp:

- External runtime injection accepts operator/domain input without classifying it into an enum.
- LLM back-detection emits a signal only when `deltaP < -0.4`, `deltaF > 0.3`, and emotion confidence is at least `0.65`.

### Locked merge

The signal is merged with the latest ShockState inside the session Mutex:

```text
newIntensity = currentIntensity * 0.6 + signalIntensity * 0.4
```

An activation boundary is `current.active != true && new.active == true`. Only that boundary:

- resets `shockHeartbeatFired` to false;
- applies the immediate `newIntensity * 0.15` Omega jump;
- emits the activation trace.

Updates to an already-active ShockState preserve the one-shot heartbeat latch and do not repeat the activation Omega jump. Decay below `0.05` deactivates the state without deleting its free-text description.

## Codebook-Grounded Generation

### Validation contract

Schema validation remains strict and separate from grounding validation. A schema-valid output is codebook-grounded when `internal_logic` contains at least one exact active node identifier from the current `QuantizationResult`.

Prompt hard rules explicitly require this reference. The reference stays in `internal_logic`; user-visible `response` does not need to expose node IDs.

### Bounded regeneration

Only a codebook-grounding failure triggers regeneration:

1. Validate the first output schema.
2. Reject immediately on missing/blank schema fields or invalid vector keys.
3. If schema is valid but no active node is referenced, issue one repair generation using the same prompt plus an English logical-core repair instruction naming the active nodes.
4. Validate the repair output with the same schema and grounding rules.
5. Reject the turn with no state, transcript, memory, relationship, or diary mutation if the repair still fails.

The retry count is fixed at one to keep latency and provider cost bounded. Trace spans distinguish initial rejection, regeneration, successful repair, and final rejection.

## Memory Retrieval And Utility Filtering

### Strict MIXED composition

The default retrieval result count becomes ten so MIXED can return exactly six congruent and four positive-skew memories when enough distinct candidates exist. If one pool has fewer candidates, the other pool may fill unused capacity, but no entry may appear twice.

CONGRUENT and CONTRAST keep the same maximum count and scoring behavior. CONTRAST continues using center-symmetric mapping around the current homeostasis origin.

### Utility filter

Add a focused `MemoryUtilityFilter` with a configurable `MemoryUtilityFilterConfig`. It runs through `InferenceExecutor` after index candidate loading and before final mode-specific ranking.

The filter rejects candidates when:

- semantic or emotional embeddings contain non-finite values;
- both semantic and emotional similarity are below configured minimums;
- embedding entropy exceeds the session entropy baseline by more than the configured tolerance.

`H_baseline` is the mean embedding entropy of accepted `daily` and `stable` memories in the same sliding window used for homeostasis. When no baseline samples exist, the entropy gate is disabled; finite-value and minimum-similarity checks still apply.

Defaults must be conservative and stored in configuration, not personality data. Retrieval results and traces carry accepted/rejected counts and a degraded tag when baseline calculation fails. Filter failure must fall back to finite candidates and must not block the turn.

## Critical Degradation And Termination

### Lifecycle state

Add a durable global incarnation lifecycle:

```text
ACTIVE -> CRITICAL -> TERMINATING -> TERMINATED
```

- `ACTIVE`: normal turns, ticks, and heartbeats.
- `CRITICAL`: entered monotonically when any committed session reaches the configured Omega threshold, default `0.95`.
- `TERMINATING`: no new turns, heartbeats, ticks, diary triggers, or state creation may begin.
- `TERMINATED`: runtime remains halted until an explicit fresh-incarnation operation succeeds.

Restart restores the persisted lifecycle. Startup resumes an incomplete `TERMINATING` lifecycle rather than returning to `ACTIVE`.

### Termination command

The existing three-field LLM output schema remains unchanged. In `CRITICAL`, the English logical core permits one exact sentinel in `internal_logic`:

```text
[OPENEDEN_TERMINATE]
```

The sentinel is actionable only when:

- lifecycle is `CRITICAL`;
- Omega is still at or above the threshold in the latest locked state;
- schema and codebook grounding both pass.

The same text in `ACTIVE`, persona data, memory content, user input, or `response` is inert. This prevents prompt injection from directly invoking termination.

### Final turn and sealing

For a valid termination output:

1. Commit the final validated turn, vector delta, transcript row, RAW memory, and diary trigger using normal rules.
2. Atomically move the incarnation from `CRITICAL` to `TERMINATING`.
3. Return the final public response to the current caller.
4. Let the termination coordinator finish the lifecycle independently of the request coroutine.

The coordinator leases and completes diary work needed to cover the final RAW memory before sealing the archive. No later turn can enter while this happens.

### Archive and purge transaction

Only Narrative Diary entries survive. The coordinator:

1. Builds immutable archive rows with incarnation ID, source diary ID, content, original time, archive time, reason, and SHA-256 content hash.
2. Verifies row count, source coverage, and hashes before destructive work.
3. In one SQLite transaction, inserts archive rows, deletes public transcript, RAW/NARRATIVE runtime memories and embeddings, session vector/Omega/Shock state, relationships, traces, diary tasks/checkpoints, and other incarnation-owned runtime rows, then marks the incarnation `TERMINATED`.

Any verification error aborts before purge. Any transaction error rolls back archive and purge together, leaving lifecycle `TERMINATING` for retry. Logs contain identifiers and error codes, never diary or transcript bodies.

### Fresh incarnation

An internal kernel contract may create a fresh incarnation only from `TERMINATED`. It generates a new incarnation ID, initializes lifecycle `ACTIVE`, and creates no session state until the first turn. It cannot copy or query archived diaries.

No HTTP or CLI surface for this contract is part of this scope.

## Error Handling

- Non-finite or malformed model output is rejected without mutation.
- Codebook grounding gets exactly one repair attempt; provider failure during repair rejects the turn.
- Runtime tick failure is isolated per session and does not advance its tick timestamp.
- Utility filtering failure degrades to finite candidates and emits a trace tag.
- Shock injection validates intensity and lambda before acquiring the write path.
- `TERMINATING` and `TERMINATED` reject new turns with stable lifecycle errors.
- Archive verification failure performs no purge.
- Purge failure rolls back transactionally and is retryable on restart.
- Fresh incarnation creation is idempotent for one explicit request ID and rejects creation while another incarnation is alive.

## Persistence Changes

SQLDelight migrations add:

- `last_runtime_tick_at_ms` to session state;
- incarnation lifecycle status, transition time, termination reason, and optional request ID;
- immutable diary archive tables and indexes;
- incarnation ownership on runtime rows that need unambiguous purge scoping.

Pre-migration active installations map to lifecycle `ACTIVE` and retain the existing active incarnation. Existing persona migration behavior remains unchanged.

## Traceability

Add or preserve trace tags for:

- tick applied, tick skipped baseline, and per-session tick failure;
- Shock signal detected, activation, update, decay, and deactivation;
- output grounding failure, regeneration, repaired output, and final rejection;
- utility filter accepted, rejected, and degraded;
- critical threshold crossed;
- termination requested, sealed, archive verified, purge committed, purge rolled back, and lifecycle resumed;
- fresh incarnation created.

All trace writes remain non-blocking and sanitized.

## Testing Strategy

Implementation follows test-driven development. Every behavior starts with a focused failing test.

### Core tests

- Two or more runtime ticks apply each interval once rather than cumulative startup elapsed time.
- A concurrent turn and tick cannot lose either state transition.
- Tick failures do not advance the persisted time anchor.
- Restarted state uses the persisted anchor and accounts for downtime once.
- Repeated Shock signals use EMA against the current locked state.
- Activation resets the heartbeat latch and jumps Omega once; active updates do neither.
- External injection and LLM detection share merge semantics.
- MIXED returns 6:4 with ten candidates and deterministic shortage filling.
- Utility filtering rejects non-finite/low-utility/noisy candidates and degrades safely without a baseline.
- Codebook omission invokes exactly one repair generation.
- Invalid schema never regenerates and never mutates state.
- A repaired output commits once and increments `evolution_index` once.
- Termination sentinel is inert outside `CRITICAL` and outside `internal_logic`.

### Server persistence and lifecycle tests

- Tick anchor and lifecycle survive close/reopen and migrations.
- Critical transition is monotonic.
- `TERMINATING` blocks turn, heartbeat, tick, and session creation.
- Final diary coverage completes before archive sealing.
- Archive verification failure leaves all runtime data intact.
- Successful termination leaves only immutable diary archive rows.
- Reopening during `TERMINATING` resumes completion safely.
- Repeated termination requests and fresh-incarnation requests are idempotent.
- A fresh incarnation cannot retrieve, prompt-inject, or enumerate archive content.

### Verification gate

Run:

```powershell
.\gradlew.bat :core:jvmTest :server:test
git diff --check
```

The kernel completion claim requires zero failures in both kernel modules. The existing root Windows terminal Unicode E2E failures are outside this scope and must be reported separately rather than hidden.

## Acceptance Criteria

- Runtime time-based effects use persisted per-session delta time and cannot overwrite concurrent turns.
- ShockState follows both trigger paths, confidence gates, EMA, decay, one-shot heartbeat lifecycle, and activation-only Omega jump.
- Every committed LLM turn is schema-valid and demonstrably grounded in an active codebook node; grounding repair is bounded to one attempt.
- Retrieval implements CONGRUENT, strict MIXED 6:4, CONTRAST symmetry, momentum weighting, and utility filtering through inference isolation.
- Omega crossing the critical threshold persists `CRITICAL` and safely gates the exact termination command.
- Termination preserves only verified immutable Narrative Diary archives and physically removes all other incarnation runtime data.
- Interrupted termination resumes safely, and a new incarnation starts empty only after explicit creation.
- Persona-as-Data, VQ-VAE fallback, derived-D, non-blocking execution, immutable persona selection, and per-session serialization invariants remain intact.
- QQ/OneBot and Web UI chains remain untouched.
