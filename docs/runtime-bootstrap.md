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
