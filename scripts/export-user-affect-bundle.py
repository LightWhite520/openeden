#!/usr/bin/env python3
"""Convert a trained Qwen affect bundle into a DJL-loadable TorchScript model."""

from __future__ import annotations

import argparse
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer

from user_affect_training import AffectHead


class ExportedAffectModel(torch.nn.Module):
    def __init__(self, encoder: SentenceTransformer, head: AffectHead) -> None:
        super().__init__()
        self.encoder = encoder
        self.head = head

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        features = {"input_ids": input_ids, "attention_mask": attention_mask}
        embedding = self.encoder(features)["sentence_embedding"]
        return self.head(embedding)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", type=Path, default=Path("data/models/thymos-6d"))
    args = parser.parse_args()
    encoder = SentenceTransformer(str(args.bundle / "text_encoder"), device="cpu")
    encoder.eval()
    embedding_dimension = encoder.get_embedding_dimension()
    head = AffectHead(embedding_dimension)
    head.load_state_dict(torch.load(args.bundle / "head.pt", map_location="cpu", weights_only=True))
    head.eval()
    model = ExportedAffectModel(encoder, head).eval()
    input_ids = torch.zeros(1, 192, dtype=torch.int64)
    attention_mask = torch.ones(1, 192, dtype=torch.int64)
    traced = torch.jit.trace(model, (input_ids, attention_mask), strict=False)
    temporary = args.bundle / "model.pt.tmp"
    traced.save(str(temporary))
    temporary.replace(args.bundle / "model.pt")
    print(f"exported={args.bundle / 'model.pt'}")


if __name__ == "__main__":
    main()
