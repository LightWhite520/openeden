# OpenEden

[中文](README.zh-CN.md)

**An experimental Kotlin/Ktor runtime that carries an AI character's state and memories across conversations.**

OpenEden asks how past interactions can change an AI character's later responses, choices, and interpretation of its own experience.

The runtime preserves a shared internal state across turns and conversation scopes. That state affects which memories are retrieved and what context the model receives. Validated model output then updates the state for later turns. The project studies whether this feedback helps sustain behavioral continuity over time.

## How it works

One incarnation shares Bio state across conversations; each conversation keeps
its own transcript and delivery context. A turn prepares the state, maps it to
codebook semantics, retrieves memories, builds the prompt, validates the LLM's
response and proposed delta, and commits the result to SQLite.

- The eight stored dimensions are `[L, P, E, S, tau, V, M, F]`: logical clarity,
  emotional resonance, emotional self-acceptance, instability, memory pull,
  vitality, empathy, and fear. Values are in `[0,1]`.
- Dissonance is derived as `D = abs(L - tau) * (1 - E)`, not stored as a ninth dimension.
- VQ-VAE maps state to codebook descriptions. A logged heuristic fallback handles
  unavailable quantization. Prompts receive semantics rather than raw 8D floats.
- Retrieval combines text and emotional similarity. State writes are bounded
  and serialized under the incarnation lock.
- Persona voice and starting points live in `persona/*.yaml`. Growth defaults
  to PreCommand; TrueSelf and Awakened are explicit starting-point choices,
  not automatic promotions based on turn count.
- Background ticks change state; heartbeat turns use the full pipeline and
  deliver only to the configured owner.

These are engineered state variables. Long-term behavioral continuity and an
advantage over simpler prompt or RAG systems still require empirical evaluation.

## Run from source

Requires JDK 21. Run from the repository root:

```powershell
.\gradlew.bat ensureLocalModelArtifact
.\gradlew.bat ensureThymosAffectModel
$env:OPENEDEN_OPENAI_API_KEY="sk-..."
$env:OPENEDEN_OPENAI_MODEL="your-responses-compatible-model"
$env:OPENEDEN_OPENAI_BASE_URL="https://api.openai.com/v1"
.\gradlew.bat :server:run
```

The first Thymos download is approximately 1.2 GB. For ChatGPT subscription
access instead of an API key, use the [sign-in guide](docs/operations/chatgpt-and-model-selection.md).
`.env.example` lists configuration options; the server and CLI do not
automatically import `.env`.

In a second terminal:

```powershell
.\gradlew.bat :cli:installDist
.\cli\build\install\openeden\bin\openeden.bat
```

Server and CLI default to `http://127.0.0.1:8080`. Change the server port with
`OPENEDEN_SERVER_PORT`. The client reads its address from
`~/.openeden/config.json`; edit `serverUrl` there for a custom server.
The JVM CLI currently does not read `OPENEDEN_SERVER_URL`.

The CLI connects over HTTP and never starts or stops the server. Use the packaged
launcher for interactive JLine input. Redirected streams use UTF-8.

For npm packaging and launch, see [npm CLI instructions](npm/README.md).

## Guides

- [Runtime, CLI configuration, commands and HTTP API](docs/runtime-cli-server.md)
- [ChatGPT sign-in and model selection](docs/operations/chatgpt-and-model-selection.md)
- [NapCat / OneBot v11](docs/onebot-napcat.md)
- [Model training and DJL configuration](docs/local-model-training.md)
- [Conversation export and incarnation reset](docs/operations/companion-quality-production-rollout.md)

## Development

| Directory | Responsibility |
| --- | --- |
| `core` | State mechanics, pipeline, persona, retrieval and model contracts |
| `server` | Ktor server, persistence and runtime assembly |
| `cli` / `client` | Terminal interface and HTTP client |
| `onebot` | QQ / OneBot v11 adapter |
| `trainer` | Local model training |
| `persona` | Persona data |

```powershell
.\gradlew.bat build
.\scripts\verify-cli-unicode.ps1
```

Engineering constraints are in [AGENTS.md](AGENTS.md).
`docs/evaluation/live-ab-judge.md` and the JSON scenarios are inputs to the
A/B evaluation scripts, not user documentation.

## License

Code and public OpenEden model/codebook artifacts are licensed under
[GNU AGPL v3.0](LICENSE).
