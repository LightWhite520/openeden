from __future__ import annotations

import argparse
import importlib.util
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

import torch
from sentence_transformers import SentenceTransformer


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")
KEYWORDS = (
    "negated",
    "quote",
    "third",
    "sarcasm",
    "flat",
    "exhausted",
    "guarded",
    "memory",
    "fear",
    "tau",
    "v_low",
    "v_high",
    "m_high",
    "p_low",
    "p_high",
    "mechanical",
    "sleepy",
    "daily",
)


def load_training_module():
    module_path = Path(__file__).with_name("train-8d-large-full-ft.py")
    spec = importlib.util.spec_from_file_location("train8", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load {module_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def load_samples(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    samples = []
    seen = set()
    for item in data["samples"]:
        if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
            continue
        vector = item.get("vector", {})
        if set(vector.keys()) != set(DIMS):
            continue
        key = (
            item.get("nodeId"),
            item.get("trainingTextZh") or item.get("definitionZh") or item.get("definition"),
            item.get("definitionZh") or item.get("definition"),
            item.get("definitionEn"),
        )
        if key in seen:
            continue
        seen.add(key)
        sample = dict(item)
        sample["vector"] = {dim: max(0.0, min(1.0, float(vector[dim]))) for dim in DIMS}
        samples.append(sample)
    return samples


def text_of(sample: dict) -> str:
    en = sample.get("definitionEn") or sample.get("definition") or ""
    zh = sample.get("trainingTextZh") or sample.get("definitionZh") or sample.get("definition") or ""
    return f"EN: {en}\nZH: {zh}" if zh else en


def contrast_labels(vector: dict[str, float], sample: dict) -> set[str]:
    labels = set()
    p, s, tau, f = vector["p"], vector["s"], vector["tau"], vector["f"]
    v, m, e, l = vector["v"], vector["m"], vector["e"], vector["l"]
    if p <= 0.35 and s >= 0.65:
        labels.add("p_low_s_high")
    if p >= 0.65 and s <= 0.35:
        labels.add("p_high_s_low")
    if tau >= 0.65 and f <= 0.35:
        labels.add("tau_high_f_low")
    if f >= 0.65 and tau <= 0.35:
        labels.add("f_high_tau_low")
    if v <= 0.35 and p >= 0.65:
        labels.add("v_low_p_high")
    if v >= 0.65 and p <= 0.35:
        labels.add("v_high_p_low")
    if m >= 0.65 and p <= 0.35:
        labels.add("m_high_p_low")
    if p >= 0.65 and m <= 0.35:
        labels.add("p_high_m_low")
    if e <= 0.35 and l >= 0.65:
        labels.add("e_low_l_high")
    if e >= 0.65 and l <= 0.35:
        labels.add("e_high_l_low")
    if p <= 0.35 and v <= 0.35:
        labels.add("flat_low_p_low_v")

    blob = " ".join(
        str(part).lower()
        for part in (
            sample.get("scenarioId", ""),
            sample.get("nodeId", ""),
            " ".join(sample.get("tags", [])),
            sample.get("definitionEn", ""),
            sample.get("trainingTextZh", ""),
            sample.get("definitionZh", ""),
        )
    )
    for keyword in KEYWORDS:
        if keyword in blob:
            labels.add(f"kw_{keyword}")
    return labels


def rule_score(sample: dict) -> float:
    vector = sample["vector"]
    labels = contrast_labels(vector, sample)
    pair_gap = max(
        abs(vector["p"] - vector["s"]),
        abs(vector["tau"] - vector["f"]),
        abs(vector["v"] - vector["p"]),
        abs(vector["m"] - vector["p"]),
        abs(vector["e"] - vector["p"]),
    )
    extremes = sum(1 for dim in DIMS if vector[dim] <= 0.2 or vector[dim] >= 0.8)
    score = 1.2 * len([label for label in labels if not label.startswith("kw_")])
    score += 0.45 * len([label for label in labels if label.startswith("kw_")])
    score += 2.0 * pair_gap
    score += 0.15 * extremes
    return score


@torch.no_grad()
def teacher_scores(samples: list[dict], model_dir: Path, batch_size: int) -> list[float]:
    if not torch.cuda.is_available():
        raise SystemExit("CUDA is required; refusing to score on CPU")
    training = load_training_module()
    metadata = json.loads((model_dir / "metadata.json").read_text(encoding="utf-8"))
    text_model = SentenceTransformer(str(model_dir / "text_encoder"), device="cuda")
    text_model.max_seq_length = 192
    text_model.eval()
    projector = training.VectorProjector(metadata["embeddingDim"]).to("cuda")
    projector.load_state_dict(torch.load(model_dir / "vector_projector.pt", map_location="cuda"))
    projector.eval()

    scores = []
    for start in range(0, len(samples), batch_size):
        batch = samples[start : start + batch_size]
        texts = [text_of(sample) for sample in batch]
        vectors = torch.tensor([[sample["vector"][dim] for dim in DIMS] for sample in batch], dtype=torch.float32, device="cuda")
        features = text_model.tokenize(texts)
        features = {key: value.to("cuda") if hasattr(value, "to") else value for key, value in features.items()}
        text_embeddings = torch.nn.functional.normalize(text_model(features)["sentence_embedding"].float(), dim=-1)
        vector_embeddings = projector(vectors)
        scores.extend((vector_embeddings * text_embeddings).sum(dim=1).detach().cpu().tolist())
    return [float(score) for score in scores]


def quantile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * q)))
    return ordered[index]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="data/training/codebook.projector-hardcases.json")
    parser.add_argument("--large", default="data/training/codebook.projector-hardcases-large.json")
    parser.add_argument("--teacher", default="data/models/codebook-base-model-hardcase-full-ft-b24")
    parser.add_argument("--output", default="data/training/codebook.selected-hardcases-8d.json")
    parser.add_argument("--report", default="data/training/codebook.selected-hardcases-8d.report.json")
    parser.add_argument("--target", type=int, default=4200)
    parser.add_argument("--batch-size", type=int, default=128)
    args = parser.parse_args()

    base = load_samples(Path(args.base))
    large = load_samples(Path(args.large))
    base_keys = {
        (
            sample.get("nodeId"),
            sample.get("trainingTextZh") or sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionEn"),
        )
        for sample in base
    }
    candidates = [
        sample
        for sample in large
        if (
            sample.get("nodeId"),
            sample.get("trainingTextZh") or sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionEn"),
        )
        not in base_keys
    ]

    scores = teacher_scores(candidates, Path(args.teacher), args.batch_size)
    scored = []
    score_values = []
    for sample, teacher_score in zip(candidates, scores, strict=True):
        rscore = rule_score(sample)
        labels = contrast_labels(sample["vector"], sample)
        if rscore < 1.5 and not labels:
            continue
        score_values.append(teacher_score)
        scored.append(
            {
                "sample": sample,
                "teacherScore": teacher_score,
                "ruleScore": rscore,
                "labels": sorted(labels),
            },
        )

    hard_threshold = quantile(score_values, 0.45)
    for item in scored:
        hardness = max(0.0, hard_threshold - item["teacherScore"]) * 10.0
        item["selectionScore"] = item["ruleScore"] + hardness

    target_extra = max(0, args.target - len(base))
    by_scenario: dict[str, list[dict]] = defaultdict(list)
    for item in scored:
        scenario = str(item["sample"].get("scenarioId") or "unknown")
        by_scenario[scenario].append(item)
    for items in by_scenario.values():
        items.sort(key=lambda item: item["selectionScore"], reverse=True)

    selected_items = []
    scenario_cap = max(8, math.ceil(target_extra / max(1, len(by_scenario)) * 2.2))
    for scenario, items in by_scenario.items():
        selected_items.extend(items[:scenario_cap])

    selected_items.sort(key=lambda item: item["selectionScore"], reverse=True)
    selected_items = selected_items[:target_extra]
    selected_samples = base + [item["sample"] for item in selected_items]

    Path(args.output).write_text(
        json.dumps({"samples": selected_samples}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    label_counter = Counter(label for item in selected_items for label in item["labels"])
    scenario_counter = Counter(str(item["sample"].get("scenarioId") or "unknown") for item in selected_items)
    report = {
        "baseSamples": len(base),
        "candidateSamples": len(candidates),
        "selectedExtraSamples": len(selected_items),
        "totalSamples": len(selected_samples),
        "nodes": len({sample["nodeId"] for sample in selected_samples}),
        "teacher": args.teacher,
        "teacherScoreQuantiles": {
            "q10": quantile(score_values, 0.10),
            "q45": hard_threshold,
            "q50": quantile(score_values, 0.50),
            "q90": quantile(score_values, 0.90),
        },
        "topLabels": label_counter.most_common(40),
        "topScenarios": scenario_counter.most_common(40),
    }
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
