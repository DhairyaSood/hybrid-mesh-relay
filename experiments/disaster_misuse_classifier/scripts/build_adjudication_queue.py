#!/usr/bin/env python3
"""Build CSV queue for adjudicator from two annotator JSONL exports."""

from __future__ import annotations

import argparse
import csv
import json
import random
from pathlib import Path

CONCERN = {"M1", "M2", "M3", "M4", "M5"}


def load(path: Path) -> dict[str, dict]:
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            out[row["sample_id"]] = row
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotator-a", type=Path, required=True)
    parser.add_argument("--annotator-b", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--m0-qa-fraction", type=float, default=0.10)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    a = load(args.annotator_a)
    b = load(args.annotator_b)
    rng = random.Random(args.seed)

    rows = []
    for sid in sorted(set(a) & set(b)):
        ra, rb = a[sid], b[sid]
        pa = ra.get("primary_content_label")
        pb = rb.get("primary_content_label")
        labels_a = set(ra.get("content_labels") or [])
        labels_b = set(rb.get("content_labels") or [])
        reason = []
        if pa != pb:
            reason.append("primary_disagreement")
        if labels_a & CONCERN or labels_b & CONCERN:
            reason.append("concern_label_present")
        if not reason and pa == "M0" and pb == "M0" and rng.random() < args.m0_qa_fraction:
            reason.append("m0_qa_sample")
        if reason:
            rows.append(
                {
                    "sample_id": sid,
                    "reason": ";".join(reason),
                    "primary_a": pa,
                    "primary_b": pb,
                    "discourse_a": ra.get("discourse_type"),
                    "discourse_b": rb.get("discourse_type"),
                    "content_labels_a": "|".join(sorted(labels_a)),
                    "content_labels_b": "|".join(sorted(labels_b)),
                }
            )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()) if rows else ["sample_id"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} adjudication rows to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
