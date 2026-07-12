#!/usr/bin/env python3
"""Evaluate a trained OpenEden codebook base model.

The evaluator maps each 8D vector through the trained projector, retrieves the
nearest semantic text embeddings, and reports exact/sample and node-level hits.
It does not store or derive dissonance as a training dimension.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer
from torch import nn
from tqdm import tqdm


def load_training_module():
    module_path = Path(__file__).with_name("train-codebook-base-model.py")
    spec = importlib.util.spec_from_file_location("train_codebook_base_model", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load training helpers from {module_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", default="data/training/codebook.samples.json")
    parser.add_argument("--model-dir", default="data/models/codebook-base-model")
    parser.add_argument("--output", default=None)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    return parser.parse_args()


def encode_texts(text_model: SentenceTransformer, texts: list[str], batch_size: int, device: str) -> torch.Tensor:
    embeddings = []
    for start in tqdm(range(0, len(texts), batch_size), desc="text embeddings"):
        batch = texts[start : start + batch_size]
        features = text_model.tokenize(batch)
        features = {key: value.to(device) if hasattr(value, "to") else value for key, value in features.items()}
        with torch.no_grad():
            batch_embeddings = text_model(features)["sentence_embedding"]
        embeddings.append(nn.functional.normalize(batch_embeddings.float(), dim=-1).cpu())
    return torch.cat(embeddings, dim=0)


def encode_vectors(projector: nn.Module, vectors: torch.Tensor, batch_size: int, device: str) -> torch.Tensor:
    embeddings = []
    for start in tqdm(range(0, vectors.size(0), batch_size), desc="vector embeddings"):
        batch = vectors[start : start + batch_size].to(device)
        with torch.no_grad():
            embeddings.append(projector(batch).cpu())
    return torch.cat(embeddings, dim=0)


def evaluate() -> None:
    args = parse_args()
    training = load_training_module()
    model_dir = Path(args.model_dir)
    metadata = json.loads((model_dir / "metadata.json").read_text(encoding="utf-8"))
    samples = training.load_samples(Path(args.samples))
    device = args.device

    text_model = SentenceTransformer(str(model_dir / "text_encoder"), device=device)
    projector = training.VectorProjector(metadata["embeddingDim"]).to(device)
    projector.load_state_dict(torch.load(model_dir / "vector_projector.pt", map_location=device))
    projector.eval()

    texts = [sample.text for sample in samples]
    node_ids = [sample.node_id for sample in samples]
    vectors = torch.tensor([sample.vector for sample in samples], dtype=torch.float32)

    text_embeddings = encode_texts(text_model, texts, args.batch_size, device)
    vector_embeddings = encode_vectors(projector, vectors, args.batch_size, device)
    scores = vector_embeddings @ text_embeddings.T
    top5 = scores.topk(k=min(5, scores.size(1)), dim=1).indices

    exact_top1 = 0
    node_top1 = 0
    node_top5 = 0
    for index, candidates in enumerate(top5.tolist()):
        if candidates[0] == index:
            exact_top1 += 1
        expected_node = node_ids[index]
        if expected_node is not None and node_ids[candidates[0]] == expected_node:
            node_top1 += 1
        if expected_node is not None and any(node_ids[candidate] == expected_node for candidate in candidates):
            node_top5 += 1

    result = {
        "samples": len(samples),
        "baseModel": metadata["baseModel"],
        "epochs": metadata["epochs"],
        "batchSize": metadata["batchSize"],
        "freezeTextEncoder": metadata["freezeTextEncoder"],
        "device": device,
        "exactTop1": exact_top1 / len(samples),
        "nodeTop1": node_top1 / len(samples),
        "nodeTop5": node_top5 / len(samples),
    }
    output = Path(args.output) if args.output else model_dir / "evaluation.json"
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    evaluate()
