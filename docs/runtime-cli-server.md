# Runtime CLI and Server

[Project overview](../README.md)

## Process Model

The Ktor server owns `OpenEdenRuntimePipeline`, SQLite/SQLDelight stores, DJL
models, the LLM client, heartbeat scheduling, runtime ticks, and the diary
worker. The CLI never opens SQLite and never applies vector state changes.

Start the server separately:

```powershell
.\gradlew.bat :server:run
```

Then start the CLI in another terminal:

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\openeden\bin\openeden.bat
```

The server defaults to port `8080`, overridden by `OPENEDEN_SERVER_PORT`.
Configure the client's saved address as described below. The CLI checks that
address with `GET /health`. It never starts or stops the server process.
`/exit` closes only the CLI client. Interactive input uses JLine; manual console
encoding changes are not required. Use the packaged launcher: Gradle proxies
standard streams and `:cli:run` is only a development convenience. Redirected
streams use UTF-8.

## CLI Commands

```text
/state
/help
/mode inline|full
/inspect on|off
/clear
/model
/model refresh
/model <number|id>
/exit
```

Every other non-empty line is sent as a chat message. `chat --message` and
`state --user` remain compatibility commands and also use HTTP.
Model selection requires the operator token described in the
[model setup guide](operations/chatgpt-and-model-selection.md).

## Public API

```text
GET  /health
POST /api/v1/chat
POST /api/v1/chat/stream
GET  /api/v1/state?userId=local
GET  /api/v1/history
```

The chat request is `{ "userId": "local", "text": "..." }`. The response is
`requestId`, `status`, `response`, and `error`. Streaming additionally requires
`clientRequestId`. Public chat output excludes raw vectors, prompts, internal
logic, retrieval diagnostics, and diary details. The state endpoint returns a
safe summary; detailed diagnostics require separate enablement and a token.

## Configuration

The CLI creates `%USERPROFILE%\.openeden\config.json` on first startup.
Its initial `serverUrl` is `http://127.0.0.1:8080`, matching the default server.
For a custom server address, edit the file and restart the client:

```json
{
  "serverUrl": "http://127.0.0.1:8080",
  "userId": "your-system-user"
}
```

`userId` is generated from the operating-system username when the file is
created. Keep your existing `userId` when changing the address. The JVM CLI
currently reads `serverUrl` from this file, not `OPENEDEN_SERVER_URL`; setting
that environment variable alone does not override the saved address, including
through the npm launcher. The CLI does not read LLM or runtime settings.

The server reads LLM, model, persona, database, and heartbeat settings from
`application.yaml`; those YAML values reference process environment variables.
The server and CLI do not automatically load `.env` files.

## Models and background tasks

Prepare model artifacts before starting the server:

```powershell
.\gradlew.bat ensureLocalModelArtifact
.\gradlew.bat ensureThymosAffectModel
```

The server defaults to `persona/atri.yaml` and `data/runtime/openeden.db`.
For model paths and export commands, see [model training](local-model-training.md).

Background ticks update the shared incarnation without advancing its completed-turn
counter. Heartbeats run full pipeline turns and advance it. Set
`OPENEDEN_OWNER_PLATFORM` and `OPENEDEN_OWNER_USER_ID` for owner-only heartbeat
delivery. Host identity is configured separately; see [OneBot](onebot-napcat.md).

## Verification

```powershell
.\gradlew.bat :server:test
.\scripts\verify-cli-unicode.ps1
```

`scripts/verify-production-runtime.ps1` performs live model calls using an isolated
temporary database, then checks diary persistence and restart recovery. It reads
`.env`, requires `sqlite3` on PATH, and currently expects JDK 21 at `F:\SDK\JDK21`.
Run it explicitly when live integration verification is needed.
