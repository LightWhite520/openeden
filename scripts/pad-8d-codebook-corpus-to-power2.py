from __future__ import annotations

import argparse
import json
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")
BASE_COUNTS = {
    "codebook.selected-hardcases-8d-vmf-balanced.json": {"extraSamples": 4032},
}


def level(value: float) -> str:
    if value < 0.3:
        return "low"
    if value > 0.6:
        return "high"
    return "mid"


def definition(vector: dict[str, float], index: int) -> tuple[str, str]:
    labels = {dim: level(vector[dim]) for dim in DIMS}
    en = (
        f"Power-of-two supplemental 8D state {index:03d}: "
        f"logical clarity is {labels['l']}, emotional resonance is {labels['p']}, "
        f"self-acceptance is {labels['e']}, entropy is {labels['s']}, "
        f"memory persistence is {labels['tau']}, vitality is {labels['v']}, "
        f"empathy mirroring is {labels['m']}, and fear is {labels['f']}. "
        "This node is used to make sparse mixed P/tau/V/F boundary regions explicit."
    )
    zh = (
        f"2 的整数次幂补充 8D 状态 {index:03d}："
        f"逻辑清晰度为{labels['l']}，情绪共鸣为{labels['p']}，"
        f"自我接纳为{labels['e']}，系统熵为{labels['s']}，"
        f"记忆牵引为{labels['tau']}，生命力为{labels['v']}，"
        f"共情映射为{labels['m']}，恐惧水平为{labels['f']}。"
        "该节点用于显式覆盖稀疏的 P/tau/V/F 混合边界区域。"
    )
    return en, zh


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
        + sum(1 for sample in samples if "power2_padding" in sample.get("tags", [])),
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
    nodes = {str(sample["nodeId"]) for sample in data["samples"]}
    if len(nodes) > args.target:
        raise ValueError(f"Corpus already has {len(nodes)} nodes, above target {args.target}")

    needed = args.target - len(nodes)
    for offset in range(needed):
        node_id = f"NODE_POWER2_8D_PAD_{offset + 1:03d}"
        if node_id in nodes:
            raise ValueError(f"Generated node already exists: {node_id}")
        vector = generated_vector(offset)
        en, zh = definition(vector, offset + 1)
        data["samples"].append(
            {
                "nodeId": node_id,
                "definition": en,
                "definitionEn": en,
                "definitionZh": zh,
                "tags": ["power2_padding", "vmf_gap", "hardcase_8d", "definition"],
                "vector": vector,
            },
        )
        nodes.add(node_id)

    Path(args.output).write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    Path(args.report).write_text(
        json.dumps(report_for(data, input_path.name), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"added": needed, "nodes": len(nodes), "samples": len(data["samples"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
