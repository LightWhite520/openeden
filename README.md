# OpenEden

OpenEden is a Kotlin/Ktor runtime for a deterministic biological state machine.
The server is the single owner of the kernel, SQLite state, local models, LLM
client, heartbeat, runtime tick, and diary worker. The CLI is a persistent
terminal client that communicates with the server over HTTP.

## License

OpenEden code, generated codebook artifacts, and published OpenEden model
artifacts are released under the GNU Affero General Public License v3.0. See
[`LICENSE`](LICENSE).

## Modules

| Path | Purpose |
|------|---------|
| `core` | Pure domain types and async contracts for the 8D vector, VQ-VAE/codebook boundary, prompt inputs, memory retrieval mode, Omega, ShockState, diary queues, and serialized vector writes. |
| `server` | Ktor API, durable runtime bootstrap, background workers, and WebSocket installation. |
| `client` | Shared HTTP client helpers for future platform frontends. |

## Architecture Rules

- Personality stays externalized as data; Kotlin code defines contracts and
  config boundaries only.
- Runtime/model-like work is represented by `suspend` APIs so implementations
  can run off Ktor request threads.
- Prompt construction consumes codebook semantic definitions or the logged
  heuristic fallback, not raw 8D floats as persona behavior.
- Dissonance is derived at runtime from `L`, `tau`, and `E`; it is not stored as
  a ninth vector dimension.
- Vector write-back is shaped around per-session mutexes and applies LLM deltas
  relative to the pre-ticked snapshot.

## Building And Running

To build or run the project, use one of the following tasks:

| Task                      | Description       |
|---------------------------|-------------------|
| `./gradlew ensureLocalModelArtifact` | Download `data/models/local-model-artifact.json` from the public Hugging Face model repo if it is missing |
| `./gradlew :run` | Start the persistent server-backed CLI |
| `./gradlew :run --args="chat --message \"hello\""` | Run one compatibility chat request through the server |
| `./gradlew :run --args="state"` | Print local CLI session state |
| `./gradlew :server:test`  | Run the tests     |
| `./gradlew :server:build` | Build the project |
| `./gradlew :server:run`   | Run the server    |

If the server starts successfully, it listens on `http://0.0.0.0:8080` and the
root route returns `OpenEden runtime skeleton`.

## Server-Backed CLI

On first CLI startup, OpenEden creates
`%USERPROFILE%\.openeden\config.json` with the server URL and the current system
username. The CLI configuration contains only client settings; LLM and runtime
settings belong to the server.

The default CLI startup is:

```powershell
.\gradlew.bat :server:run
# in another PowerShell window:
.\gradlew.bat run
```

The server reads its environment variables through `application.yaml`. Start
the server after configuring them in PowerShell:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="gpt-5.5"
$env:OPENEDEN_OPENAI_BASE_URL="https://your-relay.example.com/v1"
# Optional: low, medium (default), or high.
$env:OPENEDEN_LLM_REASONING_EFFORT="medium"
.\gradlew.bat :server:run
```

The REPL supports:

```text
/state
/help
/exit
```

Normal input is sent to `POST /api/v1/chat`. `/exit` closes only the CLI HTTP
client. The CLI never starts or stops the server; start `:server:run` in a
separate terminal first.

The public server endpoints are:

```text
GET  /health
POST /api/v1/chat       {"userId":"local","text":"你好"}
GET  /api/v1/state?userId=local
```

Chat responses contain `requestId`, `status`, `response`, and `error`. Internal
vectors, `evolutionIndex`, prompts, traces, retrieval modes, and diary details
are not part of the public CLI/API response.

Local sessions use `CLI:<userId>` and persist to the server's
`data/runtime/openeden.db` unless `OPENEDEN_RUNTIME_DB_PATH` is set.

The `:run` task depends on `ensureLocalModelArtifact`. If
`OPENEDEN_LOCAL_MODEL_ARTIFACT` is missing, Gradle downloads the default runtime
artifact from:

```text
https://huggingface.co/0x4C57/openeden-codebook-base-model
```

Override the artifact URL with `OPENEDEN_LOCAL_MODEL_ARTIFACT_URL`.
