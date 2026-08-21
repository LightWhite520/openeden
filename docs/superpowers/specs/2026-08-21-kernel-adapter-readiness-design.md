# Kernel Adapter Readiness Design

## Goal

Close the remaining runtime-kernel gaps that would otherwise leak into the future OneBot adapter. This work does not add QQ or OneBot behavior.

## Scope

The change has three bounded parts:

1. Replace the JVM inference executor's shared `Dispatchers.Default` default with an owned, named, fixed-size dispatcher. The executor remains injectable for tests, closes only dispatchers it owns, and is closed during server startup failure and normal shutdown.
2. Make heartbeat delivery availability explicit. Heartbeat generation and state write-back happen before delivery; a missing owner, disconnected target, or send failure drops the outward message without queuing or aborting the scheduler loop. Until OneBot exists, the production server uses the no-op disconnected delivery.
3. Add an artifact-backed kernel smoke test that loads `persona/atri.yaml` and `data/models/local-model-artifact.json`, runs the real local VQ-VAE/codebook and embedding implementations through the shared pipeline, validates grounded output, and verifies state persistence.

## Invariants

- Persona remains data-only in `persona/*.yaml`; Kotlin gains no personality classification or emotional behavior.
- VQ-VAE remains the primary continuous-to-discrete path. The smoke test requires a real codebook node and does not substitute heuristic personality logic.
- Inference, vector mapping, retrieval math, and model execution remain behind `InferenceExecutor`.
- Heartbeat turns still increment `evolution_index` and write state even when outward delivery is dropped.
- No heartbeat output is queued for reconnect replay.
- Cancellation is propagated; ordinary delivery failures are isolated per target.

## Testing

- JVM executor tests prove work runs on a named non-Default dispatcher and that owned resources close.
- Heartbeat tests prove disconnected and failing delivery paths preserve state evolution and do not call or abort subsequent delivery work.
- The artifact-backed smoke test proves Persona YAML -> VQ-VAE/codebook -> Prompt -> validator -> state write-back.
- The root Gradle test task is the final regression gate.
