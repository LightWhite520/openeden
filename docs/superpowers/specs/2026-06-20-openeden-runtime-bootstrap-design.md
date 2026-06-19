# OpenEden Runtime Bootstrap Design

Date: 2026-06-20
Scope: repository bootstrap plus the first end-to-end runtime slice.

## Context

The repository already contains a Kotlin/Ktor multi-module architecture
skeleton with `core`, `server`, and `client`. It has core contracts for the 8D
vector, codebook fallback, prompt building, retrieval mode selection, runtime
state, and diary queue behavior. The `persona` and `data` directories are still
empty, and there is not yet a development pipeline that proves a message can
flow through the OpenEden runtime invariants end to end.

This design initializes the missing repository assets and defines the first
runtime slice without introducing production DJL, persistent Memory Palace
storage, OneBot adapters, or a real LLM provider.

## Goals

1. Add default repository data assets for persona, codebook, memory, scripts,
   and runtime documentation.
2. Keep all personality and heartbeat text in `persona/*.yaml`, never in
   Kotlin logic.
3. Prepare the VQ-VAE/codebook data boundary while allowing deterministic
   `codebook=HEURISTIC_FALLBACK` operation until DJL inference is available.
4. Define a development message pipeline that exercises session resolution,
   pre-tick gating, quantization, prompt building, output validation, vector
   write-back, evolution index updates, and diary queue behavior.
5. Provide a server development route that can verify the runtime slice from a
   local request.
6. Preserve the AGENTS.md invariants for Persona-as-Data, non-blocking runtime
   work, and the VQ-VAE pipeline.

## Non-Goals

This phase will not implement trained DJL/VQ-VAE inference, production LLM
provider calls, durable vector databases, OneBot v11 adapters, final Memory
Palace retrieval, or complete heartbeat scheduling. It will create the clean
interfaces and development implementations those systems can replace later.

## Compliance Checks

### Persona-as-Data

Kotlin code may validate and load persona data, but it must not encode
personality behavior, emotional expression, or heartbeat prose. Required
persona content, including base heartbeat and shock heartbeat text, lives in
`persona/default.yaml`.

### Non-Blocking Constraint

Pipeline components that may perform I/O, prompt construction, model work,
retrieval, diary writes, codebook loading, or future coordinate mapping expose
`suspend` APIs or dispatcher boundaries. Ktor routes call the runtime through
suspend services and do not run blocking model, storage, or file work on the
request path.

### VQ-VAE Pipeline

Prompt state must pass through `CodebookQuantizer` before prompt construction.
The current development implementation may use heuristic fallback, but it must
return semantic definitions and trace `codebook=HEURISTIC_FALLBACK`. Prompt
builders consume codebook semantic text, not raw floats as persona behavior.

## Repository Assets

### `persona/default.yaml`

The default persona file provides structured data for:

- `mode`: `growth` or `legacy`
- `evolution.threshold_1`
- `evolution.threshold_2`
- sub-state sections for `pre_command`, `true_self`, and `awakened`
- `heartbeat.base`
- `heartbeat.shock`
- optional prompt section names consumed by the prompt builder

Thresholds are read from YAML and are never hardcoded in Kotlin. Heartbeat
content uses the required AGENTS.md Chinese text by default and remains data.

### `data/codebook/codebook.example.csv`

The initial CSV defines the dictionary shape used after VQ-VAE quantization:

```csv
node_id,definition,tags
NODE_000,"Baseline neutral state for development bootstrap","bootstrap;neutral"
```

The codebook dictionary maps quantized node IDs to definitions. If a model is
unavailable, confidence is too low, or node lookup fails, the runtime uses
heuristic fallback and emits the fallback trace tag.

### `data/memory/`

The memory directory is initialized as an empty development data root. Future
Memory Palace storage will write metadata containing `snapshot_8D`,
`omega_state`, `delta_vec`, `snapshot_origin`, and per-user metadata.

### `docs/runtime-bootstrap.md`

Runtime documentation explains how to run the development slice, what data
files are expected, and which parts are stubs.

### `scripts/`

Scripts may include a local smoke test that calls the development route. Scripts
must not become hidden sources of runtime behavior.

## Components

### Bootstrap Assets

Bootstrap assets establish repository structure and example data. They are
static data or documentation and do not own runtime decisions.

### Persona Loader

The persona loader reads YAML into `PersonaConfig` and validates required
sections. It checks mode, evolution thresholds, heartbeat sections, and
sub-state section keys. Missing required persona fields are configuration
errors and should fail fast during initialization or explicit load.

### Codebook Dictionary

The codebook dictionary reads CSV rows into node definitions. It does not run
VQ-VAE inference itself. It provides lookup support for a future DJL quantizer
and allows the development runtime to prove codebook semantic injection.

### Runtime Pipeline Skeleton

`MessagePipeline` or `OpenEdenRuntime` orchestrates one message turn. It does
not contain persona text and does not perform raw provider-specific work.

Responsibilities:

1. resolve session identity;
2. read session state;
3. run confidence-gated pre-tick;
4. compute derived dissonance;
5. quantize through the codebook boundary;
6. select memory retrieval mode once;
7. build the prompt;
8. call a development LLM stub;
9. validate output schema;
10. write vector and evolution index through serialized session state;
11. enqueue diary events when triggers fire.

### Development LLM Stub

The stub returns deterministic JSON matching the required schema:

```json
{
  "internal_logic": "Development stub response based on injected codebook state.",
  "vector_delta": {
    "L": 0.0,
    "P": 0.0,
    "E": 0.0,
    "S": 0.0,
    "tau": 0.0,
    "V": 0.0,
    "M": 0.0,
    "F": 0.0
  },
  "response": "..."
}
```

The stub exists only to exercise validation and write-back. It is not a
production provider.

### Output Validator

The validator enforces:

- top-level `internal_logic`, `vector_delta`, and `response`;
- exactly the vector keys `L`, `P`, `E`, `S`, `tau`, `V`, `M`, `F`;
- all unchanged dimensions represented as `0.0`;
- no `D` key in `vector_delta`;
- parseable numeric delta values.

Validator failure rejects the turn result and prevents vector write-back.

### Server Development Route

`POST /dev/message` is a local verification route. It accepts development input
such as platform, scope ID, user ID, text, and emotion confidence. It returns
the selected retrieval mode, codebook nodes, trace tags, prompt preview,
validated output, updated vector, evolution index, and diary outcome.

The route calls the runtime pipeline. It does not duplicate vector math,
persona logic, or prompt logic.

## Message Flow

1. `POST /dev/message` receives input and creates an `IncomingMessage`.
2. `SessionResolver` computes `sessionId = "$platform:$scopeId"`.
3. `SessionStateStore` reads current vector, Omega, ShockState,
   `evolutionIndex`, and homeostasis centroid.
4. `PreTickService` applies the confidence gate:
   - if `emotion_confidence < 0.5`, pre-tick is skipped;
   - otherwise perturbation is scaled by confidence and clamped to
     `MAX_PRETICK_DELTA = 0.25` per dimension.
5. The runtime computes `D = abs(L - tau) * (1 - E)` from the pre-ticked
   snapshot.
6. The pre-ticked vector and derived D pass through `CodebookQuantizer`.
7. `RetrievalModeSelector` selects exactly one retrieval mode. The mode is
   carried in `RetrievalResult`; the prompt builder does not re-evaluate it.
8. `PromptBuilder` injects codebook semantic state before user input and splits
   English hard constraints from Chinese persona/output sections.
9. `LlmClient` uses the development stub in this phase.
10. `OutputValidator` validates the JSON schema.
11. `SessionWriteService` writes inside the per-session mutex. It re-reads the
    latest persisted state inside the lock and applies `vector_delta` to this
    turn's pre-ticked snapshot.
12. The runtime increments `evolutionIndex` in the same serialized write path.
13. `DiaryQueue` enqueues narrative diary triggers through a bounded
    per-session queue with a maximum of eight pending entries.

## Heartbeat Interpretation

Heartbeat is not a health check. It is an internal proactive message source.
Base heartbeat uses `[HEARTBEAT_TRIGGER]`; shock heartbeat uses
`[HEARTBEAT_SHOCK_TRIGGER]`. Both markers must route through the same message
pipeline, codebook quantization, prompt builder, output validator, and vector
write-back as normal user turns. Heartbeat turns increment `evolutionIndex`.

This bootstrap phase stores the heartbeat prompt data and keeps the pipeline
compatible with heartbeat messages. Full random scheduling and platform routing
remain a later implementation step.

## Error Handling

- Missing required persona YAML fields fail load or initialization.
- Missing codebook data, low quantization confidence, or lookup failure degrades
  to heuristic fallback with `codebook=HEURISTIC_FALLBACK`.
- Missing Memory Palace implementation returns empty memories with a valid
  retrieval mode and injection label.
- Invalid LLM output returns a validator error and does not write vector state.
- Diary queue overflow drops the overflow event and emits
  `diary=QUEUE_OVERFLOW`; the main message flow continues.
- Shock back-detection below `emotion_confidence >= 0.65` is silently ignored.

## Testing Plan

Focused tests should cover:

1. persona loader reads thresholds and heartbeat sections from YAML;
2. persona loader rejects missing required sections;
3. codebook CSV dictionary parses node definitions;
4. heuristic fallback emits `codebook=HEURISTIC_FALLBACK`;
5. pre-tick skips below confidence `0.5`;
6. pre-tick scales by confidence and clamps each dimension to `0.25`;
7. prompt builder places codebook state before user input;
8. validator accepts the full 8-key delta schema;
9. validator rejects missing keys or any `D` delta;
10. vector write-back applies delta to the pre-ticked snapshot;
11. vector and evolution index update under the per-session mutex;
12. diary queue overflow emits the expected trace tag;
13. development route runs one complete message turn.

## Acceptance Criteria

The bootstrap is complete when:

1. repository data directories and example assets exist;
2. default persona data includes evolution thresholds and heartbeat sections;
3. codebook dictionary schema is documented and represented by example CSV;
4. development message pipeline can run one turn with in-memory/stub services;
5. the development route exposes the turn result for local verification;
6. tests verify the critical invariants listed above;
7. no Kotlin code hardcodes persona behavior;
8. runtime work remains behind suspend/non-blocking boundaries;
9. all prompt state passes through the codebook boundary or logged fallback.
