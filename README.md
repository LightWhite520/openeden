# OpenEden

OpenEden is a Kotlin/Ktor runtime skeleton for a deterministic biological state
machine. The current repository establishes module boundaries, core contracts,
and tests for the invariants in `AGENTS.md`; production DJL/VQ-VAE inference,
Memory Palace persistence, persona content, and platform adapters are still
future integrations.

## Modules

| Path | Purpose |
|------|---------|
| `core` | Pure domain types and async contracts for the 8D vector, VQ-VAE/codebook boundary, prompt inputs, memory retrieval mode, Omega, ShockState, diary queues, and serialized vector writes. |
| `server` | Ktor wiring, health routes, WebSocket installation, and in-memory skeleton service exposure. |
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
| `./gradlew :run --args="chat --message \"hello\""` | Run the local CLI chat surface |
| `./gradlew :run --args="state"` | Print local CLI session state |
| `./gradlew :server:test`  | Run the tests     |
| `./gradlew :server:build` | Build the project |
| `./gradlew :server:run`   | Run the server    |

If the server starts successfully, it listens on `http://0.0.0.0:8080` and the
root route returns `OpenEden runtime skeleton`.

## Local CLI

Stage 1 exposes a local one-on-one runtime through the root application. Set:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="gpt-5-mini"
$env:OPENEDEN_LOCAL_USER_ID="local"
```

If you use an OpenAI-compatible relay, also set its API base URL:

```powershell
$env:OPENEDEN_OPENAI_BASE_URL="https://your-relay.example.com/v1"
```

Then run:

```powershell
.\gradlew.bat :run --args='chat --message "你好"'
.\gradlew.bat :run --args='chat --message "你好" --debug'
.\gradlew.bat :run --args='state --user local'
```

Local sessions use `CLI:<userId>` and persist to
`data/runtime/openeden.db` unless `OPENEDEN_RUNTIME_DB_PATH` is set.
