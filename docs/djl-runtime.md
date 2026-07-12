# DJL Runtime Configuration

The JVM runtime keeps the platform-independent model ports in `core` and loads
DJL predictors only when `OPENEDEN_MODEL_BACKEND=djl`.

The production default is DJL and uses the checked-in first-version export.
The paths below are the explicit equivalent configuration:

```text
OPENEDEN_MODEL_BACKEND=djl
OPENEDEN_LOCAL_MODEL_ARTIFACT=data/models/local-model-artifact.json
OPENEDEN_DJL_VQVAE_MODEL_PATH=path/to/vqvae-model
OPENEDEN_DJL_TEXT_MODEL_PATH=path/to/text-embedding-model
OPENEDEN_DJL_EMOTIONAL_MODEL_PATH=path/to/emotional-embedding-model
OPENEDEN_DJL_ENGINE=PyTorch
OPENEDEN_DJL_MODEL_NAME=model
```

The selected DJL engine and its native runtime must be present on the JVM
runtime classpath. Startup fails fast when the artifact or any configured model
path is missing. `OPENEDEN_MODEL_BACKEND=artifact` explicitly selects the
existing local JSON artifact; test and development callers may still inject the heuristic and
deterministic adapters explicitly.

To export the existing JSON MLP weights into DJL TorchScript directories:

```powershell
& .venv-gpu/Scripts/python.exe scripts/export-local-artifact-djl.py `
  --artifact data/models/local-model-artifact.json `
  --output data/models/djl
```

The exporter creates `vqvae`, `text`, and `emotional` directories, each with a
`model.pt`. The repository includes the small deterministic export under
`data/models/djl` for JVM smoke tests. The DJL engine/native dependency must
still match the model format and host platform.

All predictor calls are suspended behind the runtime's inference dispatcher and
each predictor is closed with its DJL model during shutdown.
