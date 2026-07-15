from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="data/training/codebook.selected-hardcases-8d-vmf-balanced.json")
    parser.add_argument("--output", default="data/training/codebook.selected-vmf-runtime-artifact.json")
    args = parser.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    groups: dict[str, list[dict]] = defaultdict(list)
    for sample in data["samples"]:
        vector = sample.get("vector", {})
        if set(vector.keys()) != set(DIMS):
            raise ValueError(f"Invalid vector keys for {sample.get('nodeId')}: {vector.keys()}")
        if "dissonance" in json.dumps(sample, ensure_ascii=False).lower():
            raise ValueError(f"Sample contains derived dissonance text: {sample.get('nodeId')}")
        groups[str(sample["nodeId"])].append(sample)

    collapsed = []
    for node_id, samples in sorted(groups.items()):
        first = samples[0]
        vector = {
            dim: sum(float(sample["vector"][dim]) for sample in samples) / len(samples)
            for dim in DIMS
        }
        collapsed.append(
            {
                "nodeId": node_id,
                "definition": first.get("definitionEn") or first.get("definition") or first.get("definitionZh"),
                "definitionEn": first.get("definitionEn") or first.get("definition") or "",
                "definitionZh": first.get("definitionZh") or "",
                "tags": first.get("tags", []),
                "vector": vector,
            },
        )

    Path(args.output).write_text(
        json.dumps({"samples": collapsed}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"samples": len(collapsed), "sourceSamples": len(data["samples"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
