#!/usr/bin/env python3
"""Check challenge suite independence vs training data and other suites."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def jaccard(a: str, b: str) -> float:
    sa, sb = set(norm(a).split()), set(norm(b).split())
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def load_jsonl(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    return [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def corpus_texts() -> list[tuple[str, str]]:
    """(source_id, normalized_text)"""
    out: list[tuple[str, str]] = []
    paths = [
        ("robustness_v1", ROOT / "eval_suites/independent_robustness_v1.jsonl"),
        ("civic_v1", ROOT / "eval_suites/civic_emergency_challenge_v1.jsonl"),
    ]
    for split_dir in (
        ROOT / "data/synthetic_misuse_10k/splits",
        ROOT / "data/synthetic_misuse_v2_20k/splits",
    ):
        for name in ("train.jsonl", "validation.jsonl", "test.jsonl"):
            p = split_dir / name
            tag = f"{split_dir.name}/{name}"
            for row in load_jsonl(p):
                out.append((tag, norm(row["message_text"])))
    for tag, p in paths:
        for row in load_jsonl(p):
            out.append((tag, norm(row["message_text"])))
    return out


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("suite", type=Path, default=ROOT / "eval_suites/civic_emergency_challenge_v2.jsonl", nargs="?")
    p.add_argument("--near-threshold", type=float, default=0.88)
    args = p.parse_args()
    suite_rows = load_jsonl(args.suite)
    if not suite_rows:
        print("ERROR: empty suite", file=sys.stderr)
        return 2

    corpus = corpus_texts()
    corpus_norms = [t for _, t in corpus]

    exact = []
    near = []
    internal_dup = []
    seen: dict[str, str] = {}

    for row in suite_rows:
        tid = row["test_id"]
        nt = norm(row["message_text"])
        if nt in seen:
            internal_dup.append((tid, seen[nt]))
        seen[nt] = tid
        for src, ct in corpus:
            if nt == ct:
                exact.append({"test_id": tid, "match_source": src})
                break
        else:
            for src, ct in corpus:
                if jaccard(nt, ct) >= args.near_threshold:
                    near.append({"test_id": tid, "match_source": src, "jaccard": round(jaccard(nt, ct), 3)})
                    break

    ids = {r["test_id"] for r in suite_rows}
    if len(ids) != len(suite_rows):
        print("ERROR: duplicate test_id", file=sys.stderr)
        return 2

    report = {
        "suite": str(args.suite),
        "row_count": len(suite_rows),
        "exact_duplicates_in_corpus": exact,
        "near_duplicates_in_corpus": near,
        "internal_exact_duplicates": internal_dup,
        "language_subtype_counts": {},
    }
    from collections import Counter

    report["language_subtype_counts"] = dict(Counter(r["language_subtype"] for r in suite_rows))

    print(json.dumps(report, indent=2, ensure_ascii=False))
    if exact or internal_dup:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
