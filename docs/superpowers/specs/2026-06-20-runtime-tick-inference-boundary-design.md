# Runtime Tick and Inference Boundary Design

## Purpose

The current runtime bootstrap can process development messages, persist session state, run owner-only heartbeats, and apply sine-wave background drift. The next implementation phase hardens the runtime core before adding local CLI, Web UI, QQ/OneBot, real LLM calls, or Memory Palace storage.

The goal is a deterministic, non-blocking runtime tick and inference boundary that protects AGENTS.md invariants:

- persona remains data;
- VQ-VAE/codebook access remains a required boundary with heuristic fallback;
- vector math, dual-space mapping, ShockState decay, and retrieval-mode selection do not run on request dispatchers;
- Ω is monotonic and updated from sustained S/D/F pressure;
- background drift does not increment `evolution_index`;
- heartbeat turns do increment `evolution_index` but deliver only to owner.

## Scope

This phase covers runtime internals only.

In scope:

- `InferenceExecutor` abstraction for runtime math and future DJL work.
- Unified `RuntimeTickScheduler` replacing the narrow background drift scheduler.
- Tick-time sine drift, ShockState decay, Ω accumulation, and trace tags.
- `RuntimeConfig` for owner target and tick parameters currently scattered across server wiring.
- A bootstrap `HomeostasisCentroidProvider` interface that returns persisted origin until Memory Palace exists.

Out of scope:

- Local CLI implementation.
- Web UI implementation.
- QQ/OneBot adapter implementation.
- Telegram or other third-party platforms.
- Real DJL VQ-VAE model loading.
- Real Memory Palace storage and RAG retrieval.

## Architecture

### InferenceExecutor

Add a small runtime abstraction:

```kotlin
interface InferenceExecutor {
    suspend fun <T> run(block: suspend () -> T): T
}
```

Production JVM wiring will run this on a dedicated dispatcher. Tests can use a direct executor. This keeps common runtime code independent of JVM dispatchers while establishing a hard boundary for all operations AGENTS.md §12.2 classifies as inference/runtime math.

The following work must go through this boundary:

- codebook quantization and heuristic fallback;
- derived D calculation before prompt construction;
- `VectorMapping.toInternal`;
- retrieval mode selection;
- center-symmetric mapping once retrieval uses it;
- sine background fluctuation;
- ShockState decay;
- Ω accumulation;
- future DJL embedding/VQ-VAE calls.

### RuntimeTickScheduler

Replace `BackgroundDriftScheduler` with a unified tick:

1. Enumerate known sessions.
2. For each session, compute elapsed time since last tick.
3. Run tick math through `InferenceExecutor`.
4. Apply sine fluctuation delta.
5. Decay ShockState by elapsed time.
6. Accumulate Ω from sustained S, derived D, and F/S co-occurrence.
7. Persist state through `VectorWriteService` under the per-session mutex.
8. Emit trace tags for drift, shock decay, and omega update.

Tick must not call the LLM and must not increment `evolution_index`. It is physiological time, not a dialogue turn.

### Ω Accumulation

Add deterministic monotonic accumulation:

- high S contributes baseline wear;
- high derived D contributes cognitive wear;
- high F with high S applies a multiplier;
- low values add zero or near-zero wear;
- Ω never decreases.

The exact coefficients should live in `RuntimeConfig`, not hardcoded in call sites. Defaults can be conservative for bootstrap.

### ShockState Lifecycle

Tick decay owns passive ShockState lifecycle changes:

- decay intensity with the existing exponential formula;
- mark inactive below 0.05;
- keep free-text description;
- do not categorize source with enums;
- reset `shockHeartbeatFired` only on a new activation, not on passive decay.

### HomeostasisCentroidProvider

Introduce:

```kotlin
interface HomeostasisCentroidProvider {
    suspend fun centroidFor(sessionId: String): BioVector
}
```

Bootstrap implementation returns `SessionState.origin`. Later Memory Palace work can replace it with sliding-window centroid averaging over memories tagged `daily/stable`.

## Data Flow

User/heartbeat message flow:

1. Surface or adapter builds `DevelopmentMessageRequest` or future production request.
2. Pipeline reads session.
3. Runtime math sections run through `InferenceExecutor`.
4. Prompt builder receives derived D and codebook semantic text.
5. LLM output is validated.
6. Vector write-back applies delta to the pre-ticked snapshot under mutex.
7. Shock back-detection writes ShockState/Ω under the same mutex.

Runtime tick flow:

1. Scheduler wakes at configured interval.
2. Session IDs are read.
3. Tick math runs through `InferenceExecutor`.
4. State is persisted under `VectorWriteService`.
5. No prompt, no LLM, no heartbeat delivery, no `evolution_index` increment.

## Error Handling

- If inference executor work fails during message handling, codebook fallback must still be available where applicable.
- If tick math fails for one session, log trace context and continue other sessions.
- If owner target is missing for heartbeat delivery, drop output after state write-back.
- If runtime config is invalid, fail startup with a clear error instead of silently using unsafe defaults.

## Testing

Add focused tests:

- pipeline invokes inference executor for quantization/mapping/retrieval selection boundaries;
- tick applies sine drift and does not increment `evolution_index`;
- tick decays active ShockState below threshold to inactive;
- Ω accumulation is monotonic and responds to high S/D/F;
- low S/D/F does not reduce Ω;
- centroid provider default returns persisted origin;
- heartbeat still increments `evolution_index` and still owner-only delivers after scheduler refactor;
- all tick writes use `VectorWriteService` and preserve per-session mutex semantics.

## Implementation Order

1. Add `InferenceExecutor` and direct test executor.
2. Thread executor through `DevelopmentMessagePipeline`.
3. Add `RuntimeConfig` for tick/omega/owner defaults.
4. Replace `BackgroundDriftScheduler` with `RuntimeTickScheduler`.
5. Add Ω accumulation engine.
6. Add ShockState passive decay in tick.
7. Add `HomeostasisCentroidProvider`.
8. Update server runtime wiring.
9. Run `.\gradlew.bat :server:test`.

## Acceptance Criteria

- Runtime tests prove tick and message paths preserve `evolution_index` rules.
- No new persona text appears in Kotlin logic.
- No stored vector includes D.
- All runtime math introduced in this phase is reachable through `InferenceExecutor`.
- Heartbeat delivery remains owner-only.
- `.\gradlew.bat :server:test` passes.
