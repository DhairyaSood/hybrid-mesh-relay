#!/usr/bin/env python3
"""Export pool JSONL to Label Studio import JSON (local use only)."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    tasks = []
    for line in args.pool.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        tasks.append(
            {
                "data": {
                    "sample_id": row["sample_id"],
                    "message_text": row["message_text"],
                    "event_id": row["event_id"],
                    "source_collection": row["source_collection"],
                    "sampling_strategy": row["sampling_strategy"],
                    "keyword_bucket_hint": row.get("keyword_bucket_hint") or "",
                }
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(tasks)} tasks to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
