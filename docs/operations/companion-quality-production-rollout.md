# Companion Quality Production Rollout

This runbook covers the guarded export and incarnation reset used before production companion-quality testing. It applies only to OpenEden. Do not stop, inspect, bind, reconfigure, or alter Illusion Server. Port `8080` is reserved and must not be supplied to any OpenEden command.

## Deployed Configuration

The running OpenEden server, not the scripts, owns all mutation authority and configuration:

- `OPENEDEN_RUNTIME_DB_PATH` selects the SQLite database.
- `OPENEDEN_MAINTENANCE_EXPORT_ROOT` selects the only export tree the server accepts.
- `OPENEDEN_MAINTENANCE_TOKEN` enables the loopback maintenance API and is required as its bearer token.
- `OPENEDEN_VECTOR_DB_ENABLED`, `OPENEDEN_QDRANT_URL`, `OPENEDEN_QDRANT_COLLECTION`, and `OPENEDEN_EMBEDDING_MODEL_ID` determine projection erasure. Operators cannot disable or replace these settings in a reset request.

The maintenance API is hosted inside the live runtime and uses the exact `IncarnationTurnGate` owned by `VectorWriteService`. Standalone CLI reset is forbidden. Do not stop OpenEden before export or reset; a stopped process cannot provide the shared mutation boundary.

## Preconditions

1. Complete the Task 17 A/B release gate on a candidate database and retain its raw decisions and report.
2. Back up the configured SQLite database and deployed configuration to a separate access-controlled location.
3. Confirm OpenEden is running on its configured non-`8080` port with maintenance enabled.
4. Record the exact active incarnation ID, service root, export root, immutable persona mode/start point, and one stable reset request ID.
5. Confirm the requested export directory is a new direct child of both the service root and configured maintenance export root.

Both scripts first call the authenticated live readiness endpoint. They require schema version `23` or newer, exactly one active incarnation, an exact active-incarnation ID match, and an acceptable reset readiness state. The server independently canonicalizes paths and binds all data access to deployed configuration. A missing active ID accompanies an invalid zero/multiple-active diagnostic and must never be guessed by an operator.

## Export

```powershell
.\scripts\export-production-conversation.ps1 `
  -ServiceRoot 'D:\Services\openeden' `
  -ExportDirectory 'D:\Services\openeden\data\exports\before-quality-reset-20260829' `
  -IncarnationId '<exact-active-incarnation-id>' `
  -MaintenanceToken '<deployed-maintenance-token>' `
  -OpenEdenPort <configured-openeden-port>
```

The live server acquires the shared global incarnation mutation gate, takes an exact-incarnation SQLite snapshot, and writes deterministic JSONL files. The target must be a direct child of the server-configured export root. Production export opens that root and its unique unguessable staging directory through `SecureDirectoryStream`; payload creation, directory flushes, and the final rename are handle-relative with no-follow semantics. Each file and `manifest.json` is forced to storage before the relative rename is exposed.

The capability check is fail closed. If the deployed filesystem provider cannot supply `SecureDirectoryStream`, the server rejects export before writing payload data. The standard Windows JDK filesystem provider on the current verification host does not expose this capability, so Windows production export is unavailable until a reviewed native handle-relative implementation is supplied. Do not bypass this result with the test-only path boundary or a standalone process.

Store the completed export read-only. Do not edit its JSONL files or manifest.

## Reset Saga

```powershell
.\scripts\reset-production-incarnation.ps1 `
  -ServiceRoot 'D:\Services\openeden' `
  -ManifestPath 'D:\Services\openeden\data\exports\before-quality-reset-20260829\manifest.json' `
  -IncarnationId '<exact-active-incarnation-id>' `
  -RequestId 'quality-reset-20260829-01' `
  -PersonaMode 'growth' `
  -PersonaStart 'pre_command' `
  -MaintenanceToken '<deployed-maintenance-token>' `
  -OpenEdenPort <configured-openeden-port> `
  -ConfirmReset
```

The reset is a recoverable saga serialized by the same live global gate as turns, heartbeats, diary turn commits, and runtime ticks:

1. `PREPARED`: one SQLite transaction revalidates the exact active incarnation and exported payload, stores the normalized request fingerprint and stable fresh-incarnation ID, records every persisted projection model, and changes owned projection rows from `READY` to `RESET_RECOVERABLE` while preserving their prior state in recovery rows.
2. External erase: every collection derived from authoritative runtime configuration and persisted model metadata receives an exact incarnation/model filtered Qdrant delete with `wait=true`. An exact filtered count must then be zero for every collection.
3. `PROJECTIONS_VERIFIED`: successful external verification is committed durably. A later SQLite failure leaves this phase and recoverable projection rows; it never leaves `READY` rows pointing to absent points.
4. `COMPLETED`: one final SQLite transaction reconstructs and revalidates the exported payload, erases only rows owned by the old incarnation, retains other-incarnation rows and archives, creates exactly one neutral full-8D active incarnation with the selected immutable persona mode/start point, and records completion.

Request IDs are trimmed before lookup. A replay is accepted only when incarnation ID, durable manifest path/hash and payload hash, confirmation, persona mode, and starting point match the durable request. Conflicting reuse is rejected. A completed replay is returned from SQL before archive access, so moving a completed archive does not create another incarnation.

## Crash And Failure Recovery

- Failure before `PREPARED`: no reset saga exists and no projection state was changed. Correct the reported validation issue and retry.
- `PREPARED`: retry the exact command and request ID. Qdrant deletion is idempotent and will be issued and verified again.
- `PROJECTIONS_VERIFIED`: retry the exact command and request ID. The server skips repeated external deletion and retries only the final SQL transaction.
- Process crash: startup loads the configured database and Qdrant settings, resumes every incomplete saga under the shared gate before background schedulers or routes become available, and fails startup closed if recovery cannot complete. While diagnostics report `RESUME_REQUIRED`, new export/reset admission is rejected; only the exact durable saga request or an already completed replay is accepted.
- Ambiguous client result: query readiness, then rerun the exact reset request. Never invent a new request ID.
- Do not mark projection rows `READY`, edit saga phases, create an incarnation manually, or bypass manifest verification.

Qdrant and SQLite cannot form one cross-system ACID transaction. The durable phases, recoverable projection rows, idempotent external operation, exact verification, startup resume, and final SQL transaction are the supported equivalent atomic boundary.

## Verify

1. Query the authenticated live maintenance readiness endpoint and record its response. It must report schema `23+`, exactly one active incarnation with a nonblank matching ID, zero incomplete resets, and `resetReadiness=READY`.
2. Run `scripts/verify-production-runtime.ps1` on a non-`8080` verification port and retain its output. The verifier starts an isolated temporary OpenEden runtime; it does not export or reset deployed data.
3. Confirm the verifier reports its live maintenance schema, one active incarnation, reset readiness, Relay cache policy/capability state, diary persistence, restart continuity, and post-restart evolution.
4. Run the authorized production conversation scenario. Export transcript, Bio, relationship, memory-lineage, prompt-segment, and cache metrics without weakening any Task 17 threshold.
