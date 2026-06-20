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
