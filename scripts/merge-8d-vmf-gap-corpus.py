from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def vector_ok(vector: dict) -> bool:
    return set(vector.keys()) == set(DIMS) and all(0.0 <= float(vector[dim]) <= 1.0 for dim in DIMS)


def expand_raw(path: Path) -> list[dict]:
    samples = []
    if not path.exists():
        return samples
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        scenario = obj.get("scenario", "VMF_GAP")
        for item in obj.get("items", []):
            if not isinstance(item, dict):
                continue
            vector = item.get("vector", {})
            if not vector_ok(vector):
                continue
            if "dissonance" in json.dumps(item, ensure_ascii=False).lower():
                continue
            base = {
                "nodeId": str(item.get("nodeId")),
                "definition": str(item.get("definitionEn") or item.get("definition") or ""),
                "definitionEn": str(item.get("definitionEn") or item.get("definition") or ""),
                "definitionZh": str(item.get("definitionZh") or ""),
                "tags": [str(tag) for tag in item.get("tags", [])],
                "vector": {dim: float(vector[dim]) for dim in DIMS},
                "scenarioId": str(item.get("scenarioId") or scenario),
                "source": "vmf_gap_generated",
            }
            variants = [str(item.get("definitionZh") or "")]
            variants.extend(str(text) for text in item.get("textVariantsZh", []) if str(text).strip())
            for index, text in enumerate(dict.fromkeys(text for text in variants if text.strip())):
                sample = dict(base)
                sample["trainingTextZh"] = text
                sample["variantIndex"] = index
                samples.append(sample)
    return samples


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--selected", default="data/training/codebook.selected-hardcases-8d.json")
    parser.add_argument("--raw", default="data/training/codebook.vmf-gap.raw.jsonl")
    parser.add_argument("--output", default="data/training/codebook.selected-hardcases-8d-vmf-balanced.json")
    parser.add_argument("--report", default="data/training/codebook.selected-hardcases-8d-vmf-balanced.report.json")
    args = parser.parse_args()

    selected = json.loads(Path(args.selected).read_text(encoding="utf-8"))["samples"]
    extra = expand_raw(Path(args.raw))
    merged = []
    seen = set()
    for sample in selected + extra:
        key = (
            sample.get("nodeId"),
            sample.get("trainingTextZh") or sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionEn"),
        )
        if key in seen:
            continue
        seen.add(key)
        merged.append(sample)

    Path(args.output).write_text(json.dumps({"samples": merged}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    stats = {"samples": len(merged), "extraSamples": len(extra), "nodes": len({sample["nodeId"] for sample in merged})}
    for dim in DIMS:
        vals = [float(sample["vector"][dim]) for sample in merged]
        stats[dim] = {
            "mean": sum(vals) / len(vals),
            "low": sum(value <= 0.3 for value in vals),
            "mid": sum(0.3 < value < 0.7 for value in vals),
            "high": sum(value >= 0.7 for value in vals),
        }
    Path(args.report).write_text(json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
