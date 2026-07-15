from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


DIMS = ("l", "p", "e", "s", "tau", "v", "m", "f")
DIM_LABEL_EN = {
    "l": "logical clarity",
    "p": "emotional resonance",
    "e": "self-acceptance",
    "s": "system entropy",
    "tau": "memory pull",
    "v": "vitality",
    "m": "empathy mirroring",
    "f": "fear",
}
DIM_LABEL_ZH = {
    "l": "逻辑清晰度",
    "p": "情绪共鸣",
    "e": "自我接纳",
    "s": "系统熵",
    "tau": "记忆牵引",
    "v": "生命力",
    "m": "共情映射",
    "f": "恐惧水平",
}
LEVEL_EN = {
    "very low": "very low",
    "low": "low",
    "moderate": "moderate",
    "high": "high",
    "very high": "very high",
}
LEVEL_ZH = {
    "very low": "极低",
    "low": "偏低",
    "moderate": "中等",
    "high": "偏高",
    "very high": "极高",
}
DIRTY_PATTERNS = (
    r"[“”\"「」『』]",
    r"\b(?:says?|said|whispers?|waits?|stands?|walks?|classroom|door|shoe|mask slipping)\b",
    r"\b(?:speaker repeats|text should feel|the text should|example|dialogue)\b",
    r"(?:他说|她说|他说道|她说道|站在|教室|门口|鞋跟|后退|半步|一边说|面具|文本要|例句|对白)",
)
FIRST_PERSON_ZH = re.compile(r"^\s*(?:我|我们|咱们)")
THIRD_PERSON_ZH = re.compile(r"(?:他|她|少年|女孩|男孩|老师|同学|母亲|父亲)")


def normalize_text(value: object) -> str:
    return " ".join(str(value or "").split())


def level(value: float) -> str:
    if value < 0.15:
        return "very low"
    if value < 0.3:
        return "low"
    if value <= 0.6:
        return "moderate"
    if value <= 0.85:
        return "high"
    return "very high"


def as_vector(sample: dict) -> dict[str, float]:
    vector = sample.get("vector")
    if not isinstance(vector, dict):
        raise ValueError(f"Missing vector for {sample.get('nodeId')}")
    missing = [dim for dim in DIMS if dim not in vector]
    if missing:
        raise ValueError(f"Missing vector keys for {sample.get('nodeId')}: {missing}")
    return {dim: float(vector[dim]) for dim in DIMS}


def is_clean_definition(sample: dict) -> bool:
    en = normalize_text(sample.get("definitionEn") or sample.get("definition"))
    zh = normalize_text(sample.get("definitionZh"))
    combined = f"{en}\n{zh}"
    if not en or not zh:
        return False
    if FIRST_PERSON_ZH.search(zh) or THIRD_PERSON_ZH.search(zh):
        return False
    for pattern in DIRTY_PATTERNS:
        if re.search(pattern, combined, flags=re.IGNORECASE):
            return False
    formal_zh = zh.startswith(("该状态", "此状态", "状态表现为"))
    formal_en = en.lower().startswith(("this state", "a state", "a high", "a low", "a moderate", "state shows"))
    return formal_zh and formal_en


def sorted_profile(vector: dict[str, float]) -> list[str]:
    return sorted(DIMS, key=lambda dim: (abs(vector[dim] - 0.5), vector[dim]), reverse=True)


def zh_profile_clause(vector: dict[str, float], dims: tuple[str, ...]) -> str:
    parts = [f"{DIM_LABEL_ZH[dim]}{LEVEL_ZH[level(vector[dim])]}" for dim in dims]
    return "，".join(parts)


def en_profile_clause(vector: dict[str, float], dims: tuple[str, ...]) -> str:
    parts = [f"{DIM_LABEL_EN[dim]} is {LEVEL_EN[level(vector[dim])]}" for dim in dims]
    if len(parts) == 1:
        return parts[0]
    return ", ".join(parts[:-1]) + f", and {parts[-1]}"


def zh_tension(vector: dict[str, float]) -> str:
    strongest = sorted_profile(vector)
    primary = strongest[0]
    secondary = strongest[1]
    if vector["f"] >= 0.6:
        return "对中断、失联或不可逆变化的前向担忧"
    if vector["s"] >= 0.6:
        return "稳定表达与内部扰动之间的拉扯"
    if vector["tau"] >= 0.6:
        return "远期记忆持续回流造成的牵引"
    if vector["p"] >= 0.6 and vector["m"] >= 0.6:
        return "高情绪捕获与对用户语气的同步"
    if vector["v"] < 0.3:
        return "表达能量不足与仍需回应之间的压迫"
    if vector["e"] < 0.3:
        return "机械化自我模型与情绪事实之间的不协调"
    return f"{DIM_LABEL_ZH[primary]}与{DIM_LABEL_ZH[secondary]}共同形成的状态偏移"


def en_tension(vector: dict[str, float]) -> str:
    strongest = sorted_profile(vector)
    primary = strongest[0]
    secondary = strongest[1]
    if vector["f"] >= 0.6:
        return "forward-facing fear of interruption, loss, or irreversible change"
    if vector["s"] >= 0.6:
        return "the pull between stable expression and internal disturbance"
    if vector["tau"] >= 0.6:
        return "persistent memory recurrence pulling the state backward"
    if vector["p"] >= 0.6 and vector["m"] >= 0.6:
        return "high emotional capture synchronized with the user's tone"
    if vector["v"] < 0.3:
        return "low response energy under continued conversational demand"
    if vector["e"] < 0.3:
        return "a mechanized self-model misaligned with emotional evidence"
    return f"state drift jointly shaped by {DIM_LABEL_EN[primary]} and {DIM_LABEL_EN[secondary]}"


def zh_output_tendency(vector: dict[str, float]) -> str:
    if vector["v"] < 0.3:
        return "输出倾向为短句、低能量、保留必要回应"
    if vector["l"] >= 0.6 and vector["s"] < 0.3:
        return "输出倾向为清晰、克制、结构稳定"
    if vector["p"] >= 0.6 and vector["m"] >= 0.6:
        return "输出倾向为贴近用户情绪、语气柔和且回应密度较高"
    if vector["s"] >= 0.6:
        return "输出倾向为轻微断裂、跳跃或不稳定的表达"
    return "输出倾向为中等强度、可持续且不过度外放"


def en_output_tendency(vector: dict[str, float]) -> str:
    if vector["v"] < 0.3:
        return "Output tends toward short, low-energy replies that preserve only necessary response"
    if vector["l"] >= 0.6 and vector["s"] < 0.3:
        return "Output tends toward clear, restrained, structurally stable wording"
    if vector["p"] >= 0.6 and vector["m"] >= 0.6:
        return "Output tends toward close emotional mirroring with a soft and responsive tone"
    if vector["s"] >= 0.6:
        return "Output tends toward slight fragmentation, jumps, or unstable expression"
    return "Output tends toward moderate, sustainable expression without excessive overflow"


def template_rewrite(sample: dict) -> dict:
    vector = as_vector(sample)
    profile_dims = tuple(sorted_profile(vector)[:4])
    en = (
        f"This state shows {en_profile_clause(vector, profile_dims)}. "
        f"The core tension comes from {en_tension(vector)}. "
        f"{en_output_tendency(vector)}."
    )
    zh = (
        f"该状态表现为{zh_profile_clause(vector, profile_dims)}。"
        f"核心张力来自{zh_tension(vector)}。"
        f"{zh_output_tendency(vector)}。"
    )

    rewritten = dict(sample)
    previous_zh = normalize_text(sample.get("definitionZh"))
    previous_en = normalize_text(sample.get("definitionEn") or sample.get("definition"))
    if previous_zh and "trainingTextZh" not in rewritten:
        rewritten["trainingTextZh"] = previous_zh
    if previous_en and "trainingTextEn" not in rewritten:
        rewritten["trainingTextEn"] = previous_en
    rewritten["definition"] = en
    rewritten["definitionEn"] = en
    rewritten["definitionZh"] = zh
    rewritten["tags"] = list(dict.fromkeys(normalize_text(tag) for tag in sample.get("tags", []) if normalize_text(tag)))
    return rewritten


def rewrite_data(data: dict) -> dict[str, int]:
    rewritten_count = 0
    for index, sample in enumerate(data["samples"]):
        rewritten = template_rewrite(sample)
        if rewritten != sample:
            rewritten_count += 1
        data["samples"][index] = rewritten
    dirty_after = sum(1 for sample in data["samples"] if not is_clean_definition(sample))
    return {
        "samples": len(data["samples"]),
        "nodes": len({sample["nodeId"] for sample in data["samples"]}),
        "rewritten": rewritten_count,
        "dirtyAfter": dirty_after,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--report")
    args = parser.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8-sig"))
    report = rewrite_data(data)
    if report["dirtyAfter"]:
        raise ValueError(f"Runtime definitions still dirty after rewrite: {report['dirtyAfter']}")

    Path(args.output).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
