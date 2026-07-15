from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def clean_vector(item: dict) -> dict[str, float]:
    source = item.get("vector", item)
    vector = {}
    for key in DIMS:
        if key not in source:
            raise ValueError(f"missing {key} in {item.get('nodeId')}")
        vector[key] = max(0.0, min(1.0, float(source[key])))
    return vector


def iter_raw_items(paths: list[Path]):
    for path in paths:
        if not path.exists():
            continue
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip():
                continue
            obj = json.loads(line)
            items = obj.get("items") if "items" in obj else obj.get("samples")
            if items == []:
                continue
            if not items:
                items = [obj]
            for item in items:
                item = dict(item)
                item.setdefault("scenarioId", obj.get("scenario") or item.get("scenarioId") or f"{path.stem}_{line_no}")
                yield item


def build_samples(raw_paths: list[Path]) -> list[dict]:
    samples = []
    for item in iter_raw_items(raw_paths):
        blob = json.dumps(item, ensure_ascii=False).lower()
        if "dissonance" in blob:
            raise ValueError(f"derived dissonance text in {item.get('nodeId')}")
        node_id = str(item.get("nodeId") or f"NODE_RAW_{len(samples) + 1:06d}")
        scenario = str(item.get("scenarioId") or "generated")
        vector = clean_vector(item)
        tags = [str(tag) for tag in item.get("tags", [])]
        definition_en = str(item.get("definitionEn") or item.get("definition") or "")
        definition_zh = str(item.get("definitionZh") or "")
        training_texts = [definition_zh] if definition_zh else []
        training_texts.extend(str(text) for text in item.get("textVariantsZh", []) if str(text).strip())
        if not training_texts:
            training_texts = [definition_en]
        for index, training_text in enumerate(dict.fromkeys(training_texts)):
            samples.append(
                {
                    "nodeId": node_id,
                    "definition": definition_en,
                    "definitionEn": definition_en,
                    "definitionZh": definition_zh,
                    "trainingTextZh": training_text,
                    "tags": tags,
                    "vector": vector,
                    "scenarioId": scenario,
                    "source": "large_8d_generated",
                    "variantIndex": index,
                },
            )
    return samples


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="data/training/codebook.projector-hardcases-plus-contrast.json")
    parser.add_argument("--raw", action="append", default=[])
    parser.add_argument("--augmentation", default="data/training/codebook.large-8d-augmentation.json")
    parser.add_argument("--merged", default="data/training/codebook.projector-hardcases-large.json")
    args = parser.parse_args()

    raw_paths = [Path(path) for path in args.raw] or [
        Path("data/training/codebook.large-8d-augmentation.raw.jsonl"),
        Path("data/training/codebook.large-8d-extra.raw.jsonl"),
    ]
    generated = build_samples(raw_paths)
    seen = set()
    unique_generated = []
    for sample in generated:
        key = (sample["nodeId"], sample.get("trainingTextZh"), sample["definitionZh"], sample["definitionEn"])
        if key not in seen:
            unique_generated.append(sample)
            seen.add(key)

    base_samples = read_json(Path(args.base))["samples"] if Path(args.base).exists() else []
    merged_seen = set()
    merged = []
    for sample in base_samples + unique_generated:
        key = (
            sample["nodeId"],
            sample.get("trainingTextZh") or sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionZh") or sample.get("definition"),
            sample.get("definitionEn"),
        )
        if key not in merged_seen:
            merged.append(sample)
            merged_seen.add(key)

    Path(args.augmentation).write_text(
        json.dumps({"samples": unique_generated}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    Path(args.merged).write_text(
        json.dumps({"samples": merged}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    nodes = len({sample["nodeId"] for sample in merged})
    print(json.dumps({"generatedSamples": len(unique_generated), "mergedSamples": len(merged), "mergedNodes": nodes}, ensure_ascii=False))


if __name__ == "__main__":
    main()
