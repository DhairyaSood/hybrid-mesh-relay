#!/usr/bin/env python3
"""Audit label/language balance, duplicates, and scenario groups for synthetic JSONL."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def jaccard_tokens(a: str, b: str) -> float:
    sa, sb = set(norm(a).split()), set(norm(b).split())
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def audit(rows: list[dict], cfg: dict) -> dict:
    n = len(rows)
    labels = Counter(r["label"] for r in rows)
    sub = Counter(r["language_subtype"] for r in rows)
    groups = Counter(r.get("scenario_group", "legacy_v1") for r in rows)
    families = Counter(r["template_family_id"] for r in rows)

    texts = [norm(r["message_text"]) for r in rows]
    exact_dup = n - len(set(texts))

    # Near-duplicate scan (sampled pairs within same label — cap work)
    near_pairs = 0
    by_label: dict[str, list[str]] = {}
    for r in rows:
        by_label.setdefault(r["label"], []).append(r["message_text"])
    for lab, msgs in by_label.items():
        sample = msgs[:80]
        for i in range(len(sample)):
            for j in range(i + 1, len(sample)):
                if jaccard_tokens(sample[i], sample[j]) >= 0.92:
                    near_pairs += 1

    return {
        "row_count": n,
        "expected_total": cfg.get("total_messages"),
        "label_counts": dict(sorted(labels.items())),
        "language_subtype_counts": dict(sorted(sub.items())),
        "english_percent": round(100 * sub.get("english", 0) / n, 2) if n else 0,
        "devanagari_percent": round(100 * sub.get("devanagari_hindi", 0) / n, 2) if n else 0,
        "latin_hinglish_percent": round(100 * sub.get("latin_hinglish", 0) / n, 2) if n else 0,
        "scenario_group_counts": dict(sorted(groups.items())),
        "unique_template_families": len(families),
        "exact_duplicate_texts": exact_dup,
        "near_duplicate_pairs_sampled_same_label_jaccard_ge_0.92": near_pairs,
        "scenario_sets_config": cfg.get("scenario_sets"),
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--config", type=Path, required=True)
    p.add_argument("--dataset", type=Path, default=None)
    p.add_argument("--out", type=Path, default=None)
    args = p.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    out_dir = ROOT / cfg["output_dir"]
    dataset = args.dataset or out_dir / cfg["dataset_filename"]
    rows = load_jsonl(dataset)
    report = audit(rows, cfg)
    print(json.dumps(report, indent=2))
    if args.out:
        args.out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
