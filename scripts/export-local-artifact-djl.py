#!/usr/bin/env python3
"""Export OpenEden's deterministic JSON MLPs as DJL-loadable TorchScript models.

The exported models contain only numerical state. Persona text and the derived
D value remain outside the model artifact; the VQ input is the eight stored
coordinates only.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
from torch import nn


class LocalMlp(nn.Module):
    def __init__(self, spec: dict) -> None:
        super().__init__()
        layers: list[nn.Module] = []
        input_size = int(spec["inputSize"])
        for layer in spec["layers"]:
            linear = nn.Linear(input_size, int(layer["outputSize"]))
            with torch.no_grad():
                linear.weight.copy_(torch.tensor(layer["weights"], dtype=torch.float32))
                linear.bias.copy_(torch.tensor(layer["biases"], dtype=torch.float32))
            layers.append(linear)
            activation = layer.get("activation", "LINEAR")
            if activation == "RELU":
                layers.append(nn.ReLU())
            elif activation == "TANH":
                layers.append(nn.Tanh())
            elif activation == "SIGMOID":
                layers.append(nn.Sigmoid())
            elif activation != "LINEAR":
                raise ValueError(f"Unsupported activation: {activation}")
            input_size = int(layer["outputSize"])
        self.network = nn.Sequential(*layers)
        self.input_size = int(spec["inputSize"])

    def forward(self, values: torch.Tensor) -> torch.Tensor:
        return self.network(values)


def export(spec: dict, output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    model = LocalMlp(spec).eval()
    example = torch.zeros(1, model.input_size, dtype=torch.float32)
    traced = torch.jit.trace(model, example)
    traced.save(str(output_dir / "model.pt"))
    (output_dir / "metadata.json").write_text(
        json.dumps({"inputSize": model.input_size}, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact", type=Path, default=Path("data/models/local-model-artifact.json"))
    parser.add_argument("--output", type=Path, default=Path("build/djl-models"))
    args = parser.parse_args()

    artifact = json.loads(args.artifact.read_text(encoding="utf-8"))
    export(artifact["vqVae"]["encoder"], args.output / "vqvae")
    export(artifact["textEmbedding"]["projector"], args.output / "text")
    export(artifact["emotionalEmbedding"], args.output / "emotional")
    if artifact.get("textAffect"):
        export(artifact["textAffect"], args.output / "affect")
    print(f"Exported DJL TorchScript models to {args.output}")


if __name__ == "__main__":
    main()
