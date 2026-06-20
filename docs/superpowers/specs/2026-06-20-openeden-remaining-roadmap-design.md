# OpenEden Remaining Implementation Roadmap

Date: 2026-06-20
Scope: remaining project implementation after runtime tick and inference boundary.

## Context

OpenEden currently has a Kotlin/Ktor runtime skeleton with:

- persona-as-data loading from `persona/*.yaml`;
- 8D vector, derived dissonance, Omega, ShockState, and vector write invariants;
- heuristic codebook fallback with trace tagging;
- prompt construction and output validation;
- durable SQLDelight session state;
- owner-only heartbeat scheduling;
- sine-wave runtime tick, ShockState decay, and Omega accumulation;
- a development route for local runtime verification.

The remaining work is not one feature. It is a sequence of product surfaces and
runtime integrations. The implementation order is:

1. Local usable runtime.
2. Core runtime completion.
3. Web UI observability and debugging.
4. QQ/OneBot platform adapter.

This order deliberately keeps the first production surface local and
controllable, then hardens the internal biological/memory/model pipeline before
exposing it to third-party chat traffic.

## Guiding Constraints

All stages must preserve `AGENTS.md` invariants:

- personality remains data, not Kotlin logic;
- all model, embedding, VQ-VAE, mapping, symmetry, and runtime math stays behind
  `InferenceExecutor`;
- D is derived before prompt construction and never stored;
- vector writes, evolution index updates, and tick writes use per-session
  serialization;
- heartbeat output is delivered only to the configured owner target;
- fallback codebook output logs `codebook=HEURISTIC_FALLBACK`;
- CLI, Web UI, and QQ adapters call the same runtime pipeline instead of
  duplicating prompt, vector, memory, or persona logic.

## Stage 1: Local Usable Runtime

### Goal

Make OpenEden usable from a local CLI with a real LLM provider while retaining
the current heuristic codebook and empty Memory Palace fallback. This gives a
fast feedback loop for prompt, output validation, state mutation, heartbeat,
and persistence before adding heavier model or platform integrations.

### Deliverables

- A production-facing runtime request/response contract that replaces the
  development-only naming at the adapter boundary.
- Local CLI entry point for one-on-one sessions.
- Real LLM provider adapter behind `LlmClient`.
- Environment/config loading for provider credentials, model name, runtime DB
  path, owner target, and persona file.
- Streaming or incremental output may be added only if it does not complicate
  vector write-back ordering; non-streaming is acceptable for the first slice.
- CLI commands for:
  - sending a message;
  - printing response text;
  - showing trace tags and current state summary when a debug flag is enabled;
  - selecting or creating a local session.
- Clear local run documentation.

### Non-Goals

- No QQ/OneBot traffic.
- No Web UI.
- No trained VQ-VAE requirement.
- No full Memory Palace persistence.
- No multi-user relationship modeling.

### Architecture Notes

The CLI is a first-party surface. It should map local input into the same
runtime request type used later by Web UI and QQ. For local one-on-one use,
`sessionId = "CLI:<userId>"`, where `userId` defaults to the configured local
owner or a stable local profile ID.

The LLM adapter should be narrow:

```text
BuiltPrompt -> provider request -> raw model output -> LlmOutputValidator
```

Provider-specific retries, timeouts, and response parsing stay inside the
adapter. The core runtime should not know provider names or HTTP details.

### Acceptance Criteria

- A local user can run the CLI, send messages, and receive validated responses.
- Session vector, Omega, ShockState, and evolution index persist across process
  restarts.
- Invalid LLM output does not mutate vector state.
- Heartbeats still route through the runtime and write state, but output is
  dropped unless owner delivery is configured.
- Server tests and new CLI/provider tests pass.

## Stage 2: Core Runtime Completion

### Goal

Replace bootstrap fallbacks with the intended internal systems: Memory Palace,
DJL-backed VQ-VAE/codebook quantization, dynamic homeostasis centroid, and
momentum-aware retrieval. This stage makes the biological runtime meaningful
before it is exposed to continuous platform traffic.

### Deliverables

- DJL inference integration behind `CodebookQuantizer`.
- Codebook CSV dictionary loading for real node definitions.
- Explicit low-confidence and inference-failure fallback path to heuristic
  codebook output.
- Memory Palace storage with:
  - raw high-fidelity entries;
  - narrative diary entries;
  - semantic embeddings;
  - emotional embeddings;
  - `snapshot_8D`;
  - `omega_state`;
  - `delta_vec`;
  - `snapshot_origin`;
  - `user_id` metadata.
- Retrieval implementation for the three AGENTS modes:
  - CONGRUENT;
  - MIXED;
  - CONTRAST.
- Momentum weighting in retrieval using prior `delta_vec` impact.
- Dynamic homeostasis centroid provider using stable/daily memory windows.
- Diary write queue backed by durable storage or a restart-safe queue policy.
- Trace IDs across quantization, retrieval, centroid updates, vector writes,
  ShockState transitions, diary writes, and fallback paths.

### Non-Goals

- No Web UI polish.
- No QQ adapter yet.
- No Telegram implementation.
- No personality logic in runtime code.

### Architecture Notes

This stage should keep storage replaceable. A simple SQLite-backed memory store
is acceptable if the repository is still single-node local-first. If vector
search quality becomes a bottleneck, add a vector index behind a `MemoryStore`
or `EmbeddingIndex` interface rather than coupling retrieval to a specific DB.

Dynamic centroid computation must not run on request dispatchers. It should run
through `InferenceExecutor` and write centroid updates through serialized
session state paths.

The VQ-VAE path should have a clear confidence result:

```text
BioVector + D -> DJL model -> top-k nodes + confidence -> CSV definitions
```

If any part fails or confidence is too low, the runtime emits the deterministic
heuristic fallback and trace tag.

### Acceptance Criteria

- Quantization uses DJL when configured and deterministic fallback when not.
- Prompt builder receives semantic node definitions, not raw vector
  personality instructions.
- Memories are written with required metadata.
- Retrieval mode is selected once in runtime and carried into prompt building.
- CONTRAST retrieval uses center-symmetric emotional mapping.
- Dynamic centroid changes over stable memory history.
- All new model, mapping, retrieval, and centroid work runs through
  `InferenceExecutor`.
- Regression tests cover fallback, metadata shape, retrieval modes, centroid
  updates, and queue overflow.

## Stage 3: Web UI Observability And Debugging

### Goal

Build a local Web UI for operating and inspecting the runtime. The Web UI is not
the main chat product first; it is a control surface for validating the runtime
before QQ traffic makes failures harder to inspect.

### Deliverables

- Web chat surface using the shared runtime pipeline.
- Session selector for CLI/Web/local sessions.
- Runtime state panels:
  - 8D vector;
  - derived D;
  - Omega;
  - ShockState;
  - evolution index;
  - selected retrieval mode;
  - active codebook nodes;
  - heartbeat status.
- Trace viewer for one turn.
- Prompt preview with system/persona/user layers separated.
- Memory inspection view for retrieved snippets and stored entries.
- Runtime tick/heartbeat visibility without manual DB inspection.
- Optional operator controls gated as local-only:
  - inject ShockState;
  - trigger heartbeat evaluation;
  - run one runtime tick;
  - reset a local test session.

### Non-Goals

- No public multi-tenant deployment.
- No account system unless needed for local owner/operator separation.
- No decorative landing page.
- No Telegram or QQ-specific UI.

### Architecture Notes

The Web UI should call server APIs that expose runtime observations, not reach
into storage directly. Debug endpoints should return structured state snapshots
with sensitive fields omitted by default.

The UI should keep operational density high: status panels, trace tables, and
compact controls are more useful than marketing-style presentation.

### Acceptance Criteria

- A local operator can send a message and inspect the exact runtime state
  changes caused by that turn.
- Prompt preview shows codebook state before user input.
- Memory retrieval and trace tags are visible.
- Runtime tick and heartbeat behavior can be observed without reading logs.
- UI tests or browser smoke tests verify core flows and responsive layout.

## Stage 4: QQ/OneBot Platform Adapter

### Goal

Connect OpenEden to QQ through OneBot v11 WebSocket only, using the same runtime
pipeline proven by CLI and Web UI.

### Deliverables

- OneBot v11 WebSocket client module.
- Event parser for private and group messages.
- Session identity resolver:
  - group: `sessionId = "QQ:<group_id>"`;
  - private: `sessionId = "QQ:<user_id>"`.
- Per-user metadata capture for memory entries and traces.
- Outbound message sender with reconnect handling.
- Owner-only heartbeat delivery to configured QQ owner target.
- Platform disconnect behavior that drops stale heartbeat outputs instead of
  queueing them for replay.
- Message filtering to ignore self messages, unsupported event types, and
  malformed payloads.
- Rate-limit and retry policy for QQ sends.
- Adapter-level observability in the Web UI or logs.

### Non-Goals

- No Telegram adapter in this stage.
- No platform-specific personality behavior.
- No duplicate QQ-only runtime state.
- No group-specific fork of ATRI state beyond the configured shared session
  identity rules.

### Architecture Notes

The adapter should translate platform events into the runtime request type and
translate validated runtime responses back into OneBot send actions. It must not
build prompts, modify vector math, choose retrieval modes, or interpret persona.

Disconnected delivery behavior matters:

- user messages cannot be processed while the inbound socket is down;
- heartbeat output generated while outbound delivery is unavailable is dropped
  after state write-back;
- no stale heartbeat output is replayed after reconnect.

### Acceptance Criteria

- QQ private and group messages route through the shared runtime pipeline.
- Group sessions share one ATRI state per group.
- Memory metadata records the individual sender user ID.
- Heartbeat responses are delivered only to the configured owner QQ target.
- Reconnect does not replay stale proactive messages.
- Adapter tests cover event parsing, session resolution, ignored events,
  owner-only delivery, and outbound error handling.

## Cross-Stage Dependency Map

```text
Stage 1 Local Usable Runtime
  -> validates real LLM, persistence, local session behavior

Stage 2 Core Runtime Completion
  -> replaces heuristic/empty internals with VQ-VAE + Memory Palace

Stage 3 Web UI Observability
  -> exposes and validates runtime internals before external traffic

Stage 4 QQ/OneBot Adapter
  -> exposes the stable runtime to third-party platform traffic
```

Do not invert Stage 2 and Stage 4. QQ traffic before Memory Palace and
quantization are coherent would make runtime failures harder to diagnose and
would encourage platform-specific shortcuts.

## Suggested Implementation Specs After This Roadmap

Each stage should get its own design spec and implementation plan:

1. `local-cli-llm-runtime-design.md`
2. `memory-vqvae-core-runtime-design.md`
3. `webui-runtime-observability-design.md`
4. `onebot-qq-adapter-design.md`

Each spec should end with a concrete plan before implementation begins. The
roadmap itself is not an implementation plan.

## Global Verification Gate

Before any stage is considered complete:

- run the relevant Gradle test task;
- run `.\gradlew.bat :server:test` unless the stage is completely outside the
  server;
- scan for forbidden placeholders and stale runtime names;
- manually check AGENTS compliance:
  - persona-as-data;
  - non-blocking runtime work;
  - VQ-VAE/codebook boundary;
  - D derived only;
  - per-session serialized writes;
  - owner-only heartbeat delivery.

