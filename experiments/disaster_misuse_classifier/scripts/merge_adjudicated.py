#!/usr/bin/env python3
"""Merge pool + dual annotations + adjudication into pilot gold JSONL."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

VALID_M = {"M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7"}
VALID_D = {"D0", "D1", "D2", "D3", "D4", "D5", "D6"}


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--annotator-a", type=Path, required=True)
    parser.add_argument("--annotator-b", type=Path, required=True)
    parser.add_argument("--adjudication", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    pool = {r["sample_id"]: r for r in load_jsonl(args.pool)}
    adj = {r["sample_id"]: r for r in load_jsonl(args.adjudication)}

    gold = []
    for sid, base in pool.items():
        row = dict(base)
        if sid in adj:
            a = adj[sid]
            row["adjudicated_discourse_type"] = a.get("adjudicated_discourse_type")
            row["adjudicated_content_labels"] = a.get("adjudicated_content_labels")
            row["adjudicator_id"] = a.get("adjudicator_id")
            row["adjudication_status"] = "adjudicated"
        else:
            row["adjudication_status"] = "pending"
        gold.append(row)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as fh:
        for row in gold:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(gold)} gold rows to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
