#!/usr/bin/env python3
"""Convert Label Studio JSON export to canonical annotation JSONL."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

VALID_M = {"M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7"}
VALID_D = {"D0", "D1", "D2", "D3", "D4", "D5", "D6"}


def parse_export(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(data, dict) and "tasks" in data:
        items = data["tasks"]
    elif isinstance(data, list):
        items = data
    else:
        items = [data]

    out = []
    for item in items:
        ann_list = item.get("annotations") or item.get("completions") or []
        if not ann_list:
            continue
        ann = ann_list[-1]
        results = {r["from_name"]: r for r in ann.get("result", [])}
        data_fields = item.get("data", {})

        def choice(name: str) -> list[str]:
            r = results.get(name, {})
            val = r.get("value", {})
            if "choices" in val:
                return list(val["choices"])
            return []

        content = choice("content_labels")
        discourse = choice("discourse_type")
        primary = choice("primary_content_label")
        conf = results.get("confidence", {}).get("value", {}).get("rating")

        record = {
            "sample_id": data_fields.get("sample_id"),
            "annotator_id": None,  # filled below
            "discourse_type": discourse[0] if discourse else None,
            "content_labels": content,
            "primary_content_label": primary[0] if primary else None,
            "confidence": int(conf) if conf else None,
            "annotator_notes": (results.get("annotator_notes", {}).get("value") or {}).get("text"),
            "adjudication_status": "complete",
            "annotated_at_utc": datetime.now(timezone.utc).isoformat(),
        }
        out.append(record)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path, required=True)
    parser.add_argument("--annotator-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    rows = parse_export(args.export)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as fh:
        for row in rows:
            row["annotator_id"] = args.annotator_id
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} annotations to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
