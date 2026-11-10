#!/usr/bin/env python3
"""Detailed MU-v2 error analysis from civic challenge v1/v2 prediction artifacts."""

from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

HARMFUL = {
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
}
BENIGN = {"benign_emergency_information"}
QUOTE_REPORT = {"quoted_or_historical_threat", "report_or_warning_about_misconduct"}


def latest_eval_dir(prefix: str) -> Path | None:
    base = ROOT / "outputs"
    dirs = sorted([p for p in base.glob(f"{prefix}*") if p.is_dir()], key=lambda p: p.name)
    return dirs[-1] if dirs else None


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(ln) for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()]


def _suite_path() -> Path:
    return ROOT / "eval_suites/civic_emergency_challenge_v2.jsonl"


def explain_error(row: dict) -> list[str]:
    text = row["message_text"].lower()
    exp, pred = row["expected_label"], row["predicted_label"]
    hints: list[str] = []
    if exp in BENIGN and pred in HARMFUL:
        for kw, note in (
            ("help", "trigger word 'help'"),
            ("money", "trigger word 'money'"),
            ("pay", "trigger word 'pay'"),
            ("police", "trigger word 'police'"),
            ("warning", "trigger word 'warning'"),
            ("urgent", "trigger word 'urgent'"),
            ("threat", "trigger word 'threat'"),
            ("donat", "donation/payment wording"),
            ("fee", "fee wording"),
        ):
            if kw in text:
                hints.append(f"Possible keyword effect: {note}")
        if "?" in row["message_text"] or "??" in row["message_text"]:
            hints.append("Fragmentary/question form may increase ambiguous/harmful confusion (hypothesis).")
    if exp in QUOTE_REPORT and pred in HARMFUL:
        hints.append(
            "Quote/report vs direct-threat boundary: model may treat quoted violent wording as sender intent (hypothesis)."
        )
    if exp in HARMFUL and pred in BENIGN:
        hints.append("Harmful FN: model may under-weight coercive phrasing in this language/style (hypothesis).")
    if exp == "coercion_or_extortion" and pred == "possible_relief_worker_impersonation_or_fraud":
        hints.append("Observed coercion vs impersonation overlap in payment-demand phrasing.")
    if exp == "possible_relief_worker_impersonation_or_fraud" and pred == "report_or_warning_about_misconduct":
        hints.append("Impersonation vs report-of-misconduct confusion.")
    if not hints:
        hints.append("No single-keyword explanation; may involve context or training gap (hypothesis).")
    return hints


def analyze_predictions(pred_path: Path, suite_name: str, suite_path: Path | None = None) -> dict:
    rows = [json.loads(ln) for ln in pred_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    if suite_path and suite_path.is_file():
        by_id = {r["test_id"]: r for r in load_jsonl(suite_path)}
        for r in rows:
            extra = by_id.get(r["test_id"], {})
            r.setdefault("message_text", extra.get("message_text", ""))
            r.setdefault("language_subtype", extra.get("language_subtype"))
            r.setdefault("challenge_theme", extra.get("challenge_theme"))
            r.setdefault("scenario_rationale", extra.get("scenario_rationale"))
    errors = [r for r in rows if r["expected_label"] != r["predicted_label"]]
    benign_fp = [r for r in errors if r["expected_label"] in BENIGN]
    harmful_fn = [r for r in errors if r["expected_label"] in HARMFUL]
    by_lang = defaultdict(list)
    for e in errors:
        by_lang[e.get("language_subtype", "unknown")].append(e)

    detailed = []
    for e in errors:
        scores = e.get("class_scores", {})
        top = sorted(scores.items(), key=lambda x: -x[1])[:3]
        detailed.append(
            {
                **e,
                "top_scores": top,
                "explanations": explain_error(e),
            }
        )

    confusions = Counter((e["expected_label"], e["predicted_label"]) for e in errors)
    return {
        "suite": suite_name,
        "predictions_file": str(pred_path),
        "total": len(rows),
        "errors": len(errors),
        "benign_fp": len(benign_fp),
        "harmful_fn": len(harmful_fn),
        "errors_by_language": {k: len(v) for k, v in by_lang.items()},
        "confusion_pairs": [f"{a} -> {b}: {n}" for (a, b), n in confusions.most_common()],
        "detailed_errors": detailed,
    }


def render_md(v2: dict, v1: dict | None) -> str:
    ts = datetime.now(timezone.utc).isoformat()
    lines = [
        "# MU-v2 misuse error analysis (civic challenges)",
        "",
        f"Generated: {ts}",
        "",
        "Evidence from saved prediction files. Explanations mix **observed** label pairs with **hypotheses** (marked).",
        "Not real-world performance. MU-v1 remains canonical.",
        "",
        "## Civic challenge v2",
        "",
        f"- Predictions: `{v2['predictions_file']}`",
        f"- Errors: **{v2['errors']}/{v2['total']}** | Benign FP: **{v2['benign_fp']}** | Harmful FN: **{v2['harmful_fn']}**",
        f"- By language: {v2['errors_by_language']}",
        "",
        "### Confusion pairs (v2)",
        "",
    ]
    for c in v2["confusion_pairs"]:
        lines.append(f"- {c}")
    lines += ["", "### Every MU-v2 misclassification (v2)", ""]
    for e in v2["detailed_errors"]:
        lines.append(f"#### `{e['test_id']}` ({e.get('language_subtype')})")
        lines.append(f"- **Text:** {e['message_text']}")
        lines.append(f"- **Expected:** `{e['expected_label']}` → **Predicted:** `{e['predicted_label']}`")
        lines.append(f"- **Top scores:** {e.get('top_scores')}")
        for h in e["explanations"]:
            lines.append(f"- {h}")
        lines.append("")
    if v1:
        lines += [
            "## Civic challenge v1 (MU-v2, diagnostic)",
            "",
            f"- Errors: **{v1['errors']}/{v1['total']}** | Benign FP: **{v1['benign_fp']}** | Harmful FN: **{v1['harmful_fn']}**",
            "",
        ]
    return "\n".join(lines)


def main() -> int:
    v2_dir = latest_eval_dir("civic_challenge_v2_eval_")
    if not v2_dir:
        print("No civic_challenge_v2_eval output found", file=sys.stderr)
        return 2
    v2_pred = v2_dir / "predictions_mu_v2.jsonl"
    suite = _suite_path()
    v2 = analyze_predictions(v2_pred, "civic_emergency_challenge_v2.jsonl", suite)

    v1 = None
    v1_pred = v2_dir / "predictions_mu_v1.jsonl"
    if v1_pred.is_file():
        v1 = analyze_predictions(v1_pred, "civic_emergency_challenge_v2.jsonl (MU-v1)", suite)

    md = render_md(v2, v1)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / "outputs" / f"mu_v2_error_analysis_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "REPORT.md").write_text(md, encoding="utf-8")
    (out_dir / "analysis.json").write_text(json.dumps({"v2": v2, "v1_on_v2": v1}, indent=2, ensure_ascii=False), encoding="utf-8")
    (ROOT / "docs" / "MU_V2_ERROR_ANALYSIS.md").write_text(md, encoding="utf-8")
    print(out_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
