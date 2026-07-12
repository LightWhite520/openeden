# OpenEden

[中文文档](README.zh-CN.md)

OpenEden is a Kotlin/Ktor runtime for a deterministic continuous-to-discrete biological state machine. It connects the 8D physiological vector, VQ-VAE codebook, memory retrieval, Omega wear, ShockState, heartbeat tasks, and LLM prompt construction through one traceable async pipeline.

## What This Project Is

OpenEden is not a chatbot with personality hardcoded into business logic. It is a deterministic, mathematical runtime for a continuous-to-discrete biological state machine.

The runtime keeps personality externalized as data:

- Persona rules live in `persona/*.yaml`, distilled prompt data, and VQ-VAE codebook semantic definitions.
- Kotlin code owns state math, async execution, persistence, scheduling, validation, and adapter boundaries.
- Prompt construction receives codebook semantic nodes or a logged heuristic fallback, not raw 8D floats as behavioral rules.
- Dissonance `D` is derived at runtime with `D = |L - tau| * (1 - E)` and is never stored as a ninth vector dimension.

## Architecture

| Module | Purpose |
| --- | --- |
| `core` | Pure domain types and async contracts for the 8D vector, VQ-VAE/codebook boundary, prompt inputs, retrieval modes, Omega, ShockState, diary queues, and serialized vector writes. |
| `server` | Ktor API, runtime bootstrap, SQLite persistence, background workers, WebSocket installation, and public HTTP endpoints. |
| `client` | Shared HTTP client helpers for the CLI and future platform frontends. |
| `trainer` | Training and model-related project entry points. |
| `persona` | Data-only persona, growth thresholds, heartbeat text, and prompt sections. |
| `data` | Default location for local models, generated artifacts, and runtime SQLite state. |
| `docs` | Design notes, boundary documents, and engineering records. |

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

## Requirements

- JDK 21
- Kotlin 2.x
- Gradle Wrapper
- Optional: OpenAI-compatible LLM endpoint
- Optional: DJL/PyTorch local model files

For Windows PowerShell, UTF-8 output is recommended:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
```

## Configuration

Copy the example environment file:

```powershell
Copy-Item .env.example .env
```

Common variables:

| Variable | Description |
| --- | --- |
| `OPENEDEN_LLM_PROVIDER` | LLM provider, currently defaults to `openai`. |
| `OPENEDEN_OPENAI_API_KEY` | API key for OpenAI or an OpenAI-compatible service. |
| `OPENEDEN_OPENAI_MODEL` | LLM model name. |
| `OPENEDEN_OPENAI_BASE_URL` | OpenAI-compatible endpoint. |
| `OPENEDEN_LLM_REASONING_EFFORT` | Reasoning effort: `low`, `medium`, or `high`. |
| `OPENEDEN_SERVER_URL` | Server URL used by the CLI. |
| `OPENEDEN_RUNTIME_DB_PATH` | SQLite runtime database path. |
| `OPENEDEN_PERSONA_PATH` | Persona YAML path. |
| `OPENEDEN_LOCAL_MODEL_ARTIFACT` | Local model artifact path. |
| `OPENEDEN_OWNER_PLATFORM` | Optional heartbeat owner delivery platform. |
| `OPENEDEN_OWNER_USER_ID` | Optional heartbeat owner user ID. |

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
.\gradlew.bat run
```

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
/state
/help
/exit
```

Normal input is sent to `POST /api/v1/chat`. `/exit` closes only the CLI HTTP client and does not stop the server.

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
GET  /api/v1/state?userId=local
```

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
```

Useful Gradle tasks:

| Task | Description |
| --- | --- |
| `.\gradlew.bat ensureLocalModelArtifact` | Download the default local model artifact if it is missing. |
| `.\gradlew.bat :server:run` | Start the Ktor server. |
| `.\gradlew.bat run` | Start the persistent server-backed CLI. |
| `.\gradlew.bat run --args="chat --message \"hello\""` | Send one compatibility chat request. |
| `.\gradlew.bat run --args="state"` | Print local CLI session state. |
| `.\gradlew.bat :server:test` | Run server tests. |
| `.\gradlew.bat :server:build` | Build the server module. |

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
