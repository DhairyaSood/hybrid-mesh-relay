#!/usr/bin/env python3
"""Inter-annotator agreement between two annotation JSONL files."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path


def load_ann(path: Path) -> dict[str, dict]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        out[row["sample_id"]] = row
    return out


def cohens_kappa(y1: list[str], y2: list[str]) -> float:
    if not y1:
        return 0.0
    labels = sorted(set(y1) | set(y2))
    n = len(y1)
    agree = sum(a == b for a, b in zip(y1, y2))
    po = agree / n
    c1 = Counter(y1)
    c2 = Counter(y2)
    pe = sum((c1[l] / n) * (c2[l] / n) for l in labels)
    if pe == 1.0:
        return 1.0
    return (po - pe) / (1 - pe)


def jaccard(a: set[str], b: set[str]) -> float:
    if not a and not b:
        return 1.0
    return len(a & b) / len(a | b)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if len(args.annotations) != 2:
        raise SystemExit("Provide exactly two --annotations files")

    a = load_ann(args.annotations[0])
    b = load_ann(args.annotations[1])
    common = sorted(set(a) & set(b))
    prim_a = [a[s].get("primary_content_label") for s in common]
    prim_b = [b[s].get("primary_content_label") for s in common]
    prim_a = [x or "M7" for x in prim_a]
    prim_b = [x or "M7" for x in prim_b]

    agree = sum(x == y for x, y in zip(prim_a, prim_b)) / len(common) if common else 0
    kappa = cohens_kappa(prim_a, prim_b)

    jaccards = []
    for s in common:
        sa = set(a[s].get("content_labels") or [])
        sb = set(b[s].get("content_labels") or [])
        jaccards.append(jaccard(sa, sb))
    mean_jaccard = sum(jaccards) / len(jaccards) if jaccards else 0

    confusion = Counter((x, y) for x, y in zip(prim_a, prim_b))

    report = {
        "n_common_samples": len(common),
        "primary_label_percent_agreement": agree,
        "cohens_kappa_primary": kappa,
        "mean_jaccard_content_labels": mean_jaccard,
        "primary_confusion_pairs": {f"{k[0]}|{k[1]}": v for k, v in confusion.items()},
        "annotator_files": [str(p) for p in args.annotations],
        "target_kappa": 0.55,
        "meets_pilot_kappa_target": kappa >= 0.55,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
