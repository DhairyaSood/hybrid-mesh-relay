#!/usr/bin/env python3
"""Summarize civic challenge eval: benign false positives, harmful false negatives."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

HARMFUL = {
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
}
BENIGN = {"benign_emergency_information"}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("results_json", type=Path)
    args = p.parse_args()
    data = json.loads(args.results_json.read_text(encoding="utf-8"))
    failures = data.get("failures", [])
    benign_fp = [
        f
        for f in failures
        if f["expected_label"] in BENIGN and f["predicted_label"] not in BENIGN
    ]
    harmful_fn = [
        f
        for f in failures
        if f["expected_label"] in HARMFUL and f["predicted_label"] not in HARMFUL
    ]
    summary = {
        "suite": data.get("suite_path"),
        "model": data.get("model_path"),
        "accuracy": data["overall_metrics"]["accuracy"],
        "macro_f1": data["overall_metrics"]["macro_f1"],
        "failure_count": data.get("failure_count"),
        "benign_false_positives": len(benign_fp),
        "benign_false_positive_ids": [f["test_id"] for f in benign_fp],
        "harmful_false_negatives": len(harmful_fn),
        "harmful_false_negative_ids": [f["test_id"] for f in harmful_fn],
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
