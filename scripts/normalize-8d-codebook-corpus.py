from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")


def normalize_text(value: object) -> str:
    return " ".join(str(value or "").split())


def normalize_runtime_definition(value: object) -> str:
    text = normalize_text(value)
    text = text.replace("确认我理解", "确认说话者理解")
    text = text.replace("只是我不", "只是说话者不")
    text = text.replace("对我来说", "对说话者来说")
    text = text.replace("这对我", "这对说话者")
    if not text.startswith("我"):
        return text
    text = text.replace("你的", "用户的").replace("你现在", "用户当前").replace("你", "用户")
    text = text.replace("别人", "其他人").replace("自己", "自身")
    replacements = (
        ("我会", "说话者会"),
        ("我在", "说话者在"),
        ("我是", "说话者是"),
        ("我有", "说话者有"),
        ("我心里", "说话者心里"),
        ("我语气", "说话者语气"),
        ("我说话", "说话者说话"),
        ("我表面", "说话者表面"),
        ("我还是", "说话者仍然"),
        ("我就是", "说话者处在一种"),
        ("我更", "说话者更"),
        ("我先", "说话者先"),
        ("我提", "说话者提"),
        ("我不会", "说话者不会"),
        ("我不停", "说话者不停"),
        ("我承认", "说话者承认"),
        ("我虽然", "说话者虽然"),
        ("我对", "说话者对"),
        ("我知道", "说话者知道"),
        ("我没事", "说话者表示自身没事"),
        ("我已经", "说话者已经"),
        ("我真是", "说话者以带讽刺的方式表示"),
        ("我快", "说话者快"),
    )
    for prefix, replacement in replacements:
        if text.startswith(prefix):
            return replacement + text[len(prefix):]
    return "说话者" + text[1:]


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
                "definitionZh": normalize_runtime_definition(sample.get("definitionZh")),
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
