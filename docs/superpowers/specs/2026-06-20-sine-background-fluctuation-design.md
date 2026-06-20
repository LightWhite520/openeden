# Sine Background Fluctuation Design

## Purpose

OpenEden needs non-user-driven inner-state fluctuation for background drift. This is distinct from heartbeat scheduling: heartbeat intervals still use a cryptographically seeded source, while inner-state fluctuation should feel continuous rather than jumpy.

## Design

Add a small runtime component, `SineWaveFluctuationEngine`, that computes a bounded `VectorDelta` from elapsed time. Each vector dimension is driven by a sum of sine waves:

- per-dimension amplitude caps keep every output within `MAX_PRETICK_DELTA`;
- per-dimension phase, frequency, and secondary frequency are supplied by immutable parameters;
- a JVM factory can create those parameters from `SecureRandom`;
- the common engine is deterministic for tests when parameters are supplied.

The component returns only a delta. It does not persist state, does not encode persona text, and does not bypass the normal vector write path. A later background drift task can run it on the inference dispatcher and apply its delta under `VectorWriteService`.

## Constraints

- Do not replace heartbeat interval randomness with sine waves.
- Do not store derived `D`.
- Do not hardcode persona behavior into fluctuation math.
- Keep output bounded and testable.
