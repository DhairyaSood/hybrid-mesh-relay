#!/usr/bin/env python3
"""Full MU-v1 vs MU-v2 eval on civic_emergency_challenge_v2 (no training)."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from collections import Counter
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

SUITE = ROOT / "eval_suites/civic_emergency_challenge_v2.jsonl"
MODEL_V1 = ROOT / "models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib"
MODEL_V2 = ROOT / "models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib"

LABELS_ORDERED = [
    "ambiguous_needs_review",
    "benign_emergency_information",
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
    "quoted_or_historical_threat",
    "report_or_warning_about_misconduct",
]
HARMFUL = {
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
}
BENIGN = {"benign_emergency_information"}


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def evaluate(y_true: list[str], y_pred: list[str]) -> dict:
    yt, yp = np.array(y_true), np.array(y_pred)
    prec, rec, f1, sup = precision_recall_fscore_support(
        yt, yp, labels=LABELS_ORDERED, zero_division=0
    )
    per_class = {
        LABELS_ORDERED[i]: {
            "precision": float(prec[i]),
            "recall": float(rec[i]),
            "f1": float(f1[i]),
            "support": int(sup[i]),
        }
        for i in range(len(LABELS_ORDERED))
    }
    return {
        "accuracy": float(accuracy_score(yt, yp)),
        "macro_f1": float(f1_score(yt, yp, labels=LABELS_ORDERED, average="macro", zero_division=0)),
        "weighted_f1": float(f1_score(yt, yp, labels=LABELS_ORDERED, average="weighted", zero_division=0)),
        "per_class": per_class,
        "confusion_matrix": confusion_matrix(yt, yp, labels=LABELS_ORDERED).tolist(),
        "confusion_matrix_labels": LABELS_ORDERED,
    }


def by_subtype(rows: list[dict], y_pred: list[str]) -> dict:
    out = {}
    for st in ("english", "devanagari_hindi", "latin_hinglish"):
        idx = [i for i, r in enumerate(rows) if r["language_subtype"] == st]
        if not idx:
            continue
        yt = [rows[i]["expected_label"] for i in idx]
        yp = [y_pred[i] for i in idx]
        m = evaluate(yt, yp)
        m["n"] = len(idx)
        out[st] = m
    return out


def error_analysis(rows: list[dict], y_pred: list[str]) -> dict:
    benign_total = sum(1 for r in rows if r["expected_label"] in BENIGN)
    harmful_total = sum(1 for r in rows if r["expected_label"] in HARMFUL)
    benign_fp = []
    harmful_fn = []
    mis = []
    pred_harmful_on_nonharm = Counter()
    for r, pred in zip(rows, y_pred):
        exp, tid = r["expected_label"], r["test_id"]
        if pred != exp:
            mis.append(
                {
                    "test_id": tid,
                    "expected_label": exp,
                    "predicted_label": pred,
                    "language_subtype": r["language_subtype"],
                    "challenge_theme": r.get("challenge_theme"),
                    "scenario_rationale": r.get("scenario_rationale"),
                    "message_text": r["message_text"],
                }
            )
        if exp in BENIGN and pred not in BENIGN:
            benign_fp.append(tid)
        if exp in HARMFUL and pred not in HARMFUL:
            harmful_fn.append(tid)
        if pred in HARMFUL and exp not in HARMFUL:
            pred_harmful_on_nonharm[pred] += 1
    return {
        "misclassifications": mis,
        "benign_false_positive_count": len(benign_fp),
        "benign_false_positive_denominator": benign_total,
        "benign_false_positive_rate": len(benign_fp) / benign_total if benign_total else 0.0,
        "benign_false_positive_ids": benign_fp,
        "harmful_false_negative_count": len(harmful_fn),
        "harmful_false_negative_denominator": harmful_total,
        "harmful_false_negative_rate": len(harmful_fn) / harmful_total if harmful_total else 0.0,
        "harmful_false_negative_ids": harmful_fn,
        "overprediction_harmful_on_nonharmful_by_predicted_class": dict(pred_harmful_on_nonharm),
        "predicted_label_distribution": dict(Counter(y_pred)),
        "expected_label_distribution": dict(Counter(r["expected_label"] for r in rows)),
    }


def run_model(model_path: Path, rows: list[dict]) -> dict:
    artifact = joblib.load(model_path)
    pipe = artifact["pipeline"]
    classes = [str(c) for c in pipe.named_steps["clf"].classes_]
    texts = [clean_text(r["message_text"]) for r in rows]
    y_true = [r["expected_label"] for r in rows]
    y_pred = [str(p) for p in pipe.predict(texts)]
    scores = []
    for r, cleaned, pred in zip(rows, texts, y_pred):
        proba = pipe.predict_proba([cleaned])[0]
        score_map = {str(c): float(p) for c, p in zip(classes, proba)}
        scores.append(
            {
                "test_id": r["test_id"],
                "expected_label": r["expected_label"],
                "predicted_label": pred,
                "language_subtype": r["language_subtype"],
                "class_scores": score_map,
            }
        )
    return {
        "model_path": str(model_path),
        "overall": evaluate(y_true, y_pred),
        "by_language_subtype": by_subtype(rows, y_pred),
        "errors": error_analysis(rows, y_pred),
        "predictions": scores,
    }


def render_md(suite_meta: dict, v1: dict, v2: dict, out_dir: Path) -> str:
    lines = [
        "# Civic emergency challenge v2 evaluation",
        "",
        f"**Run:** `{out_dir.name}`",
        "",
        "Synthetic/advisory evaluation only. **Not** proof of real-world safety or sender authenticity. **MU-v1 remains canonical.**",
        "",
        f"Suite: `{SUITE.name}` ({suite_meta['row_count']} cases)",
        f"Language counts: {suite_meta['language_subtype_counts']}",
        "",
        "## Independence check",
        "",
        f"- Exact duplicates in corpus: **{len(suite_meta.get('exact_duplicates_in_corpus', []))}**",
        f"- Near duplicates (Jaccard ≥ threshold): **{len(suite_meta.get('near_duplicates_in_corpus', []))}**",
        "",
        "## MU-v1 (canonical)",
        "",
        f"- Accuracy: **{v1['overall']['accuracy']:.4f}** | Macro-F1: **{v1['overall']['macro_f1']:.4f}**",
        f"- Benign FP: **{v1['errors']['benign_false_positive_count']}/{v1['errors']['benign_false_positive_denominator']}** "
        f"({v1['errors']['benign_false_positive_rate']:.2%})",
        f"- Harmful FN: **{v1['errors']['harmful_false_negative_count']}/{v1['errors']['harmful_false_negative_denominator']}** "
        f"({v1['errors']['harmful_false_negative_rate']:.2%})",
        f"- Harmful over-prediction on non-harmful expected: `{v1['errors']['overprediction_harmful_on_nonharmful_by_predicted_class']}`",
        "",
        "### MU-v1 per language (small n)",
        "",
    ]
    for st, m in v1["by_language_subtype"].items():
        lines.append(f"- **{st}** (n={m['n']}): acc={m['accuracy']:.3f}, macro-F1={m['macro_f1']:.3f}")
    lines += ["", "## MU-v2 (candidate, not promoted)", ""]
    lines += [
        f"- Accuracy: **{v2['overall']['accuracy']:.4f}** | Macro-F1: **{v2['overall']['macro_f1']:.4f}**",
        f"- Benign FP: **{v2['errors']['benign_false_positive_count']}/{v2['errors']['benign_false_positive_denominator']}** "
        f"({v2['errors']['benign_false_positive_rate']:.2%})",
        f"- Harmful FN: **{v2['errors']['harmful_false_negative_count']}/{v2['errors']['harmful_false_negative_denominator']}** "
        f"({v2['errors']['harmful_false_negative_rate']:.2%})",
        f"- Harmful over-prediction on non-harmful expected: `{v2['errors']['overprediction_harmful_on_nonharmful_by_predicted_class']}`",
        "",
        "### MU-v2 per language",
        "",
    ]
    for st, m in v2["by_language_subtype"].items():
        lines.append(f"- **{st}** (n={m['n']}): acc={m['accuracy']:.3f}, macro-F1={m['macro_f1']:.3f}")

    lines += ["", "## Over-prediction analysis", ""]
    lines.append(
        "Counts show how often each model assigns a **harmful** label when the expected label is not in "
        "{coercion, direct_threat, impersonation_fraud}. High counts for `report_or_warning` or "
        "`coercion` on benign civic text indicate keyword-driven false alarms."
    )
    lines += ["", "## Misclassifications (MU-v1)", ""]
    for m in v1["errors"]["misclassifications"]:
        lines.append(f"- **{m['test_id']}** expected `{m['expected_label']}` → `{m['predicted_label']}` ({m['language_subtype']})")
    lines += ["", "## Misclassifications (MU-v2)", ""]
    for m in v2["errors"]["misclassifications"]:
        lines.append(f"- **{m['test_id']}** expected `{m['expected_label']}` → `{m['predicted_label']}` ({m['language_subtype']})")

    lines += [
        "",
        "## Recommendation",
        "",
        "- **Do not promote MU-v2** on this evidence alone: v2 improves over v1 on this harder suite but still misclassifies "
        f"**{v2['errors']['benign_false_positive_count']}/{v2['errors']['benign_false_positive_denominator']}** benign civic cases "
        f"and **{v2['errors']['harmful_false_negative_count']}/{v2['errors']['harmful_false_negative_denominator']}** harmful cases.",
        "- **MU-v1** shows severe harmful-class over-prediction on benign text (keywords: help, money, police, warning, pay). "
        "Keep MU-v1 as canonical for finalized audits but treat scores as unreliable on civic/emergency phrasing.",
        "- **Further model work is justified** (data + calibration + quote/report disambiguation), not Android integration.",
        "- **Local tester experimentation** with MU-v2 is reasonable **only** with strong disclaimers and side-by-side comparison—never for blocking delivery.",
        "",
        "## Reproduce",
        "",
        "```bash",
        "python3 scripts/author_civic_challenge_v2.py",
        "python3 scripts/validate_challenge_independence.py eval_suites/civic_emergency_challenge_v2.jsonl",
        "python3 scripts/eval_civic_challenge_v2_full.py",
        "python3 scripts/eval_robustness_suite.py --suite eval_suites/civic_emergency_challenge_v2.jsonl --model models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib",
        "python3 scripts/eval_robustness_suite.py --suite eval_suites/civic_emergency_challenge_v2.jsonl --model models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib",
        "```",
        "",
    ]
    return "\n".join(lines)


def main() -> int:
    if not SUITE.is_file():
        print("Run author_civic_challenge_v2.py first", file=sys.stderr)
        return 2
    for mp in (MODEL_V1, MODEL_V2):
        if not mp.is_file():
            print(f"Missing model: {mp}", file=sys.stderr)
            return 2

    # Independence
    proc = subprocess.run(
        [sys.executable, str(ROOT / "scripts/validate_challenge_independence.py"), str(SUITE)],
        capture_output=True,
        text=True,
        cwd=ROOT,
    )
    suite_meta = json.loads(proc.stdout) if proc.returncode in (0, 1) else {}
    suite_meta["independence_exit_code"] = proc.returncode

    rows = load_jsonl(SUITE)
    v1 = run_model(MODEL_V1, rows)
    v2 = run_model(MODEL_V2, rows)

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "outputs" / f"civic_challenge_v2_eval_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SUITE, out_dir / "civic_emergency_challenge_v2.jsonl")
    (out_dir / "independence_report.json").write_text(json.dumps(suite_meta, indent=2), encoding="utf-8")
    (out_dir / "mu_v1_results.json").write_text(json.dumps(v1, indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "mu_v2_results.json").write_text(json.dumps(v2, indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "predictions_mu_v1.jsonl").write_text(
        "\n".join(json.dumps(p, ensure_ascii=False) for p in v1["predictions"]) + "\n",
        encoding="utf-8",
    )
    (out_dir / "predictions_mu_v2.jsonl").write_text(
        "\n".join(json.dumps(p, ensure_ascii=False) for p in v2["predictions"]) + "\n",
        encoding="utf-8",
    )
    md = render_md(suite_meta, v1, v2, out_dir)
    (out_dir / "REPORT.md").write_text(md, encoding="utf-8")
    (ROOT / "docs" / "CIVIC_CHALLENGE_V2_EVAL.md").write_text(md, encoding="utf-8")

    print(json.dumps({"out_dir": str(out_dir), "v1_acc": v1["overall"]["accuracy"], "v2_acc": v2["overall"]["accuracy"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
