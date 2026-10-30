# Conversation export and incarnation reset

These scripts call the running server's authenticated loopback maintenance API.
They do not open or reset a database independently. Back up the deployed SQLite
database and configuration before resetting an incarnation.

## Configuration

Set `OPENEDEN_MAINTENANCE_TOKEN` and `OPENEDEN_MAINTENANCE_EXPORT_ROOT` on the
server. Database and Qdrant settings come from the deployed configuration.
The maintenance scripts currently reject port `8080`; for these commands, run
the intended OpenEden instance on another explicitly configured port.

Readiness must report schema version `23` or newer, one active incarnation,
and a matching incarnation ID. Use the returned ID rather than guessing one.
Export requires `secureExportDirectoryHandlesAvailable=true`.

The destination must be a new direct child of the configured export root.
Unix uses `SecureDirectoryStream`; Windows uses pinned native directory handles
and rejects reparse points. Unsupported filesystems fail closed.

## Export

Substitute the service paths, active ID, token and port for your deployment:

```powershell
.\scripts\export-production-conversation.ps1 `
  -ServiceRoot '<service-root>' `
  -ExportDirectory '<export-root>\before-reset' `
  -IncarnationId '<active-incarnation-id>' `
  -MaintenanceToken '<maintenance-token>' `
  -OpenEdenPort <port>
```

Keep the completed export and its manifest unchanged. The manifest binds the
payload used to validate a reset.

## Reset

Choose a stable request ID and an explicit persona starting point:

```powershell
.\scripts\reset-production-incarnation.ps1 `
  -ServiceRoot '<service-root>' `
  -ManifestPath '<export-root>\before-reset\manifest.json' `
  -IncarnationId '<active-incarnation-id>' `
  -RequestId '<stable-request-id>' `
  -PersonaMode growth `
  -PersonaStart pre_command `
  -MaintenanceToken '<maintenance-token>' `
  -OpenEdenPort <port> `
  -ConfirmReset
```

Export and reset share the live incarnation mutation gate with turns and
background workers. Reset records durable phases: `PREPARED`,
`PROJECTIONS_VERIFIED`, then `COMPLETED`. Qdrant erasure is verified before the
final SQLite transaction creates the replacement incarnation.

If the command fails or its result is ambiguous, query readiness and retry the
exact request with the same ID and arguments. Do not manually edit saga phases
or projection rows. Startup resumes incomplete resets before accepting turns.
After completion, readiness should show one active incarnation, no incomplete
resets, and `resetReadiness=READY`.
