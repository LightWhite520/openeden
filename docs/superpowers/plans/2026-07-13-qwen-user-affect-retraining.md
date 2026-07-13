# Qwen User Affect Retraining Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the character-hash affect model with a CUDA-trained BF16 `Qwen/Qwen3-Embedding-0.6B` model and run it through the isolated JVM inference path while preserving the existing six-dimensional affect contract and deterministic fallback.

**Architecture:** Training is split into an importable Python module and a thin CLI. The exported bundle contains a fixed-length TorchScript model, Hugging Face tokenizer files, and validated metadata; Kotlin tokenizes text and invokes the model behind `UserAffectAnalyzer`, while `MessagePipeline` continues to schedule analysis through `InferenceExecutor`. Heavy local weights are ignored by Git, while reproducible code, metrics, and metadata remain reviewable.

**Tech Stack:** Python 3.12, PyTorch, sentence-transformers, Transformers, CUDA/BF16, DJL 0.34 PyTorch engine, DJL Hugging Face tokenizers, Kotlin coroutines, Gradle/JUnit.

---

## File Map

- Create `scripts/user_affect_training.py`: corpus validation, deterministic split, Qwen model, CUDA training, evaluation, export, and metadata.
- Modify `scripts/train-user-affect-model.py`: argument parsing and delegation to the importable training module.
- Create `scripts/tests/test_user_affect_training.py`: CPU-safe unit tests for validation, splitting, CUDA gate, and metadata.
- Create `core/src/jvmMain/kotlin/io/openeden/relationship/TextAffectPredictor.kt`: string-to-six-float predictor boundary.
- Create `core/src/jvmMain/kotlin/io/openeden/relationship/DjlQwenTextAffectPredictor.kt`: tokenizer and TorchScript integration.
- Modify `core/src/jvmMain/kotlin/io/openeden/relationship/DjlTextAffectAnalyzer.kt`: consume text predictors and preserve fallback semantics.
- Modify `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt`: predictor contract, fallback, and real-bundle smoke coverage.
- Create `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerBenchmarkTest.kt`: opt-in cold-start and warm CPU benchmark.
- Modify `gradle/libs.versions.toml` and `core/build.gradle.kts`: add DJL Hugging Face tokenizer support.
- Modify `server/src/main/kotlin/Runtime.kt`: load Qwen bundle metadata and analyzer.
- Modify `server/src/test/kotlin/RuntimeModelLoadingTest.kt`: validate bundle selection and incompatible metadata failure.
- Modify `.gitignore`: exclude heavyweight Qwen affect checkpoints and bundle weights.
- Create `data/evaluation/user-affect.challenge.jsonl`: tracked Chinese negation, sarcasm, mixed-affect, slang, and ambiguity cases.
- Create `data/models/user-affect-qwen/metrics.json`: tracked final quality and hardware report only.
- Modify `README.md` and `README.zh-CN.md`: document local bundle preparation and runtime variables.

### Task 1: Extract Testable Training Primitives

**Files:**
- Create: `scripts/user_affect_training.py`
- Create: `scripts/tests/test_user_affect_training.py`
- Modify: `scripts/train-user-affect-model.py`

- [ ] **Step 1: Write failing corpus and split tests**

```python
class TrainingDataTest(unittest.TestCase):
    def test_split_is_deterministic_and_disjoint(self):
        records = [record(f"sample-{index:04d}") for index in range(100)]
        first = split_records(records, seed=7)
        second = split_records(list(reversed(records)), seed=7)
        self.assertEqual([r["sampleId"] for r in first.train], [r["sampleId"] for r in second.train])
        ids = [{r["sampleId"] for r in part} for part in (first.train, first.validation, first.test)]
        self.assertFalse(ids[0] & ids[1] or ids[0] & ids[2] or ids[1] & ids[2])

    def test_rejects_duplicate_sample_id(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            validate_records([record("same"), record("same")], minimum_samples=2)
```

- [ ] **Step 2: Run the tests and verify the missing module failure**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Expected: FAIL because `scripts.user_affect_training` does not exist.

- [ ] **Step 3: Implement validation and deterministic partitioning**

```python
LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")

@dataclass(frozen=True)
class Partitions:
    train: list[dict]
    validation: list[dict]
    test: list[dict]

def validate_records(records: list[dict], minimum_samples: int = 512) -> list[dict]:
    seen: set[str] = set()
    for item in records:
        sample_id = str(item.get("sampleId", ""))
        if not sample_id or sample_id in seen:
            raise ValueError(f"Duplicate or missing sampleId: {sample_id}")
        if not str(item.get("text", "")).strip():
            raise ValueError(f"Empty text: {sample_id}")
        values = [float(item[label]) for label in LABELS]
        if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in values):
            raise ValueError(f"Invalid labels: {sample_id}")
        seen.add(sample_id)
    if len(records) < minimum_samples:
        raise ValueError(f"Need at least {minimum_samples} affect training samples")
    return records

def split_records(records: list[dict], seed: int) -> Partitions:
    ordered = sorted(records, key=lambda item: item["sampleId"])
    random.Random(seed).shuffle(ordered)
    test_end = max(1, int(len(ordered) * 0.10))
    validation_end = test_end + max(1, int(len(ordered) * 0.10))
    return Partitions(ordered[validation_end:], ordered[test_end:validation_end], ordered[:test_end])
```

- [ ] **Step 4: Reduce the CLI to argument parsing plus `train(config)` delegation**

```python
def main() -> None:
    args = parse_args()
    report = train(TrainingConfig.from_args(args))
    print(json.dumps(report, indent=2, ensure_ascii=False))
```

- [ ] **Step 5: Run unit tests**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Expected: all training-data tests PASS without loading Qwen or CUDA.

- [ ] **Step 6: Commit**

```powershell
git add scripts/user_affect_training.py scripts/train-user-affect-model.py scripts/tests/test_user_affect_training.py
git commit -m "refactor: extract affect training primitives"
```

### Task 2: Implement CUDA-Only BF16 Qwen Fine-Tuning

**Files:**
- Modify: `scripts/user_affect_training.py`
- Modify: `scripts/tests/test_user_affect_training.py`

- [ ] **Step 1: Write failing CUDA-gate and model-configuration tests**

```python
def test_require_cuda_rejects_unavailable_device(self):
    with mock.patch("torch.cuda.is_available", return_value=False):
        with self.assertRaisesRegex(RuntimeError, "CUDA is required"):
            require_cuda()

def test_default_config_matches_codebook_base_model(self):
    config = TrainingConfig()
    self.assertEqual("Qwen/Qwen3-Embedding-0.6B", config.base_model)
    self.assertEqual(torch.bfloat16, config.weight_dtype)
    self.assertFalse(config.freeze_text_encoder)
```

- [ ] **Step 2: Run tests and verify failure**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Expected: FAIL because `require_cuda` and the Qwen training configuration are absent.

- [ ] **Step 3: Implement the CUDA gate and model head**

```python
def require_cuda() -> torch.device:
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is required for Qwen affect training; CPU fallback is disabled")
    return torch.device("cuda")

class AffectHead(nn.Module):
    def __init__(self, embedding_dim: int) -> None:
        super().__init__()
        self.network = nn.Sequential(
            nn.Linear(embedding_dim, 256),
            nn.GELU(),
            nn.Dropout(0.10),
            nn.Linear(256, 6),
            nn.Sigmoid(),
        )

    def forward(self, embedding: torch.Tensor) -> torch.Tensor:
        return self.network(embedding)
```

- [ ] **Step 4: Implement full-encoder BF16 training with memory controls**

```python
encoder = SentenceTransformer(config.base_model, device=str(device), model_kwargs={"torch_dtype": torch.bfloat16})
encoder.max_seq_length = config.max_seq_length
encoder[0].auto_model.gradient_checkpointing_enable()
for parameter in encoder.parameters():
    parameter.requires_grad = True

optimizer = torch.optim.AdamW([
    {"params": encoder.parameters(), "lr": config.encoder_learning_rate},
    {"params": head.parameters(), "lr": config.head_learning_rate},
], weight_decay=config.weight_decay)
```

Each physical batch runs under `torch.autocast("cuda", dtype=torch.bfloat16)`, divides loss by `gradient_accumulation_steps`, clips gradients before each optimizer step, and selects the checkpoint with lowest validation MAE. CUDA OOM is rethrown with the physical batch size and recommended retry value; it must not alter the effective batch silently.

- [ ] **Step 5: Record reproducibility and GPU telemetry**

```python
hardware = {
    "device": torch.cuda.get_device_name(device),
    "cudaVersion": torch.version.cuda,
    "weightDtype": "bfloat16",
    "peakAllocatedBytes": torch.cuda.max_memory_allocated(device),
}
```

- [ ] **Step 6: Run CPU-safe tests and a one-batch CUDA smoke test**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Run: `python scripts/train-user-affect-model.py --corpus data/training/user-affect.raw.jsonl --output build/affect-qwen-smoke --epochs 1 --max-steps 1 --physical-batch-size 1 --gradient-accumulation-steps 1`

Expected: tests PASS; smoke output reports `NVIDIA GeForce RTX 4070 Ti`, `bfloat16`, and one completed optimizer step.

- [ ] **Step 7: Commit**

```powershell
git add scripts/user_affect_training.py scripts/tests/test_user_affect_training.py scripts/train-user-affect-model.py
git commit -m "feat: train user affect with Qwen on CUDA"
```

### Task 3: Export a Versioned Qwen Affect Bundle

**Files:**
- Modify: `scripts/user_affect_training.py`
- Modify: `scripts/tests/test_user_affect_training.py`
- Modify: `.gitignore`

- [ ] **Step 1: Write failing bundle metadata tests**

```python
def test_bundle_metadata_is_runtime_complete(self):
    metadata = bundle_metadata(max_sequence_length=192)
    self.assertEqual(2, metadata["schemaVersion"])
    self.assertEqual("Qwen/Qwen3-Embedding-0.6B", metadata["baseModel"])
    self.assertEqual("bfloat16", metadata["precision"])
    self.assertEqual(1024, metadata["embeddingDimension"])
    self.assertEqual(list(LABELS), metadata["outputs"])
    self.assertEqual("model.pt", metadata["modelFile"])
    self.assertEqual("tokenizer.json", metadata["tokenizerFile"])
```

- [ ] **Step 2: Run test and verify failure**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Expected: FAIL because schema-v2 bundle export is absent.

- [ ] **Step 3: Implement a fixed-input TorchScript wrapper**

```python
class ExportedAffectModel(nn.Module):
    def __init__(self, encoder: SentenceTransformer, head: AffectHead) -> None:
        super().__init__()
        self.encoder = encoder
        self.head = head

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        features = {"input_ids": input_ids, "attention_mask": attention_mask}
        embedding = self.encoder(features)["sentence_embedding"]
        return self.head(embedding.float())
```

Export with two `[1, max_sequence_length]` `int64` tensors, reload the saved model, and compare its six outputs against the eager model within `atol=1e-3`. Copy `tokenizer.json` and tokenizer configuration from the trained encoder directory into the bundle.

- [ ] **Step 4: Add heavyweight artifact ignores**

```gitignore
data/models/user-affect-qwen/model.pt
data/models/user-affect-qwen/checkpoints/
data/models/user-affect-qwen/text_encoder/
build/affect-qwen-*/
```

- [ ] **Step 5: Run export tests and inspect bundle shape**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Run: `python scripts/train-user-affect-model.py --corpus data/training/user-affect.raw.jsonl --output build/affect-qwen-export-smoke --epochs 1 --max-steps 1 --physical-batch-size 1 --export`

Expected: `model.pt`, `tokenizer.json`, `metadata.json`, and `metrics.json` exist; eager and TorchScript output comparison passes.

- [ ] **Step 6: Commit**

```powershell
git add scripts/user_affect_training.py scripts/tests/test_user_affect_training.py .gitignore
git commit -m "feat: export versioned Qwen affect bundle"
```

### Task 4: Add JVM Tokenizer and Qwen Predictor

**Files:**
- Create: `core/src/jvmMain/kotlin/io/openeden/relationship/TextAffectPredictor.kt`
- Create: `core/src/jvmMain/kotlin/io/openeden/relationship/DjlQwenTextAffectPredictor.kt`
- Modify: `core/src/jvmMain/kotlin/io/openeden/relationship/DjlTextAffectAnalyzer.kt`
- Modify: `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`

- [ ] **Step 1: Write failing text-predictor analyzer tests**

```kotlin
@Test
fun `text predictor receives original text and maps six outputs`() = runTest {
    var received = ""
    val analyzer = DjlTextAffectAnalyzer(object : TextAffectPredictor {
        override fun predict(text: String): FloatArray {
            received = text
            return floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.9f)
        }
        override fun close() = Unit
    })
    val state = analyzer.analyze("别担心，我只是有点累")
    assertEquals("别担心，我只是有点累", received)
    assertEquals(0.9f, state.confidence)
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :core:jvmTest --tests io.openeden.relationship.DjlTextAffectAnalyzerTest`

Expected: compilation FAIL because `TextAffectPredictor` does not exist.

- [ ] **Step 3: Add the predictor boundary and preserve fallback validation**

```kotlin
interface TextAffectPredictor : AutoCloseable {
    fun predict(text: String): FloatArray
}

class DjlTextAffectAnalyzer(
    private val predictor: TextAffectPredictor,
    private val fallback: UserAffectAnalyzer = DeterministicUserAffectAnalyzer(),
) : UserAffectAnalyzer, AutoCloseable {
    private val mutex = Mutex()
    override suspend fun analyze(text: String): UserAffectState = mutex.withLock {
        runCatching { predictor.predict(text).toUserAffectState() }
            .getOrElse { fallback.analyze(text) }
    }
    override fun close() = predictor.close()
}
```

- [ ] **Step 4: Add DJL tokenizer dependency**

```toml
djl-huggingface-tokenizers = { module = "ai.djl.huggingface:tokenizers", version.ref = "djl" }
```

```kotlin
implementation(libs.djl.huggingface.tokenizers)
```

- [ ] **Step 5: Implement fixed-length tokenizer and NDList translation**

`DjlQwenTextAffectPredictor` loads `tokenizer.json`, pads or truncates to metadata `maxSequenceLength`, creates `input_ids` and `attention_mask` as `int64` NDArrays, invokes the serialized predictor, and requires exactly six finite floats. `close()` closes predictor, model, and tokenizer resources. The class exposes `fromBundle(path, engineName)` and validates schema version, precision, output names, and required files before loading weights.

- [ ] **Step 6: Run focused JVM tests**

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :core:jvmTest --tests io.openeden.relationship.DjlTextAffectAnalyzerTest`

Expected: unit tests PASS; real-bundle smoke test is skipped only when the ignored local bundle is absent.

- [ ] **Step 7: Commit**

```powershell
git add gradle/libs.versions.toml core/build.gradle.kts core/src/jvmMain/kotlin/io/openeden/relationship core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt
git commit -m "feat: run Qwen affect inference through DJL"
```

### Task 5: Wire Bundle Loading Into the Server Runtime

**Files:**
- Modify: `server/src/main/kotlin/Runtime.kt`
- Create: `server/src/test/kotlin/RuntimeModelLoadingTest.kt`
- Modify: `.env.example`
- Modify: `README.md`
- Modify: `README.zh-CN.md`

- [ ] **Step 1: Write failing runtime model-selection tests**

```kotlin
@Test
fun `djl backend loads qwen affect bundle independently of legacy text bucket size`() {
    val config = runtimeConfig(djlAffectModelPath = validQwenBundle)
    loadRuntimeModels(config).use { models ->
        assertTrue(models.userAffectAnalyzer is DjlTextAffectAnalyzer)
    }
}

@Test
fun `incompatible affect metadata fails startup`() {
    val config = runtimeConfig(djlAffectModelPath = bundleWithSchemaVersion(999))
    assertFailsWith<IllegalArgumentException> { loadRuntimeModels(config) }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :server:test --tests RuntimeModelLoadingTest`

Expected: FAIL because runtime still passes the legacy 32-bucket input dimension.

- [ ] **Step 3: Load the Qwen bundle without changing pipeline contracts**

```kotlin
val affect = DjlTextAffectAnalyzer.fromBundle(
    bundlePath = config.djlAffectModelPath,
    engineName = config.djlEngine,
)
```

Keep `userAffectAnalyzer = models.userAffectAnalyzer` in `DevelopmentMessagePipeline.create`; analysis remains inside the existing `InferenceExecutor` call in the message pipeline. Startup metadata mismatch fails fast, while per-message predictor errors still degrade through `DeterministicUserAffectAnalyzer`.

- [ ] **Step 4: Document local bundle configuration**

```dotenv
OPENEDEN_DJL_AFFECT_MODEL_PATH=data/models/user-affect-qwen
```

Document that Qwen weights are intentionally not committed to ordinary Git, how to generate the bundle, the required memory, and how the deterministic fallback behaves when individual inference fails.

- [ ] **Step 5: Run server and pipeline tests**

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :server:test :core:jvmTest`

Expected: all JVM tests PASS.

- [ ] **Step 6: Commit**

```powershell
git add server/src/main/kotlin/Runtime.kt server/src/test/kotlin/RuntimeModelLoadingTest.kt .env.example README.md README.zh-CN.md
git commit -m "feat: load Qwen affect bundle in runtime"
```

### Task 6: Train, Evaluate, Benchmark, and Publish Metrics

**Files:**
- Create locally: `data/models/user-affect-qwen/model.pt`
- Create locally: `data/models/user-affect-qwen/tokenizer.json`
- Create: `data/models/user-affect-qwen/metadata.json`
- Create: `data/models/user-affect-qwen/metrics.json`
- Create: `data/evaluation/user-affect.challenge.jsonl`
- Modify: `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt`
- Create: `core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerBenchmarkTest.kt`

- [ ] **Step 1: Add and validate the tracked Chinese challenge set**

Create at least 48 records distributed evenly across negation, sarcasm,
indirect connection need, mixed affect, slang, and low-information messages.
Each JSONL record uses the same `sampleId`, `text`, and six-label schema as the
training corpus, uses a `challenge-` ID prefix, and is excluded from all three
training partitions. Add a unit test that checks category counts, unique text,
finite labels, and absence of challenge texts from the training corpus.

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Expected: challenge-set schema and leakage checks PASS.

- [ ] **Step 2: Record the clean GPU preflight**

Run: `nvidia-smi --query-gpu=name,memory.total,memory.free,driver_version --format=csv,noheader`

Run: `python -c "import torch; assert torch.cuda.is_available(); print(torch.cuda.get_device_name(0), torch.cuda.is_bf16_supported())"`

Expected: RTX 4070 Ti is reported and BF16 support is `True`.

- [ ] **Step 3: Run full CUDA training**

Run: `python scripts/train-user-affect-model.py --corpus data/training/user-affect.raw.jsonl --output data/models/user-affect-qwen --base-model Qwen/Qwen3-Embedding-0.6B --epochs 8 --physical-batch-size 2 --gradient-accumulation-steps 8 --max-seq-length 192 --early-stopping-patience 2 --export`

Expected: process uses CUDA, records BF16, completes without CPU fallback, and writes the best validation checkpoint plus exported bundle.

- [ ] **Step 4: Compare against the legacy baseline and challenge set**

Run: `python scripts/train-user-affect-model.py --evaluate-only --corpus data/training/user-affect.raw.jsonl --bundle data/models/user-affect-qwen --baseline data/models/djl/affect --challenge-set data/evaluation/user-affect.challenge.jsonl`

Expected: report includes overall/per-dimension MAE, baseline deltas, and challenge-set results; Qwen overall held-out MAE is lower than `0.1385689`.

- [ ] **Step 5: Run the real JVM bundle smoke test**

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :core:jvmTest --tests io.openeden.relationship.DjlTextAffectAnalyzerTest`

Expected: the real bundle loads and returns six finite bounded values for Chinese input.

- [ ] **Step 6: Add and run the opt-in CPU benchmark**

The benchmark warms the model with five messages, measures at least 30 messages
with `System.nanoTime()`, sorts durations, and writes cold-start, p50, and p95
milliseconds to the test report. It runs only when
`includeModelBenchmarks=true`, so normal unit tests remain deterministic.

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :core:jvmTest --tests io.openeden.relationship.DjlTextAffectAnalyzerBenchmarkTest -PincludeModelBenchmarks=true`

Expected: metrics record cold start, p50/p95 warm latency over at least 30 short Chinese messages, process memory, CPU model, and thread count. This is observational; no unmeasured latency claim is made.

- [ ] **Step 7: Run full verification**

Run: `python -m unittest scripts.tests.test_user_affect_training -v`

Run: `$env:JAVA_HOME='F:\SDK\JDK21'; .\gradlew.bat :core:jvmTest :server:test`

Run: `git diff --check`

Expected: Python tests PASS, Gradle tests PASS, and no whitespace errors are reported.

- [ ] **Step 8: Commit only reproducible metadata, challenge data, and tests**

```powershell
git add data/models/user-affect-qwen/metadata.json data/models/user-affect-qwen/metrics.json data/evaluation/user-affect.challenge.jsonl core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerTest.kt core/src/jvmTest/kotlin/io/openeden/relationship/DjlTextAffectAnalyzerBenchmarkTest.kt
git commit -m "test: verify trained Qwen affect model"
```

Do not add `model.pt`, checkpoints, the copied encoder, raw training data, or generated sample corpora to ordinary Git history.
