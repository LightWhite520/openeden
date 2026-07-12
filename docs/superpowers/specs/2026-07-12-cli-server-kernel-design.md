# CLI/Server Kernel Boundary Design

## Goal

Make the CLI a persistent Codex-style terminal client while keeping the OpenEden kernel in one long-running server process.

## Runtime Boundary

The server is the only process that creates and owns `OpenEdenRuntimePipeline`, SQLite stores, DJL models, LLM client, heartbeat scheduler, runtime tick, and Diary worker. The CLI does not construct the pipeline, open SQLite, or apply vector state changes.

The CLI communicates with the server over HTTP. A local CLI invocation checks the configured server URL, starts a local server when it is unavailable, waits for health readiness, and then enters an interactive REPL. Exiting the CLI never stops the server; heartbeat and background state evolution continue.

## Configuration

`OPENEDEN_SERVER_URL` defaults to `http://127.0.0.1:8080`. A local URL may be auto-started; a remote URL is only contacted. Server business configuration continues to come from environment variables and `.env`. `application.yaml` remains limited to Ktor deployment and module configuration.

## HTTP API

The server exposes:

- `GET /health` for readiness checks.
- `POST /api/v1/chat` for one chat turn.
- `GET /api/v1/state` for local diagnostic state inspection.

Chat requests contain `userId` and `text`. Chat responses contain only `requestId`, `status`, `response`, and `error`. Internal fields such as `evolutionIndex`, the 8D vector, Ω, ShockState, trace tags, prompt preview, and internal reasoning are not included in normal chat responses.

## CLI REPL

Running `:run` without a one-shot message enters a persistent loop. User text is sent to `/api/v1/chat` and the returned response is printed. `/state`, `/help`, and `/exit` are local commands. `/exit` closes only the CLI client. Existing explicit state/chat commands may remain as compatibility paths until the REPL is complete.

## Local Server Process

For the first implementation, the CLI starts the project server through `gradlew.bat :server:run` when the default local endpoint is unavailable. The child server is left running after CLI exit. The CLI must report startup failure clearly and must not enter the REPL until `/health` succeeds.

## Error Handling

- Unavailable remote server: return a clear connection error without spawning a process.
- Failed local server startup or health timeout: return a clear startup error and exit.
- HTTP 4xx/5xx from chat: display the server error and keep the REPL alive when possible.
- Malformed responses: treat as a client protocol error and keep the session recoverable.

## Testing

- Server route tests for health, chat response shape, state endpoint, and invalid requests.
- CLI client tests using an HTTP mock engine for successful chat, server errors, and command handling.
- Local server manager tests for reuse of a healthy endpoint and startup timeout behavior.
- End-to-end smoke test proving two CLI messages share the server-backed SQLite session state.
