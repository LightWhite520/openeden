from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")
SEMANTIC_LEVEL_ZH = {
    "very low": "极低",
    "low": "低",
    "moderate": "中等",
    "high": "高",
    "very high": "极高",
}
LEGACY_PADDING_ID = re.compile(r"^NODE_POWER2_8D_PAD_(\d+)$")
CANONICAL_PADDING_ID = re.compile(r"^NODE_VMF_BOUNDARY_[A-Z0-9_]+_(\d+)$")
BASE_COUNTS = {
    "codebook.selected-hardcases-8d-vmf-balanced.json": {"extraSamples": 4032},
}


def level(value: float) -> str:
    if value < 0.3:
        return "low"
    if value > 0.6:
        return "high"
    return "mid"


def semantic_level(value: float) -> str:
    if value < 0.15:
        return "very low"
    if value < 0.3:
        return "low"
    if value <= 0.6:
        return "moderate"
    if value <= 0.85:
        return "high"
    return "very high"


def definition(vector: dict[str, float]) -> tuple[str, str]:
    labels = {dim: semantic_level(vector[dim]) for dim in DIMS}
    en = (
        f"Logical clarity is {labels['l']}, emotional resonance is {labels['p']}, "
        f"self-acceptance is {labels['e']}, and system entropy is {labels['s']}. "
        f"Memory persistence is {labels['tau']}, vitality is {labels['v']}, "
        f"empathy mirroring is {labels['m']}, and fear is {labels['f']}."
    )
    zh = (
        f"逻辑清晰度为{SEMANTIC_LEVEL_ZH[labels['l']]}，情绪共鸣为{SEMANTIC_LEVEL_ZH[labels['p']]}，"
        f"自我接纳为{SEMANTIC_LEVEL_ZH[labels['e']]}，系统熵为{SEMANTIC_LEVEL_ZH[labels['s']]}。"
        f"记忆牵引为{SEMANTIC_LEVEL_ZH[labels['tau']]}，生命力为{SEMANTIC_LEVEL_ZH[labels['v']]}，"
        f"共情映射为{SEMANTIC_LEVEL_ZH[labels['m']]}，恐惧水平为{SEMANTIC_LEVEL_ZH[labels['f']]}。"
    )
    return en, zh


def padding_node_id(vector: dict[str, float], index: int) -> str:
    labels = {dim: level(vector[dim]).upper() for dim in ("p", "tau", "v", "f")}
    return (
        f"NODE_VMF_BOUNDARY_P_{labels['p']}_TAU_{labels['tau']}_"
        f"V_{labels['v']}_F_{labels['f']}_{index:03d}"
    )


def padding_sample(vector: dict[str, float], index: int) -> dict:
    en, zh = definition(vector)
    return {
        "nodeId": padding_node_id(vector, index),
        "definition": en,
        "definitionEn": en,
        "definitionZh": zh,
        "tags": ["vmf_boundary", "vmf_gap", "hardcase_8d", "definition"],
        "vector": dict(vector),
    }


def canonicalize_padding_nodes(data: dict) -> int:
    migrated = 0
    generated_index = 0
    for sample in data["samples"]:
        node_id = str(sample["nodeId"])
        legacy_match = LEGACY_PADDING_ID.fullmatch(node_id)
        canonical_match = CANONICAL_PADDING_ID.fullmatch(node_id)
        tags = sample.get("tags", [])
        is_padding = (
            legacy_match is not None
            or canonical_match is not None
            or "power2_padding" in tags
            or "vmf_boundary" in tags
        )
        if not is_padding:
            continue
        generated_index += 1
        indexed_match = legacy_match or canonical_match
        index = int(indexed_match.group(1)) if indexed_match is not None else generated_index
        vector = sample["vector"]
        sample.update(padding_sample(vector, index))
        migrated += 1
    return migrated


def generated_vector(index: int) -> dict[str, float]:
    p_levels = [0.08, 0.18, 0.34, 0.52, 0.72, 0.91]
    tau_levels = [0.07, 0.22, 0.41, 0.63, 0.82, 0.95]
    v_levels = [0.06, 0.19, 0.38, 0.57, 0.76, 0.94]
    f_levels = [0.05, 0.17, 0.33, 0.55, 0.79, 0.96]
    l_levels = [0.12, 0.29, 0.47, 0.66, 0.84, 0.97]
    e_levels = [0.09, 0.26, 0.44, 0.61, 0.78, 0.93]
    s_levels = [0.04, 0.21, 0.39, 0.58, 0.74, 0.92]
    m_levels = [0.11, 0.28, 0.46, 0.64, 0.81, 0.95]

    return {
        "l": l_levels[(index // 9 + index * 2 + 1) % len(l_levels)],
        "p": p_levels[index % len(p_levels)],
        "e": e_levels[(index // 5 + 2) % len(e_levels)],
        "s": s_levels[(index // 7 + 3) % len(s_levels)],
        "tau": tau_levels[(index // len(p_levels)) % len(tau_levels)],
        "v": v_levels[(index // (len(p_levels) * len(tau_levels))) % len(v_levels)],
        "m": m_levels[(index // 11 + 5) % len(m_levels)],
        "f": f_levels[(index // 18 + 2) % len(f_levels)],
    }


def report_for(data: dict, input_name: str) -> dict:
    samples = data["samples"]
    report: dict[str, object] = {
        "samples": len(samples),
        "extraSamples": BASE_COUNTS.get(input_name, {}).get("extraSamples", 0)
        + sum(
            1
            for sample in samples
            if {"power2_padding", "vmf_boundary"}.intersection(sample.get("tags", []))
        ),
        "nodes": len({sample["nodeId"] for sample in samples}),
    }
    for dim in DIMS:
        values = [float(sample["vector"][dim]) for sample in samples]
        report[dim] = {
            "mean": sum(values) / len(values),
            "low": sum(1 for value in values if value < 0.3),
            "mid": sum(1 for value in values if 0.3 <= value <= 0.6),
            "high": sum(1 for value in values if value > 0.6),
        }
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--target", type=int, default=2048)
    args = parser.parse_args()

    input_path = Path(args.input)
    data = json.loads(input_path.read_text(encoding="utf-8"))
    migrated = canonicalize_padding_nodes(data)
    nodes = {str(sample["nodeId"]) for sample in data["samples"]}
    if len(nodes) > args.target:
        raise ValueError(f"Corpus already has {len(nodes)} nodes, above target {args.target}")

    needed = args.target - len(nodes)
    for offset in range(needed):
        index = migrated + offset + 1
        vector = generated_vector(index - 1)
        sample = padding_sample(vector, index)
        node_id = sample["nodeId"]
        if node_id in nodes:
            raise ValueError(f"Generated node already exists: {node_id}")
        data["samples"].append(sample)
        nodes.add(node_id)

    Path(args.output).write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    Path(args.report).write_text(
        json.dumps(report_for(data, input_path.name), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"added": needed, "migrated": migrated, "nodes": len(nodes), "samples": len(data["samples"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
