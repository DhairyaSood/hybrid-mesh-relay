#!/usr/bin/env python3
"""Evaluate MU-v1/v2/v3 on independent civic v3 suite."""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
from sklearn.metrics import accuracy_score, classification_report, f1_score

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from synthetic.preprocess import clean_text

SUITE = ROOT / "eval_suites/civic_emergency_challenge_v3.jsonl"
MODELS = {
    "MU-v1": ROOT / "models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib",
    "MU-v2": ROOT / "models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib",
    "MU-v3": ROOT / "models/synthetic_baseline_v3/synthetic_misuse_tfidf_logreg_v3.joblib",
}


def load_rows():
    return [json.loads(ln) for ln in SUITE.read_text(encoding="utf-8").splitlines() if ln.strip()]


def eval_model(name: str, path: Path, rows: list[dict]) -> dict:
    art = joblib.load(path)
    pipe = art["pipeline"]
    texts = [clean_text(r["message_text"]) for r in rows]
    preds = [str(p) for p in pipe.predict(texts)]
    y = [r["expected_label"] for r in rows]
    harmful = {"coercion_or_extortion", "direct_threat_or_intimidation", "possible_relief_worker_impersonation_or_fraud"}
    benign_expected = [r for r in rows if r["expected_label"] == "benign_emergency_information"]
    harmful_expected = [r for r in rows if r["expected_label"] in harmful]
    benign_fp = sum(
        1 for r, p in zip(rows, preds) if r["expected_label"] == "benign_emergency_information" and p in harmful
    )
    harmful_fn = sum(1 for r, p in zip(rows, preds) if r["expected_label"] in harmful and p not in harmful)
    by_lang = {}
    for sub in ("english", "devanagari_hindi", "latin_hinglish"):
        idx = [i for i, r in enumerate(rows) if r["language_subtype"] == sub]
        if not idx:
            continue
        yt = [y[i] for i in idx]
        pt = [preds[i] for i in idx]
        by_lang[sub] = {"accuracy": accuracy_score(yt, pt), "macro_f1": f1_score(yt, pt, average="macro", zero_division=0)}
    return {
        "model": name,
        "accuracy": accuracy_score(y, preds),
        "macro_f1": f1_score(y, preds, average="macro", zero_division=0),
        "benign_fp": benign_fp,
        "benign_n": len(benign_expected),
        "harmful_fn": harmful_fn,
        "harmful_n": len(harmful_expected),
        "by_language": by_lang,
        "report": classification_report(y, preds, zero_division=0, output_dict=True),
        "failures": [
            {"id": r["test_id"], "expected": r["expected_label"], "predicted": p, "text": r["message_text"]}
            for r, p in zip(rows, preds)
            if r["expected_label"] != p
        ],
    }


def main() -> int:
    rows = load_rows()
    results = {k: eval_model(k, v, rows) for k, v in MODELS.items() if v.is_file()}
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "outputs" / f"civic_challenge_v3_eval_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({k: {"macro_f1": v["macro_f1"], "benign_fp": v["benign_fp"]} for k, v in results.items()}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
