# CLI/Server Kernel Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the root CLI into a persistent Codex-style HTTP client while making the Ktor server the sole owner of the OpenEden kernel and its durable runtime state.

**Architecture:** The server boots the existing durable `DevelopmentMessagePipeline`, model runners, SQLite stores, heartbeat scheduler, runtime tick, and diary worker. The CLI owns only terminal interaction and an HTTP client; for the default local URL it starts `:server:run` when `/health` is unavailable, waits for readiness, and leaves that process running after `/exit`. Formal public routes expose only chat text and a minimal state view; internal vector, prompt, trace, retrieval, and evolution fields remain server-side.

**Tech Stack:** Kotlin/JVM 21, Ktor server/client CIO, kotlinx.serialization, coroutines, Clikt, existing SQLite/SQLDelight runtime.

---

### Task 1: Define the public HTTP contract and server health route

**Files:**
- Create: `server/src/main/kotlin/ApiContracts.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Modify: `server/src/main/kotlin/StatusPages.kt`
- Test: `server/src/test/kotlin/ServerApiTest.kt`

- [ ] **Step 1: Write failing route tests**

Add tests with `testApplication` for:

```kotlin
assertEquals(HttpStatusCode.OK, client.get("/health").status)
val response = client.post("/api/v1/chat") {
    contentType(ContentType.Application.Json)
    setBody("""{"userId":"local","text":"hello"}""")
}
assertEquals(HttpStatusCode.OK, response.status)
val body = Json.decodeFromString<ChatResponseDto>(response.bodyAsText())
assertTrue(body.requestId.isNotBlank())
assertEquals("completed", body.status)
assertNotNull(body.response)
assertFalse(response.bodyAsText().contains("evolutionIndex"))
assertFalse(response.bodyAsText().contains("snapshot_8D"))
```

Also test `GET /api/v1/state?userId=local` returns the minimal public state contract and that malformed chat input receives `400`.

- [ ] **Step 2: Run the focused server tests and verify failure**

Run:

```powershell
.\gradlew.bat :server:test --tests "io.openeden.server.ServerApiTest" --no-daemon
```

Expected: compilation/test failure because the public DTOs and routes do not exist.

- [ ] **Step 3: Add serializable public DTOs**

Define `ChatRequestDto(userId: String = "local", text: String)`, `ChatResponseDto(requestId, status, response, error)`, `PublicStateDto(sessionId, status, omega, shockActive)`, and `HealthResponseDto(status, service)` in `ApiContracts.kt`. Do not include `evolutionIndex`, vectors, prompt, trace tags, retrieval mode, or diary internals.

- [ ] **Step 4: Implement routes over the existing pipeline attribute**

In `configureRouting`, resolve `PipelineKey`, generate a request ID such as `req_<UUID without hyphens>`, map the public request to `DevelopmentMessageRequest(platform = "CLI", scopeId = userId, userId = userId, text = text)`, and return `completed` with only `result.response`. Catch request-level failures and return `failed` with a non-empty error while preserving the request ID. Add `/health` before the chat route and add `/api/v1/state` by reading the durable `SessionStateStore` published by runtime through a second application attribute.

- [ ] **Step 5: Make error responses JSON and run tests**

Keep the existing development route for compatibility, but ensure `StatusPages` returns a structured JSON error for public API failures. Run the focused test again and then `:server:test`.

- [ ] **Step 6: Commit the server contract**

```powershell
git add server/src/main/kotlin/ApiContracts.kt server/src/main/kotlin/Routing.kt server/src/main/kotlin/StatusPages.kt server/src/test/kotlin/ServerApiTest.kt
git commit -m "feat: add public server api contract"
```

### Task 2: Publish server-owned state and make runtime configuration consistent

**Files:**
- Modify: `server/src/main/kotlin/Runtime.kt`
- Modify: `server/src/main/kotlin/Routing.kt`
- Modify: `server/src/main/resources/application.yaml`
- Test: `server/src/test/kotlin/ServerApiTest.kt`

- [ ] **Step 1: Add a runtime state attribute and test state after chat**

Expose a read-only `SessionStateStore` attribute from `configureRuntime`; in the route test, send a chat and then request `/api/v1/state?userId=local`, asserting the same `CLI:local` session is reported.

- [ ] **Step 2: Implement the attribute and state route**

Add an `AttributeKey<SessionStateStore>`, publish the same store instance used by the pipeline, and close it exactly once during `ApplicationStopping`. Build `PublicStateDto` from the persisted state, converting the internal values to coarse public fields only. Do not move kernel logic into the route.

- [ ] **Step 3: Verify module ordering and environment behavior**

Keep `application.yaml` limited to port and module registration. Ensure `configureRuntime` runs before `configureRouting`, and retain environment/`.env` loading in Kotlin configuration rather than routing it through YAML. Run:

```powershell
.\gradlew.bat :server:test --no-daemon
```

- [ ] **Step 4: Commit runtime publication**

```powershell
git add server/src/main/kotlin/Runtime.kt server/src/main/kotlin/Routing.kt server/src/main/resources/application.yaml server/src/test/kotlin/ServerApiTest.kt
git commit -m "refactor: publish server-owned runtime state"
```

### Task 3: Build an asynchronous HTTP client for the CLI

**Files:**
- Create: `src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt`
- Create: `src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt`
- Modify: `build.gradle.kts` only if an existing client dependency is insufficient

- [ ] **Step 1: Write client tests against `MockEngine`**

Cover `health()`, `chat(userId, text)`, `state(userId)`, non-2xx error decoding, and URL joining when the configured base URL ends with `/`. Assert that chat decoding exposes only `requestId`, status, response, and error.

- [ ] **Step 2: Run the client tests and verify failure**

```powershell
.\gradlew.bat :test --tests "io.openeden.client.OpenEdenServerClientTest" --no-daemon
```

Expected: failure because the client and DTO mapping do not exist.

- [ ] **Step 3: Implement the client with Ktor CIO-compatible APIs**

Use an injected `HttpClient`, `Json`, and `baseUrl`. All public methods are `suspend`; set JSON content type, decode success bodies, convert transport/status failures into a typed `ServerClientException`, and expose `close()` for the CLI process. Do not import `OpenEdenRuntimePipeline`, SQLite, DJL, or server DB classes.

- [ ] **Step 4: Run focused and full root tests**

Run the focused test, then `:test --no-daemon`.

- [ ] **Step 5: Commit the HTTP client**

```powershell
git add src/main/kotlin/io/openeden/client/OpenEdenServerClient.kt src/test/kotlin/io/openeden/client/OpenEdenServerClientTest.kt build.gradle.kts
git commit -m "feat: add cli http client for server api"
```

### Task 4: Add local server discovery, startup, and reuse

**Files:**
- Create: `src/main/kotlin/io/openeden/client/ServerProcessManager.kt`
- Create: `src/test/kotlin/io/openeden/client/ServerProcessManagerTest.kt`
- Modify: `src/main/kotlin/io/openeden/config/LocalRuntimeConfig.kt`
- Modify: `src/main/kotlin/io/openeden/Main.kt`

- [ ] **Step 1: Write manager tests with injected process launcher and clock**

Test that a healthy server is reused without launching a process, an unavailable default local server launches `gradlew.bat :server:run`, readiness is polled until success, a remote URL is never auto-started, and timeout returns a clear failure. Use injectable `suspend healthCheck`, `launch`, `delay`, and `now` seams so tests never create a real server.

- [ ] **Step 2: Run manager tests and verify failure**

```powershell
.\gradlew.bat :test --tests "io.openeden.client.ServerProcessManagerTest" --no-daemon
```

- [ ] **Step 3: Implement process ownership rules**

Add `OPENEDEN_SERVER_URL` with default `http://127.0.0.1:8080`, a startup timeout and polling interval with conservative defaults, and local detection based on loopback host. Launch the Windows wrapper through `ProcessBuilder` with inherited environment and working directory, redirect output to the server log/console as appropriate, poll `/health`, and retain the `Process` reference only for diagnostics. Never call `destroy()` from normal CLI exit; the manager may close its HTTP client but must leave the server process alive.

- [ ] **Step 4: Refactor CLI config and remove kernel construction from the CLI path**

Change `Main.kt` so `OpenEdenCli` receives a server client/manager, not `RuntimeFactory`, `SessionStateStore`, `OpenEdenRuntimePipeline`, model loaders, or SQLDelight stores. The existing one-shot parser may remain temporarily as a compatibility adapter, but it must call HTTP APIs and must not instantiate the kernel. Load `.env` before reading environment-backed config using the existing `DotEnvLoader` path while preserving process environment precedence.

- [ ] **Step 5: Run manager and root tests**

Run the focused manager tests and `:test --no-daemon`.

- [ ] **Step 6: Commit server lifecycle management**

```powershell
git add src/main/kotlin/io/openeden/client/ServerProcessManager.kt src/test/kotlin/io/openeden/client/ServerProcessManagerTest.kt src/main/kotlin/io/openeden/config/LocalRuntimeConfig.kt src/main/kotlin/io/openeden/Main.kt
git commit -m "feat: manage reusable local server from cli"
```

### Task 5: Replace one-shot CLI behavior with a persistent REPL

**Files:**
- Modify: `src/main/kotlin/io/openeden/Main.kt`
- Modify: `src/test/kotlin/io/openeden/OpenEdenCliTest.kt`

- [ ] **Step 1: Write REPL behavior tests**

Inject input lines and a fake HTTP client/manager. Assert that two ordinary lines produce two chat calls, `/state` calls state, `/help` prints commands, `/exit` returns normally, blank input is ignored, and `/exit` does not call any shutdown method. Add a smoke test proving the same fake server client is reused across both turns.

- [ ] **Step 2: Run the REPL tests and verify failure**

```powershell
.\gradlew.bat :test --tests "io.openeden.OpenEdenCliTest" --no-daemon
```

- [ ] **Step 3: Implement the persistent loop without blocking Ktor or kernel dispatchers**

Create a `CliInput` abstraction whose real implementation reads stdin via `withContext(Dispatchers.IO)`. On startup, ensure the server is ready once, print a compact prompt, route normal text to `POST /api/v1/chat`, and print only the response text. Implement exactly `/state`, `/help`, and `/exit`; `/exit` closes the client and returns but does not stop the server. Keep output free of vectors, `evolutionIndex`, Ω internals, prompt previews, and trace tags.

- [ ] **Step 4: Preserve a clear compatibility path or remove obsolete flags deliberately**

Update command parsing/help so the documented entrypoint is `gradlew.bat run` for the REPL. If `chat --message` and `state` remain, implement them through the HTTP client and mark them as compatibility commands in help; do not retain direct pipeline construction just to support old tests.

- [ ] **Step 5: Run all CLI tests**

Run `:test --tests "io.openeden.OpenEdenCliTest" --no-daemon` and then the full root test task.

- [ ] **Step 6: Commit the REPL**

```powershell
git add src/main/kotlin/io/openeden/Main.kt src/test/kotlin/io/openeden/OpenEdenCliTest.kt
git commit -m "feat: make cli a persistent server-backed repl"
```

### Task 6: Documentation and end-to-end verification

**Files:**
- Modify: `README.md`
- Modify: `.env.example` only for missing server URL/startup settings
- Create: `docs/runtime-cli-server.md`

- [ ] **Step 1: Document the process model**

Explain that the server owns the kernel and remains alive after CLI `/exit`, local startup is automatic only for loopback URLs, remote URLs are connection-only, and `.env` is loaded by Kotlin configuration while `application.yaml` contains Ktor wiring only.

- [ ] **Step 2: Document commands and public API**

Show `gradlew.bat run`, `/state`, `/help`, `/exit`, `OPENEDEN_SERVER_URL`, and the three public endpoints. Explicitly state that vectors, `evolutionIndex`, prompts, traces, and retrieval internals are not part of the public chat response.

- [ ] **Step 3: Run the complete verification suite**

Run:

```powershell
$env:JAVA_HOME='F:\SDK\JDK21'
.\gradlew.bat :core:jvmTest :server:test :test --no-daemon
```

Then perform a local smoke test with a configured `.env`: start the CLI, send two messages, run `/state`, exit, verify the server process remains reachable at `/health`, and start a second CLI to verify it reuses the same server/database.

- [ ] **Step 4: Review the final diff for scope and invariants**

Confirm no user-owned Gradle wrapper changes, `.env`, unfinished dotenv files, or generated `META-INF/` content were staged unless explicitly required. Confirm the CLI has no imports of runtime/model/SQLDelight classes, all server API I/O is suspend-based, and no public response exposes internal evolution/vector/prompt data.

- [ ] **Step 5: Commit documentation and final verification**

```powershell
git add README.md .env.example docs/runtime-cli-server.md
git commit -m "docs: explain server-backed cli workflow"
```

## Spec Coverage Review

- Server-only kernel ownership: Tasks 2 and 4.
- Public `health`, `chat`, and `state` APIs: Tasks 1 and 2.
- Internal-field hiding and request/status/error contract: Tasks 1 and 3.
- Persistent REPL and `/state`, `/help`, `/exit`: Task 5.
- Local auto-start, readiness polling, remote connection-only behavior, and no auto-shutdown: Task 4.
- `.env` and `application.yaml` boundary: Tasks 2, 4, and 6.
- Durable state reuse and end-to-end verification: Tasks 2 and 6.
- Existing kernel invariants remain in `core`; this change does not move persona, vector math, VQ-VAE, or background workers into the CLI.
