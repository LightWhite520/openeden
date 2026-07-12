#!/usr/bin/env python3
"""Train and export OpenEden's six-dimensional user-affect model."""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import torch
from torch import nn
from torch.utils.data import DataLoader, TensorDataset

LABELS = ("valence", "arousal", "dominance", "connectionNeed", "openness", "confidence")


class AffectMlp(nn.Module):
    def __init__(self, input_size: int) -> None:
        super().__init__()
        self.network = nn.Sequential(
            nn.Linear(input_size, 96), nn.ReLU(), nn.Dropout(0.10),
            nn.Linear(96, 48), nn.ReLU(), nn.Linear(48, 6), nn.Sigmoid(),
        )

    def forward(self, values: torch.Tensor) -> torch.Tensor:
        return self.network(values)


def features(text: str, width: int) -> list[float]:
    buckets = [0.0] * width
    for index, char in enumerate(text):
        buckets[(ord(char) * 31 + index) % width] += 1.0
    norm = sum(value * value for value in buckets) ** 0.5
    return buckets if norm == 0 else [value / norm for value in buckets]


def load_records(path: Path) -> list[dict]:
    records: list[dict] = []
    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        item = json.loads(line)
        if item["sampleId"] in seen or not str(item["text"]).strip():
            raise ValueError(f"Duplicate or empty sample: {item.get('sampleId')}")
        values = [float(item[label]) for label in LABELS]
        if not all(torch.isfinite(torch.tensor(values))) or not all(0 <= value <= 1 for value in values):
            raise ValueError(f"Invalid labels: {item['sampleId']}")
        seen.add(item["sampleId"])
        records.append(item)
    if len(records) < 512:
        raise ValueError("Need at least 512 affect training samples")
    return records


def evaluate(model: nn.Module, loader: DataLoader, device: torch.device) -> tuple[float, list[float]]:
    model.eval()
    absolute = torch.zeros(6, device=device)
    count = 0
    with torch.no_grad():
        for inputs, targets in loader:
            prediction = model(inputs.to(device))
            absolute += (prediction - targets.to(device)).abs().sum(dim=0)
            count += targets.shape[0]
    per_dimension = (absolute / count).cpu().tolist()
    return sum(per_dimension) / len(per_dimension), per_dimension


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, default=Path("data/training/user-affect.raw.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("data/models/djl/affect"))
    parser.add_argument("--bucket-size", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=80)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--learning-rate", type=float, default=0.002)
    parser.add_argument("--max-test-mae", type=float, default=0.24)
    parser.add_argument("--seed", type=int, default=0xaffec726)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    random.seed(args.seed)
    records = load_records(args.corpus)
    records.sort(key=lambda item: item["sampleId"])
    random.Random(args.seed).shuffle(records)
    test_end = max(1, int(len(records) * 0.10))
    validation_end = test_end + max(1, int(len(records) * 0.10))
    partitions = {"test": records[:test_end], "validation": records[test_end:validation_end], "train": records[validation_end:]}

    def loader(rows: list[dict], shuffle: bool = False) -> DataLoader:
        x = torch.tensor([features(row["text"], args.bucket_size) for row in rows], dtype=torch.float32)
        y = torch.tensor([[row[label] for label in LABELS] for row in rows], dtype=torch.float32)
        return DataLoader(TensorDataset(x, y), batch_size=args.batch_size, shuffle=shuffle)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = AffectMlp(args.bucket_size).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=1e-4)
    loss_fn = nn.MSELoss()
    best_state: dict | None = None
    best_mae = float("inf")
    train_loader, validation_loader, test_loader = loader(partitions["train"], True), loader(partitions["validation"]), loader(partitions["test"])
    for _ in range(args.epochs):
        model.train()
        for inputs, targets in train_loader:
            optimizer.zero_grad()
            loss = loss_fn(model(inputs.to(device)), targets.to(device))
            loss.backward()
            optimizer.step()
        mae, _ = evaluate(model, validation_loader, device)
        if mae < best_mae:
            best_mae = mae
            best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}
    assert best_state is not None
    model.load_state_dict(best_state)
    test_mae, per_dimension = evaluate(model, test_loader, device)
    if test_mae > args.max_test_mae:
        raise RuntimeError(f"Test MAE {test_mae:.4f} exceeds gate {args.max_test_mae:.4f}")
    args.output.mkdir(parents=True, exist_ok=True)
    traced = torch.jit.trace(model.cpu().eval(), torch.zeros(1, args.bucket_size, dtype=torch.float32))
    traced.save(str(args.output / "model.pt"))
    metrics = {
        "schemaVersion": 1, "model": "user-affect-mlp", "inputSize": args.bucket_size,
        "outputs": list(LABELS), "device": str(device), "seed": args.seed,
        "counts": {name: len(rows) for name, rows in partitions.items()},
        "validationMae": best_mae, "testMae": test_mae,
        "testMaeByDimension": dict(zip(LABELS, per_dimension)),
    }
    (args.output / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n", encoding="utf-8")
    (args.output / "metadata.json").write_text(json.dumps({"inputSize": args.bucket_size, "outputs": list(LABELS)}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
