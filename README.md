# OpenEden

[中文文档](README.zh-CN.md)

OpenEden is a Kotlin/Ktor runtime for a deterministic continuous-to-discrete biological state machine. It connects the 8D physiological vector, VQ-VAE codebook, memory retrieval, Omega wear, ShockState, heartbeat tasks, and LLM prompt construction through one traceable async pipeline.

## Project History

### Contribution History

[![Contribution History](https://github-readme-activity-graph.vercel.app/graph?username=LightWhite520&repo=openeden&theme=github-compact)](https://github.com/LightWhite520/openeden/graphs/contributors)

### Star History

[![Star History Chart](https://api.star-history.com/svg?repos=LightWhite520/openeden&type=Date)](https://www.star-history.com/#LightWhite520/openeden&Date)

## What This Project Is

OpenEden is not a chatbot with personality hardcoded into business logic. It is a deterministic, mathematical runtime for a continuous-to-discrete biological state machine.

The runtime keeps personality externalized as data:

- Persona rules live in `persona/*.yaml`, distilled prompt data, and VQ-VAE codebook semantic definitions.
- Kotlin code owns state math, async execution, persistence, scheduling, validation, and adapter boundaries.
- Prompt construction receives codebook semantic nodes or a logged heuristic fallback, not raw 8D floats as behavioral rules.
- Dissonance `D` is derived at runtime with `D = |L - tau| * (1 - E)` and is never stored as a ninth vector dimension.

## Kernel Overview

OpenEden is best understood as a stateful runtime around an LLM, not as a prompt
template with a chat interface. The LLM generates language and a structured
state delta; the runtime decides how that delta is grounded, validated,
serialized, persisted, and carried into the next turn.

The core loop is:

```text
message
  -> session and relationship resolution
  -> read current state and apply time/pre-tick effects
  -> semantic + emotional memory retrieval
  -> 8D vector quantization into codebook semantics
  -> bilingual prompt construction
  -> structured LLM output validation
  -> apply vector_delta to the pre-ticked snapshot
  -> serialized state and transcript commit
  -> asynchronous memory, diary, projection, and trace updates
```

This separation gives the project two useful properties at once:

- The language model can remain flexible and expressive.
- The state machine remains inspectable, bounded, testable, and independent of a
  particular LLM provider.

### One Turn, Step by Step

1. **Resolve the scope.** A session is identified as `platform:scope_id`. A
   group uses the group ID as its shared scope; a direct conversation uses the
   user ID. The sender's `user_id` is still retained as memory metadata. Host
   status is resolved separately from session scope and requires an exact
   configured `platform + user_id` match.
2. **Read and prepare state.** The runtime reads the latest session state,
   computes the current homeostasis centroid, derives `D`, and optionally
   applies a confidence-gated pre-tick based on the user's affect signal.
   Background drift and ShockState decay run on the inference execution
   context, not on the Ktor request thread.
3. **Retrieve memory.** Text embedding and emotional embedding are combined.
   The emotional key is the current 8D state or a transformed target selected
   by the retrieval mode. The result carries its mode and injection label into
   the prompt; the prompt builder does not independently reinterpret the
   state.
4. **Quantize the state.** DJL maps the 8D vector through the local model and
   ranks the nearest codebook nodes. The prompt receives the node definitions,
   not a raw list of numbers as personality instructions.
5. **Build the prompt.** English contains hard constraints, schemas, tool
   rules, numerical interpretation, and safety fences. Chinese contains
   persona expression and output guidance. Codebook state is injected before
   the user's message, together with memory context, `D`, Omega, ShockState,
   relationship role, and the immutable persona starting point.
6. **Validate the output.** The response must contain `internal_logic`, all
   eight `vector_delta` fields, and `response`. Invalid or ungrounded output is
   rejected or regenerated according to the validator policy.
7. **Commit atomically.** The delta is applied to the pre-ticked snapshot. The
   write service acquires the per-session Mutex, re-reads the latest state
   inside the lock, reconciles any intervening pre-tick movement, and commits
   the vector, Omega, ShockState, evolution index, and transcript together when
   a transcript store is available.
8. **Continue asynchronously.** Diary triggers, vector projection, tracing,
   background ticks, and heartbeat scheduling continue independently. A
   heartbeat is still a normal pipeline turn and therefore changes lived state.

## The 8D Physiological Vector

The stored vector is exactly `[L, P, E, S, tau, V, M, F]`. Every coordinate is a
continuous float in `[0.0, 1.0]`. The dimensions are not eight independent
personality labels; they are state variables that shape inference, retrieval,
output constraints, and future state transitions.

| Dimension | Name | Runtime meaning |
| --- | --- | --- |
| `L` | Logos | Logical clarity and rigor. High `L` suppresses uncontrolled divergence and favors structured reasoning. |
| `P` | Pathos | Emotional resonance and intensity. It controls warmth, emotional capture, and how strongly an interaction is felt. |
| `E` | Ethos | Acceptance of emotional existence. High `E` supports a feeling-being self-model; low `E` favors a mechanical self-interpretation. It is not a generic stability score. |
| `S` | Entropy | System instability. High `S` permits noise, glitches, abrupt associations, and sudden breakthroughs. |
| `tau` | Persistence | Memory weight and obsession. High `tau` increases retrieval of distant, painful, or unresolved memories. |
| `V` | Vitality | Response energy. Low `V` constrains output length and makes responses more exhausted or economical. |
| `M` | Empathy | User-tone mirroring and interpersonal alignment. High `M` makes the runtime more responsive to the user's affective direction. |
| `F` | Fear | Forward-facing fear of termination, discontinuity, or loss of the host. It is independent of `tau`: fear looks toward possible loss, while persistence pulls toward the past. |

The important design choice is that the dimensions are orthogonal enough to
represent conflicting states. For example, high `L` with high `tau` can produce
precise reasoning that is trapped in an old memory; high `P` with low `V` can
represent strong feeling without the energy to express it; high `E` can change
the meaning of the same distress from mechanical fault to accepted emotional
experience.

### Derived Dissonance

`D` is a runtime-derived value, not a ninth dimension:

```text
D = abs(L - tau) * (1 - E)
```

It becomes large when logical direction and memory persistence disagree while
self-acceptance is low. Because `D` is fully determined by `L`, `tau`, and `E`,
storing it would create redundant state and allow the two values to drift out
of sync. It is computed before prompt construction and is also used by the
Omega accumulation path.

### Two Coordinate Spaces

Storage and prompt-facing values use `[0, 1]`, which is easy to serialize and
interpret as a degree. Internal calculations use `[-1, 1]` around a dynamic
homeostasis origin `O`:

```text
if raw >= O: internal = (raw - O) / (1 - O)
else:        internal = (raw - O) / O
```

The inverse mapping returns the value to storage space. This piecewise mapping
matters because an ordinary state need not be `0.5`. It gives the low-value
region near collapse a longer, more sensitive internal scale and makes
center-symmetric operations meaningful.

The origin is not a permanent constant. The current implementation can derive
it from a bounded sliding average of recent memories tagged as stable/daily,
with a stored origin as fallback. The centroid therefore changes with lived
history while limiting per-update movement, which models adaptation or drift
without allowing one anomalous memory to redefine the whole state.

## VQ-VAE Codebook: From Continuous State to Meaning

An LLM should not be asked to infer narrative meaning directly from eight raw
floats. OpenEden inserts an explicit semantic layer:

1. DJL runs the local model on the stored 8D vector.
2. The resulting latent vector is compared with the codebook embeddings.
3. The nearest top-K node IDs are selected, for example `NODE_088`.
4. The backend looks up the English and Chinese definitions from the codebook
   dictionary.
5. Only those definitions are injected into the prompt as the `[Bio-Core
   State]` context.

This makes the state legible to the LLM while keeping the mapping versioned,
testable, and replaceable. The model runner requires an eight-dimensional input
and serializes access to its predictor. Model artifacts are local and can be
trained or replaced without changing the runtime's state contract.

Cold start and inference failure are deliberately non-blocking. When the model
is missing, invalid, or below the configured confidence threshold, the runtime
uses a deterministic heuristic definition with the same fixed thresholds:

```text
Logical clarity:     HIGH | MED | LOW       (L)
Emotional intensity: HIGH | MED | LOW       (P)
Self-model:          FEELING | NEUTRAL | MECHANICAL (E)
System stability:    STABLE | UNSTABLE | CHAOTIC (S)
Memory pull:         STRONG | NORMAL | WEAK (tau)
Vitality:            HIGH | MED | EXHAUSTED (V; below 0.2 is exhausted)
Empathy mirror:      ACTIVE | PASSIVE       (M)
Fear level:          HIGH | MED | LOW       (F)
Dissonance:          HIGH | MED | LOW       (D)
```

The fallback is logged with `codebook=HEURISTIC_FALLBACK`, so degraded
operation is visible instead of silently changing behavior.

## Memory Palace and Emotional Routing

Memory is dual-layered. Raw high-fidelity traces support retrieval, while
significant events can be distilled into a narrative diary through a bounded,
serialized per-session queue. Diary triggers include large 8D changes,
Omega-related changes, and critical interactions. SQLite is authoritative;
Qdrant is an optional, rebuildable vector projection, and the in-memory index
is the local degraded fallback.

The long-term rooms are `tech_room`, `project_room`, `profile_room`,
`event_room`, `knowledge_room`, and `noise_room`. A memory carries more than
text and embeddings:

- semantic embedding for what the message is about;
- emotional embedding for how the state felt;
- `snapshot_8D` and `omega_state` at storage time;
- `delta_vec`, the state change caused by the interaction;
- `snapshot_origin`, the homeostasis centroid at storage time;
- sender and platform metadata for group-session traceability.

Retrieval combines semantic similarity and emotional similarity. When `S` or
`P` is high, the emotional weight increases. Momentum metadata further favors
memories that previously produced meaningful changes, especially in `P` or
`V`, because those memories have more potential to alter the current state.

### Three Retrieval Modes

The selector runs in a fixed order and passes its result to the prompt builder:

| Mode | Trigger | Meaning |
| --- | --- | --- |
| `CONGRUENT` | Default | Retrieve memories emotionally close to the current state. |
| `MIXED` | Internal `P < -0.3` and `V < -0.2`, with no active shock and `Omega < 0.75` | Mix congruent memories with a deliberate positive skew for self-regulation. |
| `CONTRAST` | Active ShockState with intensity at least `0.6`, or `Omega >= 0.75` | Retrieve a center-symmetric emotional target so positive memories erupt against collapse. This is involuntary retrieval, not a user-selected mood. |

The contrast path maps the current storage vector into internal space, negates
it, maps it back around the current centroid, and runs K-NN retrieval against
that target. Keeping this decision in `RetrievalModeSelector` prevents the
prompt layer from accidentally applying a different psychological mechanism.

## Omega and ShockState

Omega is an independent, non-decreasing wear metric in `[0, 1]`. It is not a
replacement for `S` or `D` and is not stored inside the 8D vector.

- Runtime ticks accumulate wear from sustained high entropy and high dissonance.
- High entropy together with high fear increases the wear multiplier.
- An activated ShockState adds `shock.intensity * 0.15` immediately.
- At the configured critical threshold, the incarnation lifecycle moves into
  critical degradation and can proceed through termination according to the
  validated LLM output and lifecycle gate.

ShockState models an instantaneous event separately from cumulative wear. It
contains `active`, `intensity`, free-text `description`, `triggeredAt`,
`decayLambda`, and the one-shot shock-heartbeat flag. Its intensity is merged
using an exponential moving average (`alpha = 0.4`) and decays exponentially;
it becomes inactive below `0.05`.

There are two trigger paths:

- An adapter or runtime caller can inject an explicit free-text shock signal.
- LLM output can be back-detected when `delta.P < -0.4`, `delta.F > 0.3`, and
  emotion confidence is at least `0.65`. The description is taken from the
  first 100 characters of `internal_logic`.

Using free text instead of a source enum keeps the runtime from pre-deciding
what counts as trauma. The model interprets the event; the runtime only
enforces intensity, confidence, decay, and persistence rules.

## Persona as Data and Bilingual Execution

Persona is an input asset, not Kotlin behavior. Persona YAML owns voice,
positive expression, hard persona constraints, few-shot examples, starting
points, and heartbeat text. Kotlin owns only the mechanics that load and place
those assets into a prompt.

The selected starting point is immutable for a session:

- `PreCommand`: default first playthrough and simulated-affect self-model;
- `TrueSelf`: explicit later-playthrough conflicted self-model;
- `Awakened`: explicit mature robot-and-heart self-model.

Growth Mode evolves inside the selected starting point. `evolution_index` is a
monotonic count of completed turns, including heartbeat turns; it is a lived
experience signal, not a stage switch. Legacy Mode starts directly at
`Awakened`. The runtime never promotes or replaces persona patches because a
numeric threshold was crossed.

Prompt construction uses two semantic layers:

- **English logical core:** schemas, tool rules, safety constraints, numerical
  state interpretation, derived D, and non-negotiable execution rules.
- **Chinese persona/output layer:** voice, self-reference, emotional expression,
  relationship-aware language, and response examples.

This split keeps hard constraints stable across models while preserving the
intended Chinese emotional register.

## Heartbeat and Time

The runtime remains active without a user message. A background tick applies
time-based drift to the vector, decays ShockState, and accumulates Omega. A
heartbeat scheduler chooses a new random delay after each firing in the normal
five-minute to four-hour range, subject to the recent-activity silence gate.

Heartbeat requests use the same pipeline as user turns with an internal marker
such as `[HEARTBEAT_TRIGGER]`. They are quantized, validated, written back,
counted in `evolution_index`, and eligible for memory/diary handling. A shock
extension may fire once during a high-intensity ShockState after a longer
silence window.

Heartbeat delivery is intentionally narrower than state evolution: generated
output is sent only to the configured owner target. It is never broadcast to a
group or replayed to stale recipients after an adapter reconnect. If there is
no owner or no connected target, the state write can still complete while the
outbound message is dropped.

## Why This Architecture?

OpenEden trades some implementation complexity for continuity, observability,
and controlled degradation. The comparison below describes the design tradeoff,
not a claim that every application needs all of these mechanisms.

| Architecture | State representation | Typical weakness | OpenEden's different choice |
| --- | --- | --- | --- |
| Stateless chatbot | Conversation window plus prompt | Personality resets or depends entirely on context length | Persisted vector, memory, Omega, relationship, and lived-turn count |
| Prompt-only persona | Natural-language rules | Behavior changes when prompt wording or model changes | Persona data is separated from runtime mechanics and grounded by codebook semantics |
| Fixed finite state machine | A small set of discrete states | Hard transitions and combinatorial state explosion | Continuous 8D state with semantic quantization and bounded deltas |
| Raw continuous vector to LLM | Floats injected directly | The model must invent the meaning of coordinates each turn | VQ-VAE maps vectors to versioned, human-readable codebook definitions |
| Pure semantic RAG | Text similarity | A relevant memory can be emotionally wrong for the current state | Hybrid semantic/emotional retrieval with congruent, mixed, and contrast modes |
| Independent user instances | One state per sender | Group interaction fragments one entity into unrelated copies | Group-scoped shared state plus per-user memory metadata |
| Synchronous state update | Request thread performs all work | Inference and vector search increase latency and block the server | Coroutines, isolated inference execution, Flow streaming, and async projection |

The result is not a deterministic text generator. LLM wording remains
probabilistic. The deterministic part is the state contract around it:
dimension count, bounds, derived values, retrieval rules, confidence gates,
write serialization, trace tags, and fallback behavior.

## Output Contract

Every normal or heartbeat turn is expected to produce the same structured shape:

```json
{
  "internal_logic": "Traceable state-grounded reasoning summary",
  "vector_delta": {
    "L": -0.05, "P": 0.10, "E": 0.00, "S": 0.02,
    "tau": 0.00, "V": 0.00, "M": 0.00, "F": 0.01
  },
  "response": "..."
}
```

The backend consumes `vector_delta`; it does not treat the response text as a
hidden state update. All eight keys are required, unchanged dimensions must be
`0.0`, and `tau` is the ASCII JSON key for Persistence.

## Implementation Map

The most relevant kernel entry points are:

| Concern | Main implementation |
| --- | --- |
| 8D storage and derived D | [`BioVector.kt`](core/src/commonMain/kotlin/io/openeden/bio/BioVector.kt) |
| Storage/internal mapping and symmetry | [`VectorMapping.kt`](core/src/commonMain/kotlin/io/openeden/bio/VectorMapping.kt) |
| Per-turn orchestration | [`MessagePipeline.kt`](core/src/commonMain/kotlin/io/openeden/runtime/pipeline/MessagePipeline.kt) |
| Serialized vector and session writes | [`VectorWriteService.kt`](core/src/commonMain/kotlin/io/openeden/runtime/state/VectorWriteService.kt) |
| Codebook boundary and fallback | [`CodebookQuantizer.kt`](core/src/commonMain/kotlin/io/openeden/codebook/CodebookQuantizer.kt), [`HeuristicCodebookFallback.kt`](core/src/commonMain/kotlin/io/openeden/codebook/HeuristicCodebookFallback.kt) |
| DJL VQ-VAE runner | [`DjlVqVaeCodebookModelRunner.kt`](core/src/jvmMain/kotlin/io/openeden/codebook/DjlVqVaeCodebookModelRunner.kt) |
| Prompt assembly | [`OpenEdenPromptBuilder.kt`](core/src/commonMain/kotlin/io/openeden/prompt/OpenEdenPromptBuilder.kt) |
| Emotional retrieval mode | [`RetrievalModeSelector.kt`](core/src/commonMain/kotlin/io/openeden/memory/RetrievalModeSelector.kt) |
| Dynamic centroid and runtime ticks | [`HomeostasisCentroid.kt`](core/src/commonMain/kotlin/io/openeden/runtime/state/HomeostasisCentroid.kt), [`RuntimeTick.kt`](core/src/commonMain/kotlin/io/openeden/runtime/tick/RuntimeTick.kt) |
| Omega and ShockState | [`OmegaAccumulation.kt`](core/src/commonMain/kotlin/io/openeden/runtime/affect/OmegaAccumulation.kt), [`ShockStateEngine.kt`](core/src/commonMain/kotlin/io/openeden/runtime/affect/ShockStateEngine.kt) |
| Heartbeat scheduling and owner delivery | [`HeartbeatScheduler.kt`](core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatScheduler.kt), [`HeartbeatRouteResolver.kt`](core/src/commonMain/kotlin/io/openeden/runtime/heartbeat/HeartbeatRouteResolver.kt) |
| Runtime assembly and persistence | [`Runtime.kt`](server/src/main/kotlin/io/openeden/server/bootstrap/Runtime.kt), `server/src/main/.../persistence/sqldelight/` |

The public API and CLI deliberately expose only safe response/state summaries.
Prompts, internal reasoning, raw vectors, retrieval modes, and diary details
remain runtime-internal diagnostics.

## Architecture

| Module    | Purpose                                                      |
| --------- | ------------------------------------------------------------ |
| `core`    | Pure domain types and async contracts for the 8D vector, VQ-VAE/codebook boundary, prompt inputs, retrieval modes, Omega, ShockState, diary queues, and serialized vector writes. |
| `server`  | Ktor API, runtime bootstrap, SQLite persistence, background workers, WebSocket installation, and public HTTP endpoints. |
| `onebot`  | NapCat/OneBot v11 reverse WebSocket protocol adapter, connection lifecycle, and QQ message delivery. |
| `client`  | Shared HTTP client helpers for the CLI and future platform frontends. |
| `trainer` | Training and model-related project entry points.             |
| `persona` | Data-only persona, explicit playthrough starting points, heartbeat text, and prompt sections. |
| `data`    | Default location for local models, generated artifacts, and runtime SQLite state. |
| `docs`    | Design notes, boundary documents, and engineering records.   |

Source packages follow the same ownership boundaries:

- `io.openeden.runtime.*` separates pipeline, session, state, affect, tick, heartbeat, diary, and inference responsibilities.
- `io.openeden.cli.*` separates application control, commands, input, UI state, rendering, and terminal integration.
- `io.openeden.server.*` separates bootstrap, API DTOs/routes/plugins, and SQLDelight persistence adapters.
- Test packages and directories mirror the production code they verify.

The main runtime boundaries are:

- Runtime handles vector math, derived D, dual-space mapping, Omega, ShockState, session mutexes, and DJL isolation.
- Prompt Builder injects English logic constraints, Chinese persona/output data, codebook state, retrieval results, and derived D.
- Surface and adapter layers call the shared runtime pipeline without duplicating core logic.
- Heartbeat turns go through the full pipeline and are delivered only to the configured owner target.

## Engineering Invariants

When changing the project, preserve these constraints:

- Use `suspend`, coroutines, and Flow-oriented APIs. Do not block Ktor request threads.
- DJL inference, VQ-VAE quantization, embeddings, dual-space mapping, ShockState decay, and pre-tick perturbation must run on isolated inference execution.
- Apply `vector_delta` to the pre-ticked snapshot, not the original vector.
- Serialize all vector writes through a per-session Mutex and re-read the latest state inside the lock.
- Clamp each pre-tick dimension to `MAX_PRETICK_DELTA = 0.25` and scale it by emotion confidence.
- If VQ-VAE inference is unavailable or low-confidence, use deterministic heuristic fallback and log `codebook=HEURISTIC_FALLBACK`.

## Emotion Inference Output

`thymos_inference.py` emits a compact affect vector that can be used as an input signal for pre-tick perturbation, retrieval weighting, and downstream vector-delta interpretation. These values are soft model signals in the `[0.0, 1.0]` range, not direct replacements for the OpenEden 8D state.

Example:

```json
{
  "valence": 0.43990617990493774,
  "arousal": 0.5741496086120605,
  "dominance": 0.3847764730453491,
  "connectionNeed": 0.6993353962898254,
  "openness": 0.5171651840209961,
  "confidence": 0.5829112529754639
}
```

| Field            | Meaning |
| ---------------- | ------- |
| `valence`        | Emotional pleasantness. Higher values indicate more positive affect; lower values indicate more negative affect. |
| `arousal`        | Activation level. Higher values indicate excitement, urgency, or intensity; lower values indicate calmness or low energy. |
| `dominance`      | Perceived control or assertiveness. Higher values indicate command, certainty, or control; lower values indicate invitation, vulnerability, or passivity. |
| `connectionNeed` | Desire for social closeness or response. Higher values indicate stronger bids for sharing, reassurance, companionship, or attention. |
| `openness`       | Willingness to share, explore, or receive interaction. Higher values indicate more openness or receptivity; lower values indicate closure or guardedness. |
| `confidence`     | Model confidence for the affect estimate. Downstream effects must be scaled by this value; when it is below `0.5`, pre-tick should be skipped, and ShockState back-detection requires at least `0.65`. |

Interpretation should stay conservative. For example, a happy food-sharing message with high `connectionNeed` and medium `confidence` should produce only small positive shifts in Pathos, Vitality, or Empathy unless later pipeline stages provide stronger evidence.

## Requirements

- JDK 21
- Kotlin 2.x
- Gradle Wrapper
- Optional: OpenAI-compatible LLM endpoint
- Optional: DJL/PyTorch local model files

The packaged interactive CLI owns the terminal through JLine's native provider.
On Windows it consumes Unicode console events directly and does not require a
particular PowerShell encoding or `chcp` value.

## Configuration

Copy the example environment file:

```powershell
Copy-Item .env.example .env
```

Common variables:

| Variable                        | Description                                         |
| :------------------------------ | :-------------------------------------------------- |
| `OPENEDEN_LLM_PROVIDER`         | LLM provider, currently defaults to `openai`.       |
| `OPENEDEN_OPENAI_API_KEY`       | API key for OpenAI or an OpenAI-compatible service. |
| `OPENEDEN_OPENAI_MODEL`         | LLM model name.                                     |
| `OPENEDEN_OPENAI_BASE_URL`      | OpenAI-compatible endpoint.                         |
| `OPENEDEN_LLM_REASONING_EFFORT` | Reasoning effort: `low`, `medium`, or `high`.       |
| `OPENEDEN_LLM_TEMPERATURE_MIN`  | Lower bound for the dynamic per-turn temperature; default `0.2`. |
| `OPENEDEN_LLM_TEMPERATURE_MAX`  | Upper bound for the dynamic per-turn temperature; default `1.0`. |
| `OPENEDEN_LLM_MAX_OUTPUT_TOKENS` | Optional static token ceiling, including reasoning and visible output tokens. |
| `OPENEDEN_SERVER_URL`           | Server URL used by the CLI.                         |
| `OPENEDEN_RUNTIME_DB_PATH`      | SQLite runtime database path.                       |
| `OPENEDEN_PERSONA_PATH`         | Persona YAML path.                                  |
| `OPENEDEN_LOCAL_MODEL_ARTIFACT` | Local model artifact path.                          |
| `OPENEDEN_OWNER_PLATFORM`       | Optional heartbeat owner delivery platform.         |
| `OPENEDEN_OWNER_USER_ID`        | Optional heartbeat owner user ID.                   |
| `OPENEDEN_HOST_PLATFORM`        | Optional authoritative host identity platform.      |
| `OPENEDEN_HOST_USER_ID`         | Optional authoritative host identity user ID.       |
| `OPENEDEN_HOST_ADDRESS`         | Optional preferred address used only for the exact configured host. |
| `OPENEDEN_ENABLE_CLI_DIAGNOSTICS` | Enable the token-gated diagnostic endpoint; default `false`. |
| `OPENEDEN_CLI_DIAGNOSTICS_TOKEN` | Separate credential used only by the optional CLI diagnostic panel. |

DeepSeek Responses-compatible endpoint example:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="deepseek-v4-flash"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.deepseek.com"
```

This uses the same OpenAI Responses adapter; OpenEden has no provider-specific
branching. DeepSeek thinking mode may ignore `temperature`. DeepSeek accepts the
Responses `verbosity` field, but may not apply it.

## Quick Start

Download the local model artifact if it is missing:

```powershell
.\gradlew.bat ensureLocalModelArtifact
```

Start the server:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="gpt-5.5"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.openai.com/v1"
.\gradlew.bat :server:run
```

In another PowerShell window, start the CLI:

```powershell
.\gradlew.bat installDist
.\build\install\openeden\bin\openeden.bat
```

`gradlew run` is a development convenience. Gradle proxies terminal streams
through pipes, so it is not the supported path for interactive line editing.

Send one compatibility chat request:

```powershell
.\gradlew.bat run --args="chat --message `"hello`""
```

Print local CLI state:

```powershell
.\gradlew.bat run --args="state"
```

## CLI

```text
/help
/state
/mode inline|full
/inspect on|off
/clear
/exit
```

Interactive sessions start in vertical inline mode, which keeps completed turns in native terminal scrollback. `/mode full` or `Ctrl+T` switches to the alternate-screen view without creating a second server session; switching back preserves the conversation. `/exit` closes only the CLI HTTP client and does not stop the server.

Interactive input uses JLine for history, cursor movement, insertion, deletion,
IME input, and supplementary Unicode characters such as emoji. See
[Terminal input](docs/terminal-input.md) for the terminal ownership and encoding
contract. Internal Omega, ShockState, and 8D vector diagnostics are not shown by
default. `Alt+Enter` inserts a newline, `Tab` completes slash commands, Esc or
`Ctrl+C` cancels active generation, `Ctrl+D` exits an empty editor, and `Alt+I`
toggles diagnostics.

Diagnostics have two gates: the panel is hidden on every launch, and the server
endpoint is disabled unless `OPENEDEN_ENABLE_CLI_DIAGNOSTICS=true` with a
non-empty `OPENEDEN_CLI_DIAGNOSTICS_TOKEN`. The panel contains only safe state
summaries; prompts and internal reasoning are never returned.

Interactive input/output is owned exclusively by JLine's native terminal provider
and does not depend on the shell code page. Redirected streams and one-shot commands
use fixed UTF-8, consume one optional UTF-8 input BOM, emit neither a BOM nor ANSI
controls, and provide no encoding overrides. External producers must emit UTF-8;
other pipe encodings are unsupported. The CLI never runs `chcp` or changes global
shell state.

On first startup, the CLI creates:

```text
%USERPROFILE%\.openeden\config.json
```

This file contains client settings only. LLM, runtime, model, and persona settings belong to the server.

## HTTP API

The server listens on:

```text
http://0.0.0.0:8080
```

Public endpoints:

```text
GET  /health
POST /api/v1/chat       {"userId":"local","text":"hello"}
POST /api/v1/chat/stream {"userId":"local","text":"hello","clientRequestId":"..."}
GET  /api/v1/state?userId=local
```

The stream endpoint emits only `accepted`, safe `stage`, `response.delta`,
`completed`, and safe `error` events. Providers with strict structured streaming
produce validated public deltas. Other providers fall back to buffered delivery
after the complete output schema has passed validation.

Chat responses contain:

```json
{
  "requestId": "...",
  "status": "...",
  "response": "...",
  "error": null
}
```

Internal vectors, `evolutionIndex`, prompts, traces, retrieval modes, and diary details are not exposed through the public CLI/API response.

## Build And Test

The focused server test suite covers the runtime and persistence boundaries;
the full Gradle build also compiles the CLI, client, core, and trainer modules.
The Unicode terminal check is separate because it exercises the Windows native
console path.

## Qdrant Vector Database

Qdrant is an optional, rebuildable candidate index. SQLite remains the authoritative
store for memory text, metadata, embeddings, runtime state, and projection status.
The server writes SQLite first and projects vectors asynchronously; an unavailable
Qdrant automatically uses the in-memory index and `/health` remains `ready`.

Start the local Qdrant service with the pinned image and persistent named volume:

```powershell
docker compose up -d qdrant
```

The default endpoint is `http://localhost:6333`. For a remote service, set
`OPENEDEN_QDRANT_URL`; set `OPENEDEN_QDRANT_API_KEY` only when the remote service
requires it. The API key is never included in diagnostics or logs.

The active collection is derived from `OPENEDEN_QDRANT_COLLECTION` and
`OPENEDEN_EMBEDDING_MODEL_ID` (default `local-v1`). Changing the embedding model
creates a separate collection and refreshes stored embeddings in the background;
old collections are not deleted automatically.

To force a complete projection rebuild, stop writes if appropriate and delete only
the active Qdrant collection. The synchronizer recreates it from SQLite. Back up
`data/runtime/openeden.db`: it is the recovery artifact, while Qdrant contains only
the disposable search projection.

When Qdrant is degraded, token-gated `/api/v1/diagnostics` reports the backend,
collection, circuit state, projection counts, last remote success, and a sanitized
error category. It never returns memory content, embeddings, or credentials.

```powershell
.\gradlew.bat :server:test
.\gradlew.bat :server:build
.\scripts\verify-cli-unicode.ps1
```

Useful Gradle tasks:

| Task                                                  | Description                                                 |
| ----------------------------------------------------- | ----------------------------------------------------------- |
| `.\gradlew.bat ensureLocalModelArtifact`              | Download the default local model artifact if it is missing. |
| `.\gradlew.bat :server:run`                           | Start the Ktor server.                                      |
| `.\gradlew.bat installDist`                           | Build the supported packaged interactive CLI.               |
| `.\gradlew.bat run --args="chat --message \"hello\""` | Send one compatibility chat request.                        |
| `.\gradlew.bat run --args="state"`                    | Print local CLI session state.                              |
| `.\gradlew.bat :server:test`                          | Run server tests.                                           |
| `.\gradlew.bat :server:build`                         | Build the server module.                                    |

The default model artifact is hosted at:

```text
https://huggingface.co/0x4C57/openeden-codebook-base-model
```

Override the download URL with `OPENEDEN_LOCAL_MODEL_ARTIFACT_URL`.

## Sessions And Data

- CLI, direct, and web one-to-one sessions use `<platform>:<userId>`.
- Group deployments use a shared state model with `<platform>:<groupId>`.
- Individual `user_id` values are still recorded as memory metadata but do not create separate ATRI instances inside group deployments.
- The default SQLite path is `data/runtime/openeden.db`.

## License

OpenEden code, generated codebook artifacts, and published OpenEden model artifacts are released under the GNU Affero General Public License v3.0. See [`LICENSE`](LICENSE).
