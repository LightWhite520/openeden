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

## Architecture

| Module    | Purpose                                                      |
| --------- | ------------------------------------------------------------ |
| `core`    | Pure domain types and async contracts for the 8D vector, VQ-VAE/codebook boundary, prompt inputs, retrieval modes, Omega, ShockState, diary queues, and serialized vector writes. |
| `server`  | Ktor API, runtime bootstrap, SQLite persistence, background workers, WebSocket installation, and public HTTP endpoints. |
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
| `OPENEDEN_SERVER_URL`           | Server URL used by the CLI.                         |
| `OPENEDEN_RUNTIME_DB_PATH`      | SQLite runtime database path.                       |
| `OPENEDEN_PERSONA_PATH`         | Persona YAML path.                                  |
| `OPENEDEN_LOCAL_MODEL_ARTIFACT` | Local model artifact path.                          |
| `OPENEDEN_OWNER_PLATFORM`       | Optional heartbeat owner delivery platform.         |
| `OPENEDEN_OWNER_USER_ID`        | Optional heartbeat owner user ID.                   |
| `OPENEDEN_HOST_PLATFORM`        | Optional authoritative host identity platform.      |
| `OPENEDEN_HOST_USER_ID`         | Optional authoritative host identity user ID.       |
| `OPENEDEN_ENABLE_CLI_DIAGNOSTICS` | Enable the token-gated diagnostic endpoint; default `false`. |
| `OPENEDEN_CLI_DIAGNOSTICS_TOKEN` | Separate credential used only by the optional CLI diagnostic panel. |
| `OPENEDEN_STDIN_ENCODING`       | Redirected stdin charset override; default `UTF-8`.  |
| `OPENEDEN_STDOUT_ENCODING`      | Redirected stdout charset override; default `UTF-8`. |
| `OPENEDEN_STDERR_ENCODING`      | Redirected stderr charset override; default `UTF-8`. |

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

TTY input/output uses JLine's native terminal provider. Redirected streams are
UTF-8 without an output BOM, consume one optional UTF-8 input BOM, and emit no
ANSI controls. Legacy producers can opt into a charset such as `GBK` with the
three explicit stream variables above; the CLI never changes global shell state.

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
