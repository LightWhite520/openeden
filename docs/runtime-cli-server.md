# Runtime CLI and Server

## Process Model

The Ktor server owns `OpenEdenRuntimePipeline`, SQLite/SQLDelight stores, DJL
models, the LLM client, heartbeat scheduling, runtime ticks, and the diary
worker. The CLI never opens SQLite and never applies vector state changes.

Start the server separately:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()
.\gradlew.bat :server:run
```

Then start the CLI in another terminal:

```powershell
.\gradlew.bat run
```

The CLI checks `OPENEDEN_SERVER_URL` with `GET /health`. It never starts or
stops the server process. `/exit` closes only the CLI client.

## CLI Commands

```text
/state
/help
/exit
```

Every other non-empty line is sent as a chat message. `chat --message` and
`state --user` remain compatibility commands and also use HTTP.

## Public API

```text
GET  /health
POST /api/v1/chat
GET  /api/v1/state?userId=local
```

The chat request is `{ "userId": "local", "text": "..." }`. The response is
`requestId`, `status`, `response`, and `error`. Runtime vectors,
`evolutionIndex`, prompts, trace tags, retrieval modes, Ω internals, and diary
worker details remain server-side.

## Configuration

The CLI creates `%USERPROFILE%\.openeden\config.json` on first startup:

```json
{
  "serverUrl": "http://127.0.0.1:8080",
  "userId": "your-system-user"
}
```

`userId` is generated from the operating-system username when the file is
created. The CLI does not read LLM or runtime settings.

The server reads LLM, model, persona, database, and heartbeat settings from
`application.yaml`; those YAML values reference process environment variables.
The server and CLI do not automatically load `.env` files.
