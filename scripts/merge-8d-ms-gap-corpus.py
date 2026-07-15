from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def expand_raw(path: Path) -> list[dict]:
    samples = []
    for line in path.read_text(encoding="utf-8").splitlines() if path.exists() else []:
        if not line.strip():
            continue
        obj = json.loads(line)
        scenario = obj.get("scenario", "MS_GAP")
        for item in obj.get("items", []):
            vector = item.get("vector", {})
            if set(vector.keys()) != set(DIMS):
                continue
            base = {
                "nodeId": str(item.get("nodeId")),
                "definition": str(item.get("definitionEn") or ""),
                "definitionEn": str(item.get("definitionEn") or ""),
                "definitionZh": str(item.get("definitionZh") or ""),
                "tags": [str(tag) for tag in item.get("tags", [])],
                "vector": {dim: float(vector[dim]) for dim in DIMS},
                "scenarioId": str(item.get("scenarioId") or scenario),
                "source": "ms_gap_generated",
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
    parser.add_argument("--base", default="data/training/codebook.selected-hardcases-8d-vmf-balanced.json")
    parser.add_argument("--raw", default="data/training/codebook.ms-gap.raw.jsonl")
    parser.add_argument("--output", default="data/training/codebook.selected-hardcases-8d-vmf-ms-balanced.json")
    parser.add_argument("--report", default="data/training/codebook.selected-hardcases-8d-vmf-ms-balanced.report.json")
    args = parser.parse_args()
    merged = []
    seen = set()
    base = json.loads(Path(args.base).read_text(encoding="utf-8"))["samples"]
    extra = expand_raw(Path(args.raw))
    for sample in base + extra:
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
    report = {"samples": len(merged), "extraSamples": len(extra), "nodes": len({s["nodeId"] for s in merged})}
    for dim in DIMS:
        vals = [float(sample["vector"][dim]) for sample in merged]
        report[dim] = {
            "mean": sum(vals) / len(vals),
            "low": sum(v <= 0.3 for v in vals),
            "mid": sum(0.3 < v < 0.7 for v in vals),
            "high": sum(v >= 0.7 for v in vals),
        }
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
