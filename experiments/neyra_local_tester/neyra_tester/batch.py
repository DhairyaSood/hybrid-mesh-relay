from __future__ import annotations

import csv
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from neyra_tester.inference import ModelBundle, predict_all
from neyra_tester.paths import TESTER_ROOT


def _read_rows(path: Path) -> list[dict]:
    path = Path(path)
    if path.suffix.lower() == ".jsonl":
        rows = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
        return rows
    if path.suffix.lower() == ".csv":
        with path.open(newline="", encoding="utf-8-sig") as fh:
            return list(csv.DictReader(fh))
    raise ValueError("Batch file must be .csv or .jsonl")


def run_batch(
    bundle: ModelBundle,
    input_path: Path,
    output_path: Path | None = None,
) -> Path:
    rows = _read_rows(input_path)
    if not rows:
        raise ValueError("Batch file has no rows")
    text_key = None
    for key in ("message_text", "text", "message", "body"):
        if key in rows[0]:
            text_key = key
            break
    if not text_key:
        raise ValueError("Batch file needs a message_text, text, message, or body column")

    out_rows: list[dict[str, Any]] = []
    for i, row in enumerate(rows):
        text = row.get(text_key, "")
        result = predict_all(bundle, str(text))
        out_rows.append(
            {
                "row_index": i,
                "message_text": text,
                "expected_label": row.get("expected_label"),
                "result": result,
            }
        )

    if output_path is None:
        ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        out_dir = TESTER_ROOT / "outputs"
        out_dir.mkdir(parents=True, exist_ok=True)
        output_path = out_dir / f"batch_predictions_{ts}.jsonl"
    else:
        output_path = Path(output_path)
        output_path.parent.mkdir(parents=True, exist_ok=True)

    with output_path.open("w", encoding="utf-8") as fh:
        for row in out_rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    return output_path
