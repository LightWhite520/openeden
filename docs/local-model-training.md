# Local Model Training

OpenEden local model training has two stages:

1. A deterministic artifact stage that converts labeled 8D samples into the
   local runtime artifact.
2. An optional GPU base-model alignment stage that fine-tunes a text embedding
   base model against the bilingual 8D/text pairs.

Neither stage encodes persona behavior in Kotlin. Training data contains state
semantics only; derived D remains runtime-computed and is never stored.

## Deterministic Runtime Artifact

Run:

```powershell
.\gradlew.bat :trainer:trainLocalModel
```

Default inputs and outputs:

- input samples: `data/training/codebook.samples.json`
- model artifact: `data/models/local-model-artifact.json`
- generated codebook CSV: `data/codebook/codebook.generated.csv`
- report: `build/reports/openeden-training-report.txt`

Training samples contain `nodeId`, semantic `definition`, optional
`definitionEn`/`definitionZh`, `tags`, and the stored 8D `vector`. They must not
contain derived D; D remains runtime-computed.

If the local runtime artifact is missing, Gradle can restore it from the public
OpenEden Hugging Face model repository:

```powershell
.\gradlew.bat ensureLocalModelArtifact
```

By default this downloads
`https://huggingface.co/0x4C57/openeden-codebook-base-model/resolve/main/local-model-artifact.json`.
Set `OPENEDEN_LOCAL_MODEL_ARTIFACT` to change the destination path, or
`OPENEDEN_LOCAL_MODEL_ARTIFACT_URL` to change the source URL.

`data/codebook/codebook.generated.csv` uses bilingual columns:

```csv
node_id,definition_en,definition_zh,tags
```

The runtime dictionary accepts both this format and the older
`node_id,definition,tags` format.

## GPU Base-Model Alignment

Use this when a GPU is available and you want a real base model trained on the
8D/text pairs before downstream artifact generation.

The current published model repository is:

```text
https://huggingface.co/0x4C57/openeden-codebook-base-model
```

The repository is public and AGPL-3.0 licensed.

Recommended starting point:

- default: `Qwen/Qwen3-Embedding-0.6B`
- larger GPU: `Qwen/Qwen3-Embedding-4B`
- high-memory GPU: `Qwen/Qwen3-Embedding-8B`

Install Python dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install torch --index-url https://download.pytorch.org/whl/cu126
pip install -r trainer/base-model-requirements.txt
```

Train:

```powershell
python scripts/train-codebook-base-model.py `
  --samples data/training/codebook.samples.json `
  --base-model Qwen/Qwen3-Embedding-0.6B `
  --output data/models/codebook-base-model `
  --epochs 3 `
  --batch-size 16
```

If VRAM is tight, add `--freeze-text-encoder` to train only the 8D vector
projector against the base text encoder.

For effect-first training on a 12GB RTX 4070 Ti, the tested stronger setting is
an unfrozen encoder with a larger contrastive batch:

```powershell
python scripts/train-codebook-base-model.py `
  --samples data/training/codebook.samples.json `
  --base-model Qwen/Qwen3-Embedding-0.6B `
  --output data/models/codebook-base-model `
  --epochs 5 `
  --batch-size 16
```

Evaluate the trained model:

```powershell
python scripts/evaluate-codebook-base-model.py `
  --samples data/training/codebook.samples.json `
  --model-dir data/models/codebook-base-model `
  --batch-size 64
```

To use the generated artifact in the local CLI runtime:

```powershell
$env:OPENEDEN_LOCAL_MODEL_ARTIFACT="data/models/local-model-artifact.json"
```

If no artifact is configured, or a quantization path fails at runtime, OpenEden
continues through the deterministic `codebook=HEURISTIC_FALLBACK` path.
