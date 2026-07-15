from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def normalize_text(value: object) -> str:
    return " ".join(str(value or "").split())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    canonical: dict[str, dict] = {}
    for sample in data["samples"]:
        node_id = str(sample["nodeId"])
        vector = sample.get("vector", {})
        if set(vector.keys()) != set(DIMS):
            raise ValueError(f"Invalid vector keys for {node_id}: {vector.keys()}")
        canonical.setdefault(
            node_id,
            {
                "definition": normalize_text(
                    sample.get("definitionEn") or sample.get("definition") or sample.get("definitionZh")
                ),
                "definitionEn": normalize_text(sample.get("definitionEn") or sample.get("definition")),
                "definitionZh": normalize_text(sample.get("definitionZh")),
                "tags": list(dict.fromkeys(normalize_text(tag) for tag in sample.get("tags", []) if normalize_text(tag))),
            },
        )

    for sample in data["samples"]:
        meta = canonical[str(sample["nodeId"])]
        sample["definition"] = meta["definition"]
        sample["definitionEn"] = meta["definitionEn"]
        sample["definitionZh"] = meta["definitionZh"]
        sample["tags"] = meta["tags"]

    Path(args.output).write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"samples": len(data["samples"]), "nodes": len(canonical)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
