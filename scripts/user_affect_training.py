"""CUDA-only Qwen user-affect fine-tuning and bundle preparation."""

from __future__ import annotations

import argparse
import json
import math
import random
import shutil
from dataclasses import asdict, dataclass
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer
from torch import nn
from torch.utils.data import DataLoader
from tqdm import tqdm

LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")


@dataclass(frozen=True)
class Partitions:
    train: list[dict]
    validation: list[dict]
    test: list[dict]


@dataclass
class TrainingConfig:
    base_model: str = "Qwen/Qwen3-Embedding-0.6B"
    epochs: int = 8
    physical_batch_size: int = 2
    gradient_accumulation_steps: int = 8
    max_seq_length: int = 192
    encoder_learning_rate: float = 2e-5
    head_learning_rate: float = 1e-3
    weight_decay: float = 1e-4
    early_stopping_patience: int = 2
    max_steps: int | None = None
    seed: int = 0xAFFEC726
    freeze_text_encoder: bool = False
    weight_dtype: torch.dtype = torch.bfloat16


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
        return self.network(embedding.float())


def require_cuda() -> torch.device:
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is required for Qwen affect training; CPU fallback is disabled")
    if not torch.cuda.is_bf16_supported():
        raise RuntimeError("BF16 is required for Qwen affect training")
    return torch.device("cuda")


def validate_records(records: list[dict], minimum_samples: int = 512) -> list[dict]:
    seen: set[str] = set()
    for item in records:
        sample_id = str(item.get("sampleId", ""))
        if not sample_id or sample_id in seen:
            raise ValueError(f"Duplicate or missing sampleId: {sample_id}")
        if not str(item.get("text", "")).strip():
            raise ValueError(f"Empty text: {sample_id}")
        try:
            values = [float(item[label]) for label in LABELS]
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(f"Invalid labels: {sample_id}") from exc
        if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in values):
            raise ValueError(f"Invalid labels: {sample_id}")
        seen.add(sample_id)
    if len(records) < minimum_samples:
        raise ValueError(f"Need at least {minimum_samples} affect training samples")
    return records


def load_records(path: Path) -> list[dict]:
    records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return validate_records(records)


def split_records(records: list[dict], seed: int) -> Partitions:
    ordered = sorted(records, key=lambda item: str(item["sampleId"]))
    random.Random(seed).shuffle(ordered)
    test_end = max(1, int(len(ordered) * 0.10))
    validation_end = test_end + max(1, int(len(ordered) * 0.10))
    return Partitions(ordered[validation_end:], ordered[test_end:validation_end], ordered[:test_end])


def bundle_metadata(config: TrainingConfig, embedding_dimension: int = 1024) -> dict:
    return {
        "schemaVersion": 2,
        "baseModel": config.base_model,
        "precision": "bfloat16",
        "embeddingDimension": embedding_dimension,
        "maxSequenceLength": config.max_seq_length,
        "outputs": list(LABELS),
        "modelFile": "model.pt",
        "tokenizerFile": "tokenizer.json",
        "head": "1024->256->6-sigmoid",
    }


def _texts_and_targets(rows: list[dict]) -> tuple[list[str], torch.Tensor]:
    texts = [str(row["text"]) for row in rows]
    targets = torch.tensor([[float(row[label]) for label in LABELS] for row in rows], dtype=torch.float32)
    return texts, targets


def _predict(encoder: SentenceTransformer, head: AffectHead, texts: list[str], device: torch.device) -> torch.Tensor:
    features = encoder.tokenize(texts)
    features = {key: value.to(device) if hasattr(value, "to") else value for key, value in features.items()}
    embedding = encoder(features)["sentence_embedding"]
    return head(embedding)


def evaluate(encoder: SentenceTransformer, head: AffectHead, rows: list[dict], batch_size: int, device: torch.device) -> tuple[float, list[float]]:
    encoder.eval()
    head.eval()
    absolute = torch.zeros(len(LABELS), device=device)
    count = 0
    with torch.no_grad():
        for start in range(0, len(rows), batch_size):
            texts, targets = _texts_and_targets(rows[start:start + batch_size])
            prediction = _predict(encoder, head, texts, device)
            absolute += (prediction - targets.to(device)).abs().sum(dim=0)
            count += len(texts)
    per_dimension = (absolute / max(count, 1)).float().cpu().tolist()
    return sum(per_dimension) / len(per_dimension), per_dimension


def train(config: TrainingConfig, corpus: Path, output: Path) -> dict:
    device = require_cuda()
    random.seed(config.seed)
    torch.manual_seed(config.seed)
    torch.cuda.reset_peak_memory_stats(device)
    records = load_records(corpus)
    partitions = split_records(records, config.seed)

    encoder = SentenceTransformer(config.base_model, device=str(device))
    encoder.to(dtype=config.weight_dtype)
    encoder.max_seq_length = config.max_seq_length
    if hasattr(encoder[0], "auto_model"):
        encoder[0].auto_model.gradient_checkpointing_enable()
    if config.freeze_text_encoder:
        for parameter in encoder.parameters():
            parameter.requires_grad = False
    embedding_dimension = encoder.get_sentence_embedding_dimension()
    if embedding_dimension is None:
        raise RuntimeError("Could not determine Qwen embedding dimension")
    head = AffectHead(embedding_dimension).to(device)
    encoder_parameters = [parameter for parameter in encoder.parameters() if parameter.requires_grad]
    optimizer = torch.optim.AdamW(
        [{"params": encoder_parameters, "lr": config.encoder_learning_rate}, {"params": head.parameters(), "lr": config.head_learning_rate}],
        weight_decay=config.weight_decay,
    )
    scaler = torch.amp.GradScaler("cuda", enabled=False)
    loss_fn = nn.MSELoss()
    best_mae = float("inf")
    best_state: dict[str, dict[str, torch.Tensor]] | None = None
    steps = 0
    stale_epochs = 0

    batches_per_epoch = math.ceil(len(partitions.train) / config.physical_batch_size)
    optimizer_steps_per_epoch = math.ceil(batches_per_epoch / config.gradient_accumulation_steps)
    for epoch in range(config.epochs):
        encoder.train()
        head.train()
        rows = list(partitions.train)
        random.Random(config.seed + epoch).shuffle(rows)
        optimizer.zero_grad(set_to_none=True)
        progress = tqdm(
            range(0, len(rows), config.physical_batch_size),
            total=batches_per_epoch,
            desc=f"epoch {epoch + 1}/{config.epochs}",
            unit="batch",
        )
        for batch_index, start in enumerate(progress):
            texts, targets = _texts_and_targets(rows[start:start + config.physical_batch_size])
            with torch.autocast(device_type="cuda", dtype=config.weight_dtype):
                prediction = _predict(encoder, head, texts, device)
                loss = loss_fn(prediction, targets.to(device)) / config.gradient_accumulation_steps
            loss.backward()
            is_boundary = ((batch_index + 1) % config.gradient_accumulation_steps == 0)
            is_last = start + config.physical_batch_size >= len(rows)
            if is_boundary or is_last:
                torch.nn.utils.clip_grad_norm_(list(encoder.parameters()) + list(head.parameters()), 1.0)
                optimizer.step()
                optimizer.zero_grad(set_to_none=True)
                steps += 1
                progress.set_postfix(loss=f"{float(loss.detach()) * config.gradient_accumulation_steps:.4f}", step=steps)
                if config.max_steps is not None and steps >= config.max_steps:
                    break
        validation_mae, validation_by_dimension = evaluate(encoder, head, partitions.validation, config.physical_batch_size, device)
        print(
            f"epoch {epoch + 1}/{config.epochs} complete: "
            f"validation_mae={validation_mae:.6f}, optimizer_steps={steps}/{optimizer_steps_per_epoch * (epoch + 1)}",
            flush=True,
        )
        if validation_mae < best_mae:
            best_mae = validation_mae
            stale_epochs = 0
            best_state = {
                "encoder": {key: value.detach().cpu().clone() for key, value in encoder.state_dict().items()},
                "head": {key: value.detach().cpu().clone() for key, value in head.state_dict().items()},
            }
        else:
            stale_epochs += 1
        if config.max_steps is not None and steps >= config.max_steps:
            break
        if stale_epochs >= config.early_stopping_patience:
            break

    if best_state is None:
        raise RuntimeError("Training completed without a validation checkpoint")
    encoder.load_state_dict(best_state["encoder"])
    head.load_state_dict(best_state["head"])
    test_mae, test_by_dimension = evaluate(encoder, head, partitions.test, config.physical_batch_size, device)
    output.mkdir(parents=True, exist_ok=True)
    checkpoint = {"encoder": best_state["encoder"], "head": best_state["head"]}
    torch.save(checkpoint, output / "model.pt")
    torch.save(head.state_dict(), output / "head.pt")
    text_encoder_output = output / "text_encoder"
    encoder.save(str(text_encoder_output))
    tokenizer_paths = list(text_encoder_output.rglob("tokenizer.json"))
    if tokenizer_paths:
        shutil.copy2(tokenizer_paths[0], output / "tokenizer.json")
    metadata = bundle_metadata(config, embedding_dimension)
    (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    metrics = {
        "schemaVersion": 2,
        "baseModel": config.base_model,
        "counts": {"train": len(partitions.train), "validation": len(partitions.validation), "test": len(partitions.test)},
        "steps": steps,
        "validationMae": best_mae,
        "testMae": test_mae,
        "testMaeByDimension": dict(zip(LABELS, test_by_dimension)),
        "validationMaeByDimension": dict(zip(LABELS, validation_by_dimension)),
        "hardware": {
            "device": torch.cuda.get_device_name(device),
            "cudaVersion": torch.version.cuda,
            "weightDtype": "bfloat16",
            "peakAllocatedBytes": torch.cuda.max_memory_allocated(device),
        },
        "config": {key: value for key, value in asdict(config).items() if key != "weight_dtype"},
    }
    (output / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    return metrics


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=Path("data/training/user-affect-v2.final.unique.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("data/models/thymos-6d"))
    parser.add_argument("--base-model", default="Qwen/Qwen3-Embedding-0.6B")
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--physical-batch-size", type=int, default=2)
    parser.add_argument("--gradient-accumulation-steps", type=int, default=8)
    parser.add_argument("--max-seq-length", type=int, default=192)
    parser.add_argument("--early-stopping-patience", type=int, default=2)
    parser.add_argument("--max-steps", type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = TrainingConfig(
        base_model=args.base_model,
        epochs=args.epochs,
        physical_batch_size=args.physical_batch_size,
        gradient_accumulation_steps=args.gradient_accumulation_steps,
        max_seq_length=args.max_seq_length,
        early_stopping_patience=args.early_stopping_patience,
        max_steps=args.max_steps,
    )
    print(json.dumps(train(config, args.corpus, args.output), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
