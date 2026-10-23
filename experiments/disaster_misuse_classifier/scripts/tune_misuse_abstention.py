#!/usr/bin/env python3
"""Tune conservative abstention thresholds on validation split only (not challenge sets)."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import joblib
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from synthetic.preprocess import clean_text

HARMFUL = {
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
}
BENIGN = {"benign_emergency_information"}


def load_jsonl(p: Path) -> list[dict]:
    return [json.loads(ln) for ln in p.read_text(encoding="utf-8").splitlines() if ln.strip()]


def main() -> int:
    val_path = ROOT / "data/synthetic_misuse_v2_20k/splits/validation.jsonl"
    model_path = ROOT / "models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib"
    if not val_path.is_file() or not model_path.is_file():
        print("Need v2 validation split and MU-v2 for tuning", file=sys.stderr)
        return 2
    rows = load_jsonl(val_path)
    pipe = joblib.load(model_path)["pipeline"]
    texts = [clean_text(r["message_text"]) for r in rows]
    y_true = [r["label"] for r in rows]
    proba = pipe.predict_proba(texts)
    classes = [str(c) for c in pipe.named_steps["clf"].classes_]

    best = None
    for min_max in (0.35, 0.45, 0.55, 0.65):
        for min_margin in (0.05, 0.10, 0.15, 0.20):
            abstain = 0
            benign_fp = benign_total = harmful_fn = harmful_total = 0
            for i, row in enumerate(rows):
                scores = {classes[j]: float(proba[i][j]) for j in range(len(classes))}
                ranked = sorted(scores.items(), key=lambda x: -x[1])
                top, second = ranked[0], ranked[1]
                margin = top[1] - second[1]
                if top[1] < min_max or margin < min_margin:
                    abstain += 1
                    continue
                pred = top[0]
                exp = row["label"]
                if exp in BENIGN:
                    benign_total += 1
                    if pred not in BENIGN:
                        benign_fp += 1
                if exp in HARMFUL:
                    harmful_total += 1
                    if pred not in HARMFUL:
                        harmful_fn += 1
            cov = 1 - abstain / len(rows)
            score = benign_fp + 2 * harmful_fn  # weight FN higher
            cand = {
                "min_top_score": min_max,
                "min_margin": min_margin,
                "abstain_rate": abstain / len(rows),
                "coverage": cov,
                "benign_fp": benign_fp,
                "benign_fp_rate": benign_fp / benign_total if benign_total else 0,
                "harmful_fn": harmful_fn,
                "harmful_fn_rate": harmful_fn / harmful_total if harmful_total else 0,
            }
            if best is None or score < best["_score"]:
                cand["_score"] = score
                best = cand

    assert best
    out = {
        "disclaimer": "Thresholds tuned on synthetic v2 validation only; not calibrated probabilities.",
        "model": "MU-v2 experimental",
        "min_top_score": best["min_top_score"],
        "min_margin": best["min_margin"],
        "validation_metrics": {k: v for k, v in best.items() if not k.startswith("_")},
    }
    dest = ROOT.parent / "neyra_local_tester/misuse_abstention_v2.yaml"
    dest.write_text(yaml.safe_dump(out, sort_keys=False), encoding="utf-8")
    print(json.dumps(out, indent=2))
    print(f"Wrote {dest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
