# OpenEden Architecture Skeleton Design

Date: 2026-06-10
Scope: initialize the project framework for the OpenEden runtime.

## Context

The repository currently contains a fresh Ktor/Gradle multi-module scaffold with
`client`, `core`, and `server` modules. The generated sample service and routes
compile the basic project shape, but they do not yet encode the runtime
boundaries required by `AGENTS.md`.

The initialization target is an architecture skeleton: a buildable, testable
framework that establishes the core invariants before real model inference,
Memory Palace persistence, OneBot adapters, or final persona content are added.

## Goals

1. Establish module boundaries that keep personality as external data.
2. Add pure core domain types for the 8D physiological vector, derived
   dissonance, Omega, ShockState, retrieval mode, and session state snapshots.
3. Define asynchronous runtime interfaces for quantization, prompt construction,
   vector write-back, diary writes, memory retrieval, and heartbeat delivery.
4. Provide deterministic heuristic codebook fallback behavior with a
   `codebook=HEURISTIC_FALLBACK` trace tag.
5. Ensure vector write paths are shaped around per-session mutexes and apply
   `vector_delta` to the pre-ticked snapshot.
6. Add focused tests for the invariants that can be verified without DJL or
   external services.
7. Replace generated sample names and README content with OpenEden-specific
   framework documentation.

## Non-Goals

This initialization will not implement trained DJL/VQ-VAE inference, persistent
Memory Palace storage, OneBot v11 WebSocket adapters, production LLM provider
calls, final Chinese persona text, or complete heartbeat scheduling. Those
systems will plug into the interfaces created here.

## Compliance Checks

### Persona-as-Data

Kotlin code will define loading and validation boundaries for persona config,
sub-state thresholds, and prompt sections. It will not hardcode personality
behavior, emotional expression, response templates, or identity text. Prompt
builder inputs will accept persona data and codebook semantic text as data.

### Non-Blocking Constraint

Runtime APIs that can perform I/O, model work, coordinate mapping, retrieval,
diary writes, or prompt building will be `suspend` APIs or `Flow` producers.
Inference-like work will be represented behind an `InferenceDispatcher`
boundary. Server routes will call suspend services and will not use blocking
thread sleeps, synchronous file I/O in request paths, or global locks.

### VQ-VAE Pipeline

The core pipeline will require a `CodebookQuantizer` abstraction that accepts an
8D vector and derived dissonance context. A heuristic fallback implementation
will be provided for cold start and inference failure. Prompt construction will
consume semantic node definitions or fallback semantic text, not raw floats as
personality instructions.

## Proposed Module Layout

### `core`

`core` owns shared domain and runtime contracts:

- `bio`: 8D vector type, dimension names, delta type, derived dissonance, clamp
  and threshold helpers.
- `bio.mapping`: storage-to-internal and internal-to-storage piecewise mapping,
  centroid-aware transforms, center-symmetric mapping.
- `runtime`: session state, pre-tick result, vector write service, Omega update
  rules, ShockState update and decay.
- `codebook`: quantizer interfaces, quantization result, heuristic fallback.
- `memory`: retrieval mode selector, retrieval result contract, memory metadata
  including `delta_vec` and `snapshot_origin`.
- `persona`: persona config contracts, evolution thresholds loaded from data.
- `prompt`: prompt builder contract and input context types.
- `trace`: trace tags and trace event contracts.

The module stays pure where possible and does not depend on Ktor server APIs.

### `server`

`server` owns Ktor application wiring:

- health and status routes for verifying the app starts;
- route modules that depend on runtime interfaces;
- dependency assembly for in-memory skeleton implementations;
- logging configuration and request trace propagation.

The server will not contain persona behavior or vector math beyond calling core
services.

### `client`

`client` remains a shared client boundary for future Web, CLI, or platform
frontends. During initialization it can expose typed HTTP/client contracts but
does not need user-facing behavior.

### Root Project

The root Gradle project will remain the build orchestrator. It should not
duplicate server application entry points or contain runtime logic that belongs
in `core` or `server`.

## Core Data Flow

1. A message enters the server as an `IncomingMessage`.
2. Session identity is resolved as a shared state key.
3. The runtime obtains the per-session mutex for any write path.
4. Emotion confidence gates pre-tick behavior:
   - confidence below `0.5`: skip pre-tick;
   - otherwise scale perturbation by confidence and clamp every dimension to
     `MAX_PRETICK_DELTA = 0.25`.
5. The pre-ticked snapshot is quantized through `CodebookQuantizer`.
6. If quantization is unavailable or low-confidence, heuristic fallback returns
   deterministic semantic text and emits `codebook=HEURISTIC_FALLBACK`.
7. Derived dissonance is computed as `abs(L - tau) * (1 - E)` before prompt
   construction and is not stored as a vector dimension.
8. Memory retrieval mode is selected once by `RetrievalModeSelector` and carried
   in `RetrievalResult`.
9. Prompt builder receives codebook semantic text, derived dissonance, retrieval
   result, persona data, and message content.
10. LLM output is validated against the required schema.
11. `vector_delta` is applied to the pre-ticked snapshot, not the original
    pre-pre-tick vector.
12. State, Omega, evolution index, memory metadata, and diary trigger events are
    written through serialized session-aware services.

## Key Components

### 8D Vector

The stored dimensions are exactly `[L, P, E, S, tau, V, M, F]`. Dissonance is
derived at runtime and never appears in `snapshot_8D`, `delta_vec`, or training
data contracts.

### Dual-Space Mapping

Mapping functions will operate in the inference/runtime boundary and use the
dynamic homeostasis centroid as the origin. The piecewise formula maps storage
space `[0.0, 1.0]` into internal space `[-1.0, 1.0]`, with a matching inverse
function for remapping retrieval targets.

### ShockState

ShockState uses:

- `active: Boolean`
- `intensity: Float`
- `description: String`
- `triggeredAt: Instant`
- `decayLambda: Float`

No enum will categorize shock source or type. Intensity updates use EMA with
alpha `0.4`. Activation applies the immediate Omega jump
`omega += intensity * 0.15`.

### Retrieval Mode Selector

Mode selection order is:

1. active ShockState with intensity at least `0.6` -> `CONTRAST`
2. Omega at least `0.75` -> `CONTRAST`
3. internal P below `-0.3` and internal V below `-0.2` -> `MIXED`
4. otherwise -> `CONGRUENT`

The prompt builder uses the selected mode from `RetrievalResult` and does not
re-evaluate the state.

### Session Serialization

All vector write-back and evolution index updates are protected by a per-session
mutex. The latest persisted state is re-read inside the lock before writing.
The skeleton may use in-memory storage, but its interface must allow a durable
store later.

Narrative diary writes will be represented by a bounded per-session queue with a
maximum of eight pending events. Overflow emits `diary=QUEUE_OVERFLOW`.

## Error Handling

Quantization failures degrade to heuristic fallback and trace the degraded mode.
Low-confidence emotion signals do not trigger ShockState and may skip pre-tick.
Disconnected heartbeat adapters drop pending heartbeat messages instead of
queuing stale messages. Validator failures reject or regenerate LLM output
through explicit result types instead of throwing across route boundaries.

## Testing Plan

Focused tests will cover:

- derived D calculation and absence from stored vector/delta contracts;
- storage/internal piecewise mapping and inverse mapping;
- center-symmetric retrieval target mapping;
- heuristic fallback thresholds and trace tag;
- pre-tick confidence gate and per-dimension cap;
- applying delta to the pre-ticked snapshot;
- ShockState EMA update, confidence gate, decay, and Omega jump;
- retrieval mode priority order;
- per-session mutex write sequencing with independent sessions not blocking
  each other;
- persona threshold loading from config-shaped data instead of hardcoded values.

## Acceptance Criteria

The project framework is initialized when:

1. Gradle build and server tests pass.
2. The README describes OpenEden framework modules and commands.
3. Generated sample service names are removed or isolated from the public
   framework surface.
4. Core runtime contracts compile and tests verify the critical invariants.
5. No Kotlin code hardcodes persona behavior.
6. No blocking operation is introduced into request/runtime paths.
7. VQ-VAE quantization is represented by an async contract with heuristic
   fallback and trace tagging.
