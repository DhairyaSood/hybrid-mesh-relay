#!/usr/bin/env python3
"""Validate annotation files: codes, duplicates, leakage."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import yaml

VALID_M = {"M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7"}
VALID_D = {"D0", "D1", "D2", "D3", "D4", "D5", "D6"}


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def validate_annotations(path: Path) -> list[str]:
    errors = []
    for i, row in enumerate(load_jsonl(path)):
        sid = row.get("sample_id")
        if not sid or not re.match(r"^PILOT-[0-9a-f]{32}$", sid):
            errors.append(f"{path}:{i} invalid sample_id")
        d = row.get("discourse_type")
        if d and d not in VALID_D:
            errors.append(f"{path}:{i} invalid discourse_type {d}")
        for m in row.get("content_labels") or []:
            if m not in VALID_M:
                errors.append(f"{path}:{i} invalid content label {m}")
        p = row.get("primary_content_label")
        if p and p not in VALID_M:
            errors.append(f"{path}:{i} invalid primary {p}")
        conf = row.get("confidence")
        if conf is not None and conf not in (1, 2, 3):
            errors.append(f"{path}:{i} invalid confidence {conf}")
        if "sender" in json.dumps(row).lower():
            errors.append(f"{path}:{i} possible sender field present")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--gold", type=Path, default=None)
    parser.add_argument("--annotations", type=Path, action="append", default=[])
    parser.add_argument("--splits-config", type=Path, default=None)
    parser.add_argument("--train-manifest", type=Path, default=None)
    parser.add_argument("--holdout-manifest", type=Path, default=None)
    args = parser.parse_args()

    errors: list[str] = []
    pool = load_jsonl(args.pool)
    pool_ids = [r["sample_id"] for r in pool]
    if len(pool_ids) != len(set(pool_ids)):
        errors.append("duplicate sample_id in pool")

    texts = [re.sub(r"\s+", " ", r["message_text"].lower()).strip() for r in pool]
    if len(texts) != len(set(texts)):
        errors.append("duplicate message_text in pool")

    for ann in args.annotations:
        errors.extend(validate_annotations(ann))
        ann_rows = load_jsonl(ann)
        for row in ann_rows:
            if row["sample_id"] not in set(pool_ids):
                errors.append(f"annotation sample_id not in pool: {row['sample_id']}")

    if args.gold:
        gold = load_jsonl(args.gold)
        for row in gold:
            labels = row.get("adjudicated_content_labels") or row.get("content_labels")
            if labels:
                for m in labels:
                    if m not in VALID_M:
                        errors.append(f"gold invalid label {m}")

    # Event leakage: same event must not appear in both train and holdout manifests if provided
    if args.train_manifest and args.holdout_manifest:
        train_events = set(args.train_manifest.read_text().split())
        hold_events = set(args.holdout_manifest.read_text().split())
        overlap = train_events & hold_events
        if overlap:
            errors.append(f"event leakage between train/holdout manifests: {overlap}")

    # Eval reserve should not be marked train in future manifests (warning only in pilot)
    eval_ids = {r["sample_id"] for r in pool if r.get("split_role") == "pilot_eval_reserve"}
    if args.train_manifest:
        train_ids = set(args.train_manifest.read_text().split())
        leak = eval_ids & train_ids
        if leak:
            errors.append(f"eval reserve ids present in train manifest: {len(leak)} ids")

    event_counts = Counter(r["event_id"] for r in pool)
    report = {
        "pool_size": len(pool),
        "eval_reserve": len(eval_ids),
        "event_id_counts": dict(event_counts),
        "errors": errors,
        "ok": len(errors) == 0,
    }
    print(json.dumps(report, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
