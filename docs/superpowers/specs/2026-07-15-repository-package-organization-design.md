# Repository Package Organization Design

## Purpose

Reorganize OpenEden's Kotlin sources by cohesive domain responsibility so that
unrelated entry points, transport types, runtime services, rendering code, and
persistence adapters no longer accumulate in the same package. The migration
changes physical paths, Kotlin package names, imports, tests, configured entry
points, and documentation references without changing runtime behavior or
public HTTP contracts.

This design applies across the repository, while deliberately leaving packages
that are already cohesive intact.

## Architectural Constraints

The reorganization preserves all repository invariants:

- Persona remains external data. No persona behavior moves into Kotlin logic.
- Coroutine, Flow, Ktor, inference-dispatcher, and per-session serialization
  behavior remain non-blocking and unchanged.
- The VQ-VAE codebook path and its logged heuristic fallback remain unchanged.
- Derived dissonance remains separate from the stored 8D vector.
- No API payload, persistence schema, CLI command, or terminal behavior changes.

## Package Strategy

Packages are organized by domain responsibility inside existing Gradle module
boundaries. Technical adapters remain separate from domain contracts. Tests
mirror the production package and directory of the code they verify.

The migration does not target equal package sizes. A cohesive package may hold
several related types; a new subpackage is introduced only when it establishes
a meaningful ownership boundary.

## CLI Packages

The root application module uses the following structure:

```text
io.openeden.cli
|- application     CLI orchestration and interactive control
|- command         interactive command models, parsing, and completion
|- input           CLI input contracts and JLine/stdin adapters
|- state           UI state, events, reducer, messages, and diagnostics
|- render          inline/full-screen/Markdown rendering and frame diffing
`- terminal        JLine sessions, encoding, providers, lifecycle, and capabilities

io.openeden.client  server API contract, HTTP implementation, and transport models
io.openeden.config  local CLI configuration and persistence
```

The application main class moves under `io.openeden.cli`; Gradle distribution
configuration is updated to use the new fully qualified entry point. Client and
configuration packages remain separate because they are adapters used by the
CLI application rather than terminal UI concerns.

## Core Runtime Packages

Existing cohesive domains such as `bio`, `codebook`, `memory`, `persona`,
`prompt`, `relationship`, and `trace` remain intact. The mixed `runtime` package
is divided as follows:

```text
io.openeden.runtime
|- pipeline        requests, results, turn source, and message/runtime pipelines
|- session         session state, stores, mutex registry, and turn gate
|- state           vector writes, homeostasis, runtime configuration, and invariants
|- affect          emotion signals, pre-tick, ShockState, and Omega
|- tick            background drift, tick configuration, and fluctuation strategies
|- heartbeat       scheduling, routing, delivery, decisions, and configuration
|- diary           tasks, triggers, queueing, workers, checkpoints, and generation
`- inference       inference execution contracts and platform implementations
```

Dependencies continue to point inward through existing interfaces. The package
move must not introduce server or CLI dependencies into `core`.

## Server Packages

The server module uses explicit bootstrap, transport, and persistence boundaries:

```text
io.openeden.server
|- bootstrap
|  |- main entry point
|  `- runtime dependency assembly and configuration
|- api
|  |- dto           HTTP and WebSocket transport models
|  |- route         health, chat, state, development, and WebSocket routes
|  `- plugin        serialization and status-page installation
`- persistence
   `- sqldelight    session, memory, diary, relationship, and trace adapters
```

SQLDelight-generated types remain where the build generates them; handwritten
adapters move to `persistence.sqldelight`. HTTP DTOs do not enter core runtime
packages, and persistence implementations do not enter route packages.

## Trainer Packages

The trainer's current types form one small, cohesive codebook-training unit and
remain in `io.openeden.trainer`. It will not be split into single-file packages.
Future subpackages should be added only when independent ingestion, training
backend, or evaluation responsibilities emerge.

## File Responsibility

Reusable or public top-level classes, interfaces, objects, and enums receive
focused files. Files such as the current heartbeat implementation, which hold
multiple independently reusable public types, are split along the package
boundaries above. Small private or local helpers may remain with their sole
consumer when extracting them would reduce readability.

## AGENTS.md Rule

`AGENTS.md` gains a mandatory package and directory organization section with
these requirements:

- Organize production code by cohesive domain responsibility within its Gradle
  module; do not mix API DTOs, routes, persistence adapters, runtime services,
  terminal infrastructure, and rendering types in one package.
- Keep module root packages limited to deliberate entry points or genuinely
  module-wide types.
- Mirror production package paths in test source sets.
- Place reusable/public top-level types in focused files.
- Do not create subpackages merely to equalize file counts or isolate a single
  type without a real ownership boundary.
- Any new package must have a describable responsibility and dependency
  direction.

These rules complement the existing one-primary-type-per-file guidance and are
enforced for future generated code.

## Migration Sequence

1. Add the organization rule to `AGENTS.md`.
2. Reorganize core runtime packages and their tests, updating imports in all
   dependent modules.
3. Reorganize CLI application, command, input, state, render, and terminal
   packages and tests; update the Gradle application entry point.
4. Reorganize server bootstrap, API, and SQLDelight adapter packages and tests.
5. Split multi-public-type files where the new ownership boundaries require it.
6. Update README module and CLI startup documentation plus source-path
   references that are intended to remain current.
7. Remove obsolete empty directories and verify that no old package imports or
   entry-point references remain.

Existing uncommitted user changes are preserved byte-for-byte in substance when
their files move. Unrelated generated or cache files are not included.

## Verification

The migration is complete only when all of the following succeed:

```powershell
.\gradlew.bat test :core:jvmTest :server:test installDist -Pkotlin.incremental=false --no-daemon --console=plain
```

Additional verification includes:

- run the packaged CLI entry point from `build/install/openeden/bin`;
- run `WindowsTerminalInputE2ETest` to protect Unicode editing behavior;
- search for obsolete package declarations, imports, and the previous main
  class;
- confirm tests mirror production packages;
- inspect `git diff` to ensure package movement did not alter Persona-as-Data,
  vector, VQ-VAE, concurrency, protocol, or persistence behavior.

macOS-specific terminal behavior is not changed by this refactor and remains a
residual platform verification gap unless a macOS runner is available.
