from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
import re


BOUNDARY_PREFIX = "NODE_VMF_BOUNDARY_"


def is_removed_node(node_id: object, remove_prefixes: tuple[str, ...]) -> bool:
    return str(node_id).startswith(remove_prefixes)


def replacement_priority(node_id: str, samples: list[dict]) -> tuple[int, int, str]:
    if node_id.startswith("NODE_MS_"):
        family = 0
    elif node_id.startswith("NODE_BIG_"):
        family = 1
    elif node_id.startswith("NODE_EXTRA_"):
        family = 2
    else:
        family = 3
    return (family, -len(samples), node_id)


def family_key(node_id: str) -> str:
    without_variant = re.sub(r"_\d{2}(?:_\d{2})?$", "", node_id)
    return re.sub(r"_\d{3}_\d{2}$", "", without_variant)


def grouped_by_node(samples: list[dict]) -> dict[str, list[dict]]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for sample in samples:
        groups[str(sample["nodeId"])].append(sample)
    return groups


def select_replacements(candidates: list[tuple[str, list[dict]]], needed: int) -> list[tuple[str, list[dict]]]:
    by_family: dict[str, list[tuple[str, list[dict]]]] = defaultdict(list)
    for node_id, samples in candidates:
        by_family[family_key(node_id)].append((node_id, samples))
    for items in by_family.values():
        items.sort(key=lambda item: replacement_priority(item[0], item[1]))

    selected = []
    while len(selected) < needed and by_family:
        for family in sorted(by_family):
            items = by_family[family]
            if not items:
                continue
            selected.append(items.pop(0))
            if len(selected) == needed:
                break
        by_family = {family: items for family, items in by_family.items() if items}
    return selected


def replace_boundary_nodes(
    target: dict,
    source: dict,
    remove_prefixes: tuple[str, ...] = (BOUNDARY_PREFIX,),
) -> dict[str, int]:
    target_samples = target["samples"]
    existing_nodes = {str(sample["nodeId"]) for sample in target_samples}
    removed_nodes = {node_id for node_id in existing_nodes if is_removed_node(node_id, remove_prefixes)}
    kept = [sample for sample in target_samples if not is_removed_node(sample["nodeId"], remove_prefixes)]
    kept_nodes = {str(sample["nodeId"]) for sample in kept}

    source_groups = grouped_by_node(source["samples"])
    candidates = [
        (node_id, samples)
        for node_id, samples in source_groups.items()
        if node_id not in kept_nodes and not is_removed_node(node_id, (BOUNDARY_PREFIX,))
    ]
    candidates.sort(key=lambda item: replacement_priority(item[0], item[1]))

    needed = len(removed_nodes)
    if len(candidates) < needed:
        raise ValueError(f"Only {len(candidates)} replacement nodes available for {needed} removed nodes")

    replacements = []
    for _, samples in select_replacements(candidates, needed):
        replacements.extend(dict(sample) for sample in samples)

    target["samples"] = kept + replacements
    return {
        "removedBoundaryNodes": len(removed_nodes),
        "addedReplacementNodes": needed,
        "samples": len(target["samples"]),
        "nodes": len({sample["nodeId"] for sample in target["samples"]}),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--remove-prefix", action="append", default=None)
    args = parser.parse_args()

    target = json.loads(Path(args.target).read_text(encoding="utf-8"))
    source = json.loads(Path(args.source).read_text(encoding="utf-8"))
    remove_prefixes = tuple(args.remove_prefix or [BOUNDARY_PREFIX])
    report = replace_boundary_nodes(target, source, remove_prefixes)

    Path(args.output).write_text(json.dumps(target, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
