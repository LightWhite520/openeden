#!/usr/bin/env python3
"""Train a base embedding model against OpenEden 8D codebook samples.

This trains a text encoder plus a small 8D vector projector with contrastive
loss. It does not store derived D and does not encode persona behavior.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer
from torch import nn
from torch.utils.data import DataLoader, Dataset
from tqdm import tqdm


DIMENSIONS = ("l", "p", "e", "s", "tau", "v", "m", "f")


@dataclass(frozen=True)
class CodebookPair:
    vector: list[float]
    text: str
    node_id: str | None = None


class CodebookDataset(Dataset[CodebookPair]):
    def __init__(self, samples: list[CodebookPair]) -> None:
        self.samples = samples

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int) -> CodebookPair:
        return self.samples[index]


class VectorProjector(nn.Module):
    def __init__(self, output_dim: int) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(8, 256),
            nn.GELU(),
            nn.LayerNorm(256),
            nn.Linear(256, output_dim),
        )

    def forward(self, vector: torch.Tensor) -> torch.Tensor:
        return nn.functional.normalize(self.net(vector), dim=-1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", default="data/training/codebook.samples.json")
    parser.add_argument("--output", default="data/models/codebook-base-model")
    parser.add_argument("--base-model", default="Qwen/Qwen3-Embedding-0.6B")
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--projector-lr", type=float, default=1e-3)
    parser.add_argument("--max-seq-length", type=int, default=192)
    parser.add_argument("--freeze-text-encoder", action="store_true")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    return parser.parse_args()


def load_samples(path: Path) -> list[CodebookPair]:
    data = json.loads(path.read_text(encoding="utf-8"))
    samples = []
    for item in data["samples"]:
        vector = item["vector"]
        if set(vector.keys()) != set(DIMENSIONS):
            raise ValueError(f"Invalid vector keys for {item.get('nodeId')}: {vector.keys()}")
        definition_en = item.get("definitionEn") or item["definition"]
        definition_zh = item.get("trainingTextZh") or item.get("definitionZh") or ""
        text = f"EN: {definition_en}\nZH: {definition_zh}" if definition_zh else definition_en
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            raise ValueError(f"Sample contains derived dissonance text: {item.get('nodeId')}")
        samples.append(
            CodebookPair(
                vector=[float(vector[key]) for key in DIMENSIONS],
                text=text,
                node_id=item.get("nodeId"),
            ),
        )
    return samples


def collate(batch: list[CodebookPair]) -> tuple[torch.Tensor, list[str]]:
    vectors = torch.tensor([sample.vector for sample in batch], dtype=torch.float32)
    texts = [sample.text for sample in batch]
    return vectors, texts


def train() -> None:
    args = parse_args()
    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    samples = load_samples(Path(args.samples))
    dataset = CodebookDataset(samples)
    loader = DataLoader(dataset, batch_size=args.batch_size, shuffle=True, collate_fn=collate)

    text_model = SentenceTransformer(args.base_model, device=args.device)
    text_model.max_seq_length = args.max_seq_length
    embedding_dim = text_model.get_sentence_embedding_dimension()
    if embedding_dim is None:
        raise RuntimeError("Could not determine sentence embedding dimension")

    projector = VectorProjector(embedding_dim).to(args.device)
    if args.freeze_text_encoder:
        for parameter in text_model.parameters():
            parameter.requires_grad = False

    parameters = [
        {"params": projector.parameters(), "lr": args.projector_lr},
    ]
    text_parameters = [parameter for parameter in text_model.parameters() if parameter.requires_grad]
    if text_parameters:
        parameters.append({"params": text_parameters, "lr": args.lr})
    optimizer = torch.optim.AdamW(parameters)
    temperature = nn.Parameter(torch.tensor(0.07, device=args.device))
    optimizer.add_param_group({"params": [temperature], "lr": args.projector_lr})

    for epoch in range(args.epochs):
        total_loss = 0.0
        progress = tqdm(loader, desc=f"epoch {epoch + 1}/{args.epochs}")
        for vectors, texts in progress:
            vectors = vectors.to(args.device)
            text_features = text_model.tokenize(texts)
            text_features = {
                key: value.to(args.device) if hasattr(value, "to") else value
                for key, value in text_features.items()
            }
            text_embeddings = text_model(text_features)["sentence_embedding"]
            text_embeddings = nn.functional.normalize(text_embeddings.float(), dim=-1)
            vector_embeddings = projector(vectors)

            logits = vector_embeddings @ text_embeddings.T / temperature.clamp(min=0.01)
            labels = torch.arange(logits.size(0), device=args.device)
            loss = (nn.functional.cross_entropy(logits, labels) + nn.functional.cross_entropy(logits.T, labels)) / 2

            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()

            total_loss += float(loss.detach())
            progress.set_postfix(loss=f"{total_loss / max(1, progress.n):.4f}")

    text_model.save(str(output_dir / "text_encoder"))
    torch.save(projector.state_dict(), output_dir / "vector_projector.pt")
    (output_dir / "metadata.json").write_text(
        json.dumps(
            {
                "baseModel": args.base_model,
                "samples": len(samples),
                "dimensions": list(DIMENSIONS),
                "embeddingDim": embedding_dim,
                "epochs": args.epochs,
                "batchSize": args.batch_size,
                "freezeTextEncoder": args.freeze_text_encoder,
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    train()
