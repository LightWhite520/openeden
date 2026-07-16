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


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


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


@dataclass(frozen=True)
class Pair:
    vector: list[float]
    text: str
    node_id: str


class Corpus(Dataset):
    def __init__(self, samples: list[Pair]) -> None:
        self.samples = samples

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, index: int) -> Pair:
        return self.samples[index]


def load_samples(path: Path) -> list[Pair]:
    data = json.loads(path.read_text(encoding="utf-8"))
    pairs = []
    for item in data["samples"]:
        vector = item["vector"]
        if set(vector) != set(DIMS):
            raise ValueError(f"bad vector keys for {item.get('nodeId')}: {sorted(vector)}")
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            raise ValueError(f"sample contains derived dissonance: {item.get('nodeId')}")
        en = item.get("definitionEn") or item.get("definition") or ""
        zh = item.get("definitionZh") or item.get("definition") or ""
        text = f"EN: {en}\nZH: {zh}" if zh else en
        pairs.append(Pair([float(vector[key]) for key in DIMS], text, str(item["nodeId"])))
    return pairs


def collate(batch: list[Pair]):
    return (
        torch.tensor([item.vector for item in batch], dtype=torch.float32),
        [item.text for item in batch],
        [item.node_id for item in batch],
    )


def multipositive_loss(logits: torch.Tensor, node_ids: list[str]) -> torch.Tensor:
    ids = torch.tensor([[left == right for right in node_ids] for left in node_ids], device=logits.device)
    neg_inf = torch.finfo(logits.dtype).min
    row_pos = torch.where(ids, logits, neg_inf).logsumexp(dim=1)
    row_all = logits.logsumexp(dim=1)
    col_pos = torch.where(ids.T, logits.T, neg_inf).logsumexp(dim=1)
    col_all = logits.T.logsumexp(dim=1)
    return ((row_all - row_pos).mean() + (col_all - col_pos).mean()) / 2


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", default="data/training/codebook.projector-hardcases-large.json")
    parser.add_argument("--start", default="data/models/codebook-base-model-hardcase-full-ft-b24")
    parser.add_argument("--output", default="data/models/codebook-base-model-hardcase-large-ft-b24")
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=24)
    parser.add_argument("--lr", type=float, default=1.5e-6)
    parser.add_argument("--projector-lr", type=float, default=1.5e-4)
    parser.add_argument("--max-seq-length", type=int, default=192)
    parser.add_argument("--freeze-text-encoder", action="store_true")
    parser.add_argument("--amp-dtype", choices=("none", "bf16", "fp16"), default="none")
    args = parser.parse_args()

    if not torch.cuda.is_available():
        raise SystemExit("CUDA is required; refusing to train on CPU")
    device = "cuda"
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)

    samples = load_samples(Path(args.samples))
    loader = DataLoader(Corpus(samples), batch_size=args.batch_size, shuffle=True, collate_fn=collate, drop_last=True)

    text_model_path = Path(args.start) / "text_encoder"
    text_model = SentenceTransformer(str(text_model_path if text_model_path.exists() else args.start), device=device)
    text_model.max_seq_length = args.max_seq_length
    if args.freeze_text_encoder:
        for parameter in text_model.parameters():
            parameter.requires_grad = False
        text_model.eval()
    embedding_dim = text_model.get_sentence_embedding_dimension()
    if embedding_dim is None:
        raise RuntimeError("could not determine embedding dimension")

    projector = VectorProjector(embedding_dim).to(device)
    projector_path = Path(args.start) / "vector_projector.pt"
    if projector_path.exists():
        projector.load_state_dict(torch.load(projector_path, map_location=device))

    params = [{"params": projector.parameters(), "lr": args.projector_lr}]
    text_parameters = [p for p in text_model.parameters() if p.requires_grad]
    if text_parameters:
        params.append({"params": text_parameters, "lr": args.lr})
    temperature = nn.Parameter(torch.tensor(0.07, device=device))
    params.append({"params": [temperature], "lr": args.projector_lr})
    optimizer = torch.optim.AdamW(params)
    use_amp = args.amp_dtype != "none"
    amp_dtype = torch.bfloat16 if args.amp_dtype == "bf16" else torch.float16
    scaler = torch.amp.GradScaler("cuda", enabled=args.amp_dtype == "fp16")

    history = []
    for epoch in range(args.epochs):
        total = 0.0
        progress = tqdm(loader, desc=f"epoch {epoch + 1}/{args.epochs}")
        for step, (vectors, texts, node_ids) in enumerate(progress, start=1):
            vectors = vectors.to(device)
            features = text_model.tokenize(texts)
            features = {key: value.to(device) if hasattr(value, "to") else value for key, value in features.items()}
            with torch.amp.autocast("cuda", dtype=amp_dtype, enabled=use_amp):
                if args.freeze_text_encoder:
                    with torch.no_grad():
                        text_embeddings = text_model(features)["sentence_embedding"]
                else:
                    text_embeddings = text_model(features)["sentence_embedding"]
                text_embeddings = nn.functional.normalize(text_embeddings.float(), dim=-1)
                vector_embeddings = projector(vectors)
                logits = vector_embeddings @ text_embeddings.T / temperature.clamp(min=0.01)
                loss = multipositive_loss(logits, node_ids)
            optimizer.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.step(optimizer)
            scaler.update()
            total += float(loss.detach())
            progress.set_postfix(loss=f"{total / step:.4f}", temp=f"{float(temperature.detach()):.4f}")
        history.append({"epoch": epoch + 1, "loss": total / max(1, len(loader)), "temperature": float(temperature.detach())})
        torch.cuda.empty_cache()

    text_model.save(str(output / "text_encoder"))
    torch.save(projector.state_dict(), output / "vector_projector.pt")
    (output / "metadata.json").write_text(
        json.dumps(
            {
                "start": args.start,
                "samples": len(samples),
                "dimensions": list(DIMS),
                "embeddingDim": embedding_dim,
                "epochs": args.epochs,
                "batchSize": args.batch_size,
                "freezeTextEncoder": args.freeze_text_encoder,
                "ampDtype": args.amp_dtype,
                "lossHistory": history,
                "cudaDevice": torch.cuda.get_device_name(0),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
