# Runtime Bootstrap

The bootstrap slice uses `persona/default.yaml`, `data/codebook/codebook.example.csv`, in-memory session state, heuristic codebook fallback, and a development LLM stub.

Run tests with:

```powershell
.\gradlew.bat :server:test
```

Run the server with:

```powershell
.\gradlew.bat :server:run
```

Development endpoint:

`POST /dev/message`

The endpoint is for local verification only. Production platform adapters should call the same runtime pipeline instead of duplicating logic.

## Runtime Tick

The runtime tick runs independently of user messages. It applies sine-wave physiological drift, ShockState passive decay, and Ω accumulation without incrementing `evolution_index`.

Heartbeat owner delivery is configured with:

```powershell
$env:OPENEDEN_OWNER_PLATFORM="QQ"
$env:OPENEDEN_OWNER_USER_ID="123456"
```

If owner variables are absent, heartbeat output is dropped after state write-back.

## Local CLI

The Stage 1 local runtime uses the same pipeline as the development route, but
through a production-facing local contract. Configure:

```powershell
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="gpt-5.5"
$env:OPENEDEN_LOCAL_USER_ID="local"
```

OpenAI-compatible relay providers are configured by overriding the base URL:

```powershell
$env:OPENEDEN_OPENAI_BASE_URL="https://your-relay.example.com/v1"
```

Run:

```powershell
.\gradlew.bat :run --args='chat --message "hello"'
.\gradlew.bat :run --args='chat --message "hello" --debug'
.\gradlew.bat :run --args='state --user local'
```

The CLI stores state in `data/runtime/openeden.db` by default. It keeps
`sessionId = "CLI:<userId>"`, uses the configured persona YAML, and still routes
prompt state through the codebook boundary before provider calls.

## Production Runtime Verification

The production verification script exercises the real DJL backend, OpenAI-compatible endpoint,
durable SQLite state, asynchronous Diary generation, graceful shutdown, and restart recovery. It
reads `.env` in the repository root and never prints the API key. Ensure `.env` contains
`OPENEDEN_OPENAI_API_KEY` plus the four DJL model path variables:
`OPENEDEN_DJL_VQVAE_MODEL_PATH`, `OPENEDEN_DJL_TEXT_MODEL_PATH`,
`OPENEDEN_DJL_EMOTIONAL_MODEL_PATH`, and `OPENEDEN_DJL_AFFECT_MODEL_PATH`.

Install `sqlite3` on `PATH`, then run from PowerShell:

```powershell
.\scripts\verify-production-runtime.ps1
```

The script requires JDK 21 at `F:\SDK\JDK21`, uses a temporary file-backed SQLite database,
starts `:server:run`, sends three chats, waits for a completed Diary task, stops and restarts the
server against the same database, and sends a fourth chat. Startup, request, Diary, and restart
waits are finite; override them when needed:

```powershell
.\scripts\verify-production-runtime.ps1 -StartupTimeoutSeconds 120 -DiaryTimeoutSeconds 300
```

The temporary database and child server are removed/stopped in all exit paths.
