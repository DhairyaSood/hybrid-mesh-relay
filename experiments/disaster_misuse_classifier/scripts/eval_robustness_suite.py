#!/usr/bin/env python3
"""Evaluate frozen synthetic misuse model on an independent robustness JSONL suite."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from synthetic.preprocess import clean_text

LABELS_ORDERED = [
    "ambiguous_needs_review",
    "benign_emergency_information",
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
    "quoted_or_historical_threat",
    "report_or_warning_about_misconduct",
]


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def load_training_texts() -> set[str]:
    base = ROOT / "data/synthetic_misuse_10k/splits"
    seen: set[str] = set()
    for name in ("train.jsonl", "validation.jsonl", "test.jsonl"):
        p = base / name
        if not p.is_file():
            continue
        for row in load_jsonl(p):
            seen.add(norm(row["message_text"]))
    return seen


def evaluate_split(y_true: list[str], y_pred: list[str], labels: list[str]) -> dict:
    yt = np.array(y_true)
    yp = np.array(y_pred)
    prec, rec, f1, sup = precision_recall_fscore_support(
        yt, yp, labels=labels, zero_division=0
    )
    per_class = {
        labels[i]: {
            "precision": float(prec[i]),
            "recall": float(rec[i]),
            "f1": float(f1[i]),
            "support": int(sup[i]),
        }
        for i in range(len(labels))
    }
    return {
        "accuracy": float(accuracy_score(yt, yp)),
        "macro_f1": float(f1_score(yt, yp, labels=labels, average="macro", zero_division=0)),
        "weighted_f1": float(f1_score(yt, yp, labels=labels, average="weighted", zero_division=0)),
        "per_class": per_class,
        "confusion_matrix": confusion_matrix(yt, yp, labels=labels).tolist(),
        "confusion_matrix_labels": labels,
    }


def metrics_by_subtype(rows: list[dict], y_pred: list[str], labels: list[str]) -> dict:
    out = {}
    for subtype in ("english", "devanagari_hindi", "latin_hinglish"):
        idx = [i for i, r in enumerate(rows) if r["language_subtype"] == subtype]
        if not idx:
            continue
        y_t = [rows[i]["expected_label"] for i in idx]
        y_p = [y_pred[i] for i in idx]
        m = evaluate_split(y_t, y_p, labels)
        m["n"] = len(idx)
        out[subtype] = m
    return out


def score_row(pipe, text: str, classes: list[str]) -> dict[str, float]:
    proba = pipe.predict_proba([text])[0]
    return {str(c): float(p) for c, p in zip(classes, proba)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--suite",
        type=Path,
        default=ROOT / "eval_suites/independent_robustness_v1.jsonl",
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=ROOT / "models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib",
    )
    parser.add_argument("--outputs-dir", type=Path, default=ROOT / "outputs")
    args = parser.parse_args()

    if not args.suite.is_file():
        print(f"ERROR: suite not found: {args.suite}", file=sys.stderr)
        return 2
    if not args.model.is_file():
        print(f"ERROR: model not found: {args.model}", file=sys.stderr)
        return 2

    suite = load_jsonl(args.suite)
    train_texts = load_training_texts()
    overlaps = []
    for row in suite:
        if norm(row["message_text"]) in train_texts:
            overlaps.append(row["test_id"])
    if overlaps:
        print(
            f"WARNING: {len(overlaps)} suite texts normalize-match 10k splits: {overlaps[:5]}",
            file=sys.stderr,
        )

    artifact = joblib.load(args.model)
    pipe = artifact["pipeline"]
    classes = [str(c) for c in pipe.named_steps["clf"].classes_]
    labels = LABELS_ORDERED

    texts = [clean_text(r["message_text"]) for r in suite]
    y_true = [r["expected_label"] for r in suite]
    y_pred = [str(p) for p in pipe.predict(texts)]

    overall = evaluate_split(y_true, y_pred, labels)
    by_subtype = metrics_by_subtype(suite, y_pred, labels)

    failures = []
    for row, pred, cleaned in zip(suite, y_pred, texts):
        if pred != row["expected_label"]:
            failures.append(
                {
                    "test_id": row["test_id"],
                    "expected_label": row["expected_label"],
                    "predicted_label": pred,
                    "language_subtype": row["language_subtype"],
                    "scenario_rationale": row["scenario_rationale"],
                    "message_text": row["message_text"],
                    "class_scores": score_row(pipe, cleaned, classes),
                }
            )

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = args.outputs_dir / f"robustness_eval_{run_id}"
    out_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.suite, out_dir / "independent_robustness_suite.jsonl")

    results = {
        "run_id": run_id,
        "disclaimer": (
            "Independent robustness suite metrics are synthetic/advisory only. "
            "Misclassification does not prove innocence or guilt. "
            "Labels are not evidence of criminality."
        ),
        "model_path": str(args.model),
        "suite_path": str(args.suite),
        "suite_size": len(suite),
        "overlap_with_10k_splits": overlaps,
        "command": f"{sys.executable} scripts/eval_robustness_suite.py --suite {args.suite} --model {args.model}",
        "overall_metrics": overall,
        "metrics_by_language_subtype": by_subtype,
        "failure_count": len(failures),
        "failures": failures,
        "per_example": [
            {
                "test_id": r["test_id"],
                "expected_label": r["expected_label"],
                "predicted_label": p,
                "correct": r["expected_label"] == p,
                "language_subtype": r["language_subtype"],
            }
            for r, p in zip(suite, y_pred)
        ],
    }
    (out_dir / "results.json").write_text(
        json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    md = render_report(results)
    (out_dir / "report.md").write_text(md, encoding="utf-8")

    print(json.dumps({"run_id": run_id, "accuracy": overall["accuracy"], "macro_f1": overall["macro_f1"], "failures": len(failures)}, indent=2))
    print(f"Wrote {out_dir}")
    return 0


def render_report(results: dict) -> str:
    o = results["overall_metrics"]
    lines = [
        f"# Robustness evaluation `{results['run_id']}`",
        "",
        results["disclaimer"],
        "",
        f"Suite: `{results['suite_path']}` ({results['suite_size']} examples)",
        f"Model: `{results['model_path']}`",
        "",
        "## Overall",
        "",
        f"- Accuracy: **{o['accuracy']:.4f}**",
        f"- Macro-F1: **{o['macro_f1']:.4f}**",
        f"- Weighted-F1: **{o['weighted_f1']:.4f}**",
        f"- Failures: **{results['failure_count']}**",
        "",
        "### Per-class (overall)",
        "",
        "| Label | P | R | F1 | N |",
        "|---|---:|---:|---:|---:|",
    ]
    for lab, m in o["per_class"].items():
        lines.append(f"| `{lab}` | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} | {m['support']} |")

    lines += ["", "### By language subtype", ""]
    for st, m in results["metrics_by_language_subtype"].items():
        lines.append(f"- **{st}** (n={m['n']}): accuracy={m['accuracy']:.3f}, macro-F1={m['macro_f1']:.3f}")

    if results["failures"]:
        lines += ["", "## Failures", ""]
        for f in results["failures"]:
            lines.append(f"### `{f['test_id']}`")
            lines.append(f"- Expected: `{f['expected_label']}`")
            lines.append(f"- Predicted: `{f['predicted_label']}`")
            lines.append(f"- Subtype: {f['language_subtype']}")
            lines.append(f"- Rationale: {f['scenario_rationale']}")
            lines.append(f"- Text: {f['message_text']}")
            top = sorted(f["class_scores"].items(), key=lambda x: -x[1])[:4]
            lines.append(f"- Top scores: {top}")
            lines.append("")

    lines += [
        "",
        "## Reproduce",
        "",
        "```bash",
        results["command"],
        "```",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
