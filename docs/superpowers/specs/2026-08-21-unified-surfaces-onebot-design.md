# Unified Surfaces and NapCat OneBot Adapter Design

Date: 2026-08-21
Status: Approved for implementation planning

## Purpose

OpenEden will run one authoritative runtime kernel in the `server` process. CLI, Web UI, and QQ are separate surfaces that submit messages to that kernel. QQ connectivity uses NapCat through OneBot v11 reverse WebSocket.

One OpenEden server instance represents one persona incarnation and one configured QQ bot. Operators who need independent bots run independent OpenEden instances with separate databases and configuration.

## Goals

- Make the repository root a Gradle aggregation project rather than an application module.
- Give `core`, `server`, `client`, `cli`, `webui`, and `onebot` distinct ownership boundaries.
- Keep one runtime kernel, persistence stack, heartbeat scheduler, and model stack in `server`.
- Accept NapCat OneBot v11 reverse WebSocket connections without Mirai.
- Route private and group QQ messages through the existing `DevelopmentMessagePipeline`.
- Preserve `platform:scopeId` session identity and individual sender metadata.
- Deliver heartbeat output only to the configured QQ owner and never replay stale output after reconnect.
- Keep all network and adapter operations coroutine-based and non-blocking.

## Non-Goals

- Direct QQ protocol login from OpenEden.
- Mirai or Mirai API HTTP integration.
- OneBot HTTP, forward WebSocket, or OneBot v12 support.
- Multiple independent QQ bots sharing one OpenEden runtime.
- Cross-platform session merging between CLI, Web UI, and QQ.
- Persona, prompt, vector, VQ-VAE, retrieval, or memory-policy changes.
- Implementing the Web UI itself in the OneBot delivery phase.

## Target Repository Layout

```text
openeden/
  build.gradle.kts          Gradle aggregation and shared build configuration
  settings.gradle.kts
  core/                     Runtime kernel
  server/                   Authoritative runtime host and HTTP API
  client/                   Transport-neutral OpenEden API client and DTOs
  cli/                      JVM terminal application
  webui/                    Browser application when implemented
  onebot/                   OneBot v11 reverse WebSocket adapter
  trainer/                  Model training utilities
```

The repository-root `src/` directory is removed after its contents are migrated:

- `src/.../io/openeden/cli` moves to `cli/src/.../io/openeden/cli`.
- `src/.../io/openeden/config` moves with the CLI because it stores CLI configuration.
- `src/.../io/openeden/client` moves to the existing `client` module.
- Corresponding tests move to the owning modules.
- The root `application` configuration moves to `cli/build.gradle.kts`.

## Dependency Direction

```text
cli ------> client
webui ----> client or the same public wire schema

server ---> core
server ---> onebot ---> core

trainer --> core where model contracts are required
```

Rules:

- `core` has no Ktor server, QQ, OneBot, browser, or terminal dependency.
- `onebot` has no dependency on `server`; `server` constructs and installs the adapter.
- `client` does not expose internal runtime state classes as public wire contracts.
- `cli` and `webui` never instantiate `DevelopmentMessagePipeline`.
- `server` is the only production owner of runtime startup and shutdown.

## Runtime Ownership

`server` remains the composition root. Startup creates the durable stores, inference executor, VQ-VAE quantizer, memory system, `DevelopmentMessagePipeline`, heartbeat scheduler, tick scheduler, and adapter infrastructure exactly once.

```text
server startup
  -> load configuration and persona data
  -> start stores, models, and inference dispatcher
  -> construct DevelopmentMessagePipeline
  -> construct OneBot connection registry and action sender
  -> start heartbeat and tick schedulers
  -> install HTTP, Web UI, and OneBot routes
```

Surface adapters only translate transport data. They must not build prompts, interpret persona, calculate vector state, select retrieval modes, or bypass quantization.

## Surface Session Identity

Every request continues to use `sessionId = "${platform}:${scopeId}"`.

| Surface | Platform | Scope ID | Sender metadata |
|---|---|---|---|
| CLI | `CLI` | configured local user ID | local user ID |
| Web UI | `WEB` | authenticated/local web user ID | web user ID |
| QQ private | `QQ` | OneBot `user_id` | OneBot `user_id` |
| QQ group | `QQ` | OneBot `group_id` | OneBot `user_id` |

All users in one QQ group therefore share `QQ:<group_id>`, while memory and trace metadata retain the individual sender `user_id`. CLI, Web UI, and QQ sessions are intentionally separate unless a future design adds explicit cross-platform identity linking.

## OneBot Module Boundary

The `onebot` module owns:

```text
io.openeden.onebot.config       Adapter configuration models and validation
io.openeden.onebot.protocol     Serializable OneBot v11 events, actions, and responses
io.openeden.onebot.connection   Active socket registration and connection epochs
io.openeden.onebot.ingress      Event parsing, filtering, and pipeline mapping
io.openeden.onebot.egress       Action correlation, timeout, and bounded retry
io.openeden.onebot.heartbeat    HeartbeatDelivery implementation
io.openeden.onebot.route        Ktor reverse WebSocket route installation
```

Production files remain focused, with one reusable public top-level type per Kotlin file.

The adapter accepts a narrow message-handler function from `server` that delegates to `DevelopmentMessagePipeline.handle`. This keeps tests independent from runtime construction without introducing a second pipeline abstraction in `core`.

## Reverse WebSocket Lifecycle

NapCat is configured with OpenEden's reverse WebSocket URL:

```text
ws://127.0.0.1:8080/onebot/v11
wss://eden.example.com/onebot/v11
```

The route path is configurable. On connection:

1. Validate `Authorization: Bearer <token>` before accepting adapter traffic.
2. Read and validate NapCat's `X-Self-ID` header against the configured bot self ID.
3. Register the socket with a monotonically increasing connection epoch.
4. Replace an older connection for the same configured bot and close the stale socket.
5. Reject connections for a different bot self ID.
6. On disconnect, remove the socket only if its epoch is still active and fail all pending actions for that epoch.

The registry represents one configured bot, not a multi-bot tenant registry. The self ID protects routing and reconnect correctness; it does not become part of `sessionId`.

No message or action is stored for replay after disconnect. A response whose pipeline work completes after its originating connection is gone is dropped.

## Inbound Processing

The socket reader distinguishes OneBot events from action responses using structured JSON fields. It never parses protocol frames with ad hoc string matching.

Supported events:

- `post_type=message`, `message_type=private`.
- `post_type=message`, `message_type=group`.
- String messages and array-form text segments.

Ignored inputs:

- Events whose `self_id` does not match the configured bot.
- Messages sent by the bot itself.
- Unsupported post types and message types.
- Empty messages and payloads without usable text.
- Malformed JSON and binary WebSocket frames.

Private messages are always eligible. Group activation is configurable with `MENTION_ONLY`, `ALL`, and `DISABLED`, defaulting to `MENTION_ONLY`. This is transport policy, not persona behavior. In mention-only mode, the adapter removes the bot mention before submitting non-empty text.

Mapping:

```text
private -> platform=QQ, scopeId=user_id,  userId=user_id
group   -> platform=QQ, scopeId=group_id, userId=user_id
```

The turn ID is derived from the configured bot self ID plus OneBot `message_id`, producing a stable idempotency key. Missing or invalid message IDs are rejected rather than assigned unstable replay-sensitive IDs.

The reader keeps action responses responsive while LLM turns are running. Parsed message events enter a bounded adapter work queue processed by a configurable number of coroutine workers. Queue overflow is dropped and logged with `onebot=QUEUE_OVERFLOW`; no platform callback thread is blocked.

## Outbound Actions

Private replies use `send_private_msg`; group replies use `send_group_msg`. Messages are emitted as OneBot text segments.

Each action contains a unique `echo`. Pending actions are held only in memory for the active connection epoch. The receive loop completes the corresponding deferred result when NapCat returns an action response.

Action behavior:

- Serialize WebSocket frame writes with a coroutine `Mutex`.
- Await action results with `withTimeout`, never blocking a thread.
- Retry only a configured small number of times and only within the same active connection epoch.
- Do not retry schema or authorization failures.
- Do not carry retries onto a reconnected socket.
- Drop null, blank, or runtime-rejected responses.

## Heartbeat Delivery

`OneBotHeartbeatDelivery` implements the existing `HeartbeatDelivery` boundary. It reports connected only when:

- the configured target platform is exactly `QQ`;
- the active NapCat socket matches the configured bot;
- the active socket epoch remains open.

Heartbeat delivery always uses `send_private_msg` to the configured owner user ID. It does not infer ownership from recent activity, group membership, host relationship metadata, or the active QQ session.

Heartbeat output is generated and committed before delivery, as it is today. If the owner is absent, the adapter is disconnected, the epoch changes, or sending fails, outward delivery is dropped. No retry may cross a reconnect and no heartbeat queue is introduced.

## Configuration

The server loads OneBot configuration and passes a validated value object into the adapter:

```yaml
openeden:
  onebot:
    enabled: "$OPENEDEN_ONEBOT_ENABLED:false"
    path: "$OPENEDEN_ONEBOT_PATH:/onebot/v11"
    accessToken: "$?OPENEDEN_ONEBOT_ACCESS_TOKEN:"
    botSelfId: "$?OPENEDEN_ONEBOT_SELF_ID:"
    groupPolicy: "$OPENEDEN_ONEBOT_GROUP_POLICY:MENTION_ONLY"
    eventQueueCapacity: "$OPENEDEN_ONEBOT_EVENT_QUEUE_CAPACITY:64"
    eventWorkers: "$OPENEDEN_ONEBOT_EVENT_WORKERS:4"
    actionTimeoutMs: "$OPENEDEN_ONEBOT_ACTION_TIMEOUT_MS:10000"
    maxActionRetries: "$OPENEDEN_ONEBOT_MAX_ACTION_RETRIES:2"
```

When OneBot is enabled, access token and bot self ID are mandatory. Invalid paths, queue sizes, worker counts, timeouts, retry counts, partial heartbeat owner configuration, and non-QQ OneBot owner configuration fail startup with actionable errors.

Deployment location is not encoded in OpenEden. Operators configure NapCat with the URL appropriate for localhost, LAN, Docker DNS, or a TLS reverse proxy.

## Concurrency and Failure Handling

- Ktor socket reads, pipeline calls, action waits, retries, and shutdown are suspendable coroutine operations.
- No `runBlocking`, `Thread.sleep`, blocking queue, or synchronous network client is permitted.
- Message events from different sessions may run concurrently; the existing per-session pipeline gate serializes turns for one `platform:scopeId`.
- The existing per-session vector mutex remains the only vector-write authority.
- Adapter cancellation never bypasses pipeline finalization or invents a separate state write.
- One malformed event, action failure, or worker failure does not terminate the socket reader or sibling workers.
- Application shutdown closes the active socket, cancels adapter workers, fails pending action deferreds, then releases runtime resources.

## Observability

Adapter logs include bot self ID, connection epoch, message type, scope ID, sender ID, OneBot message ID, action echo, and failure category where applicable. Access tokens and message content are not logged.

Stable tags include:

```text
onebot=CONNECTED
onebot=DISCONNECTED
onebot=AUTH_REJECTED
onebot=EVENT_IGNORED
onebot=MALFORMED_EVENT
onebot=QUEUE_OVERFLOW
onebot=ACTION_TIMEOUT
onebot=ACTION_FAILED
onebot=HEARTBEAT_DROPPED
```

## Testing

### Module migration

- Existing CLI, client, and compatibility tests pass from their new modules.
- The root project has no production source set or application entry point.
- Dependency checks confirm CLI and Web UI clients cannot construct the runtime kernel.

### OneBot unit tests

- Parse private and group message events from string and segment-array payloads.
- Resolve group and private scope IDs exactly.
- Preserve sender user ID independently of group scope.
- Filter self messages, unsupported events, empty text, and malformed payloads.
- Enforce mention-only, all, and disabled group policies.
- Encode private and group send actions with stable `echo` values.
- Correlate success, failure, and timeout action responses.

### OneBot route and lifecycle tests

- Reject missing or incorrect bearer tokens.
- Reject mismatched bot self IDs.
- Replace a stale connection for the configured bot.
- Clear only the matching connection epoch on disconnect.
- Do not replay ordinary or heartbeat output after reconnect.
- Bound event work and report queue overflow.

### Server integration tests

- A QQ private event reaches the shared pipeline as `QQ:<user_id>`.
- Users in one group share `QQ:<group_id>` while retaining individual sender metadata.
- A validated runtime response emits the correct OneBot action.
- Runtime rejection emits no QQ message.
- Heartbeats deliver only to the configured QQ owner and drop while disconnected.

## Architecture Compliance

- Persona remains entirely in persona data; OneBot contains no tone or character behavior.
- Every QQ turn uses the existing pipeline and therefore the existing VQ-VAE or traced heuristic fallback path.
- The adapter neither stores nor interprets 8D vectors or derived dissonance.
- All inference and vector operations remain on the existing inference executor.
- All vector writes and `evolution_index` changes remain under existing session serialization.
- Heartbeat delivery remains owner-only, immediate, and non-replaying.

## Acceptance Criteria

- The root project is an aggregator, with CLI and client code in focused modules.
- `server` starts exactly one runtime kernel and one optional NapCat adapter.
- NapCat can connect through authenticated OneBot v11 reverse WebSocket.
- QQ private and group messages route through the shared runtime pipeline with correct session and sender identity.
- Replies return through the same active connection epoch.
- Disconnect and reconnect never replay stale messages or heartbeats.
- One OpenEden instance accepts only its configured QQ bot.
- CLI and HTTP behavior remain compatible after module migration.
- Focused OneBot, server, client, CLI, and core test suites pass.
