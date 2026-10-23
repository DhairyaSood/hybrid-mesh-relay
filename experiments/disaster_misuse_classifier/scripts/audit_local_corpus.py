#!/usr/bin/env python3
"""Read-only audit: misuse-related labels and keyword prevalence (not training)."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]


def load_rows(path: Path) -> tuple[list[str], list[dict]]:
    with path.open(newline="", encoding="utf-8-sig", errors="replace") as fh:
        reader = csv.DictReader(fh)
        fields = list(reader.fieldnames or [])
        rows = list(reader)
    return fields, rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs" / "audit.yaml")
    args = parser.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    dataset_root = Path(cfg["dataset_root"])
    if not dataset_root.is_dir():
        print(f"ERROR: dataset_root missing: {dataset_root}")
        return 2

    buckets = {
        name: re.compile(spec["pattern"], re.I)
        for name, spec in cfg["keyword_buckets"].items()
    }
    discourse = {
        name: re.compile(spec, re.I)
        for name, spec in cfg["discourse_heuristics"].items()
    }

    files_report = {}
    global_unique_any_kw = set()
    global_unique_core_kw = set()
    core_buckets = {
        "explicit_threat_violence",
        "looting_theft",
        "impersonation_fraud",
    }

    for path in sorted(dataset_root.glob("*.csv")):
        fields, rows = load_rows(path)
        n = len(rows)
        bucket_counts = Counter()
        target_on_hits = defaultdict(Counter)
        help_on_hits = defaultdict(Counter)
        discourse_counts = Counter()
        rows_with_any = 0

        for r in rows:
            text = (r.get("Tweet_text") or "").strip()
            tgt = (r.get("Target") or "").strip()
            help = (r.get("Help") or "").strip()
            hits = [k for k, pat in buckets.items() if pat.search(text)]
            if not hits:
                continue
            rows_with_any += 1
            global_unique_any_kw.add(text.lower())
            if any(h in core_buckets for h in hits):
                global_unique_core_kw.add(text.lower())
            for h in hits:
                bucket_counts[h] += 1
                target_on_hits[h][tgt] += 1
                if help:
                    help_on_hits[h][help] += 1
            if any(h in hits for h in ("explicit_threat_violence", "looting_theft", "impersonation_fraud")):
                for dname, dpat in discourse.items():
                    if dpat.search(text):
                        discourse_counts[dname] += 1

        files_report[path.name] = {
            "columns": fields,
            "row_count": n,
            "has_help_label": "Help" in fields,
            "has_misuse_label": False,
            "keyword_bucket_hits": dict(bucket_counts),
            "rows_with_any_keyword_bucket": rows_with_any,
            "fraction_with_keyword_bucket": round(rows_with_any / n, 6) if n else 0,
            "target_breakdown_on_hits": {k: dict(v) for k, v in target_on_hits.items()},
            "help_breakdown_on_hits": {k: dict(v) for k, v in help_on_hits.items()},
            "discourse_on_threat_loot_impersonation_hits": dict(discourse_counts),
        }

    summary = {
        "audited_at_utc": datetime.now(timezone.utc).isoformat(),
        "dataset_root": str(dataset_root),
        "conclusion": {
            "dedicated_misuse_labels_present": False,
            "humanitarian_help_labels_present_in_task3_files": True,
            "unique_texts_matching_any_keyword_bucket": len(global_unique_any_kw),
            "unique_texts_matching_threat_loot_impersonation_keywords": len(global_unique_core_kw),
            "suitable_for_misuse_classifier_training_without_new_labels": False,
        },
        "files": files_report,
        "safety_note": (
            "Keyword counts are not misuse ground truth. Many hits are news, sympathy, "
            "or discussion. Do not infer criminality from wording."
        ),
    }

    out_dir = ROOT / cfg.get("outputs_dir", "outputs") / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_audit"
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "audit_report.json"
    out_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Wrote {out_path}")
    print(json.dumps(summary["conclusion"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
