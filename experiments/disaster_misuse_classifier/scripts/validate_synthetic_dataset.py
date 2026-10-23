#!/usr/bin/env python3
"""Validate synthetic misuse training dataset integrity."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]

VALID_LABELS = {
    "benign_emergency_information",
    "direct_threat_or_intimidation",
    "coercion_or_extortion",
    "possible_relief_worker_impersonation_or_fraud",
    "report_or_warning_about_misconduct",
    "quoted_or_historical_threat",
    "ambiguous_needs_review",
}

VALID_BUCKETS = {"english", "hindi_hinglish"}
VALID_SUBTYPES = {"english", "devanagari_hindi", "latin_hinglish"}


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        if line.strip():
            rows.append(json.loads(line))
    return rows


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def validate(rows: list[dict], cfg: dict) -> dict:
    errors: list[str] = []
    n = len(rows)

    if n != int(cfg["total_messages"]):
        errors.append(f"expected {cfg['total_messages']} rows, got {n}")

    en = sum(1 for r in rows if r.get("language_bucket") == "english")
    hi = sum(1 for r in rows if r.get("language_bucket") == "hindi_hinglish")
    if en != int(cfg["english_count"]):
        errors.append(f"english_count expected {cfg['english_count']}, got {en}")
    if hi != int(cfg["hindi_hinglish_count"]):
        errors.append(f"hindi_hinglish_count expected {cfg['hindi_hinglish_count']}, got {hi}")

    dev = sum(1 for r in rows if r.get("language_subtype") == "devanagari_hindi")
    lat = sum(1 for r in rows if r.get("language_subtype") == "latin_hinglish")
    en_sub = sum(1 for r in rows if r.get("language_subtype") == "english")

    texts = []
    for i, r in enumerate(rows):
        sid = r.get("sample_id", "")
        if not sid or not str(sid).startswith("SYNTH-"):
            errors.append(f"row {i}: invalid sample_id")
        text = (r.get("message_text") or "").strip()
        if not text:
            errors.append(f"row {i}: empty message_text")
        texts.append(norm(text))
        lab = r.get("label")
        if lab not in VALID_LABELS:
            errors.append(f"row {i}: invalid label {lab}")
        if r.get("language_bucket") not in VALID_BUCKETS:
            errors.append(f"row {i}: invalid language_bucket")
        st = r.get("language_subtype")
        if st not in VALID_SUBTYPES:
            errors.append(f"row {i}: invalid language_subtype")
        if r.get("language_bucket") == "english" and st != "english":
            errors.append(f"row {i}: english row must have subtype english")
        if r.get("language_bucket") == "hindi_hinglish" and st not in (
            "devanagari_hindi",
            "latin_hinglish",
        ):
            errors.append(f"row {i}: hindi row needs devanagari or latin subtype")
        if not r.get("template_family_id"):
            errors.append(f"row {i}: missing template_family_id")
        if r.get("label_source") != "generator_scenario":
            errors.append(f"row {i}: label_source must be generator_scenario")

    dup = n - len(set(texts))
    if dup:
        errors.append(f"duplicate normalized texts: {dup}")

    label_counts = Counter(r["label"] for r in rows)
    for lab, expected in cfg["label_counts"].items():
        if label_counts.get(lab, 0) != expected:
            errors.append(f"label {lab}: expected {expected}, got {label_counts.get(lab, 0)}")

    return {
        "ok": len(errors) == 0,
        "errors": errors,
        "total_count": n,
        "unique_text_count": len(set(texts)),
        "duplicate_text_count": dup,
        "english_count": en,
        "hindi_hinglish_count": hi,
        "devanagari_hindi_count": dev,
        "latin_hinglish_count": lat,
        "english_subtype_count": en_sub,
        "label_counts": dict(sorted(label_counts.items())),
        "label_counts_by_subtype": _by_subtype(rows),
    }


def _by_subtype(rows: list[dict]) -> dict:
    out: dict[str, dict[str, int]] = {}
    for r in rows:
        st = r["language_subtype"]
        out.setdefault(st, {})
        out[st][r["label"]] = out[st].get(r["label"], 0) + 1
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs/synthetic_dataset.yaml")
    parser.add_argument("--dataset", type=Path, default=None)
    args = parser.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    dataset = args.dataset or ROOT / cfg["output_dir"] / cfg["dataset_filename"]
    if not dataset.is_file():
        print(f"ERROR: missing dataset {dataset}", file=sys.stderr)
        return 2
    rows = load_jsonl(dataset)
    report = validate(rows, cfg)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 1 if not report["ok"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
