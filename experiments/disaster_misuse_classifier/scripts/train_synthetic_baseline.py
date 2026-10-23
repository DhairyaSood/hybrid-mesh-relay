#!/usr/bin/env python3
"""Train/evaluate TF-IDF + Logistic Regression on synthetic misuse JSONL splits."""

from __future__ import annotations

import argparse
import json
import platform
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import yaml
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)
from sklearn.pipeline import Pipeline

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


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def load_family_set(path: Path) -> set[str]:
    return {ln.strip() for ln in path.read_text(encoding="utf-8").splitlines() if ln.strip()}


def assert_split_isolation(cfg: dict) -> dict:
    tr = load_family_set(ROOT / cfg["data"]["train_families_manifest"])
    va = load_family_set(ROOT / cfg["data"]["validation_families_manifest"])
    te = load_family_set(ROOT / cfg["data"]["test_families_manifest"])
    overlap = {
        "train_val": sorted(tr & va),
        "train_test": sorted(tr & te),
        "val_test": sorted(va & te),
    }
    ok = not (overlap["train_val"] or overlap["train_test"] or overlap["val_test"])
    return {"ok": ok, "family_counts": {"train": len(tr), "val": len(va), "test": len(te)}, "overlap": overlap}


def rows_to_xy(rows: list[dict]) -> tuple[list[str], np.ndarray, list[dict]]:
    texts = [clean_text(r["message_text"]) for r in rows]
    y = np.array([r["label"] for r in rows])
    return texts, y, rows


def build_pipeline(cfg: dict, word_max_features: int, C: float) -> Pipeline:
    clf_cfg = cfg["classifier"]
    return Pipeline(
        [
            (
                "tfidf",
                TfidfVectorizer(
                    analyzer="word",
                    ngram_range=tuple(cfg["features"]["word_ngram_range"]),
                    max_features=int(word_max_features),
                    min_df=int(cfg["features"]["min_df"]),
                    sublinear_tf=bool(cfg["features"]["sublinear_tf"]),
                    lowercase=False,
                ),
            ),
            (
                "clf",
                LogisticRegression(
                    C=float(C),
                    class_weight=clf_cfg.get("class_weight"),
                    max_iter=int(clf_cfg.get("max_iter", 3000)),
                    solver=str(clf_cfg.get("solver", "lbfgs")),
                    random_state=int(cfg["random_seed"]),
                ),
            ),
        ]
    )


def evaluate(y_true: np.ndarray, y_pred: np.ndarray, labels: list[str]) -> dict:
    prec, rec, f1, sup = precision_recall_fscore_support(
        y_true, y_pred, labels=labels, zero_division=0
    )
    per_class = {}
    for i, lab in enumerate(labels):
        per_class[lab] = {
            "precision": float(prec[i]),
            "recall": float(rec[i]),
            "f1": float(f1[i]),
            "support": int(sup[i]),
        }
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)),
        "weighted_f1": float(
            f1_score(y_true, y_pred, labels=labels, average="weighted", zero_division=0)
        ),
        "per_class": per_class,
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=labels).tolist(),
        "confusion_matrix_labels": labels,
        "classification_report": classification_report(
            y_true, y_pred, labels=labels, target_names=labels, output_dict=True, zero_division=0
        ),
    }


def metrics_by_subtype(rows: list[dict], y_pred: np.ndarray, labels: list[str]) -> dict:
    out = {}
    for subtype in ("english", "devanagari_hindi", "latin_hinglish"):
        idx = [i for i, r in enumerate(rows) if r.get("language_subtype") == subtype]
        if not idx:
            continue
        y_t = np.array([rows[i]["label"] for i in idx])
        y_p = y_pred[idx]
        out[subtype] = evaluate(y_t, y_p, labels)
        out[subtype]["n"] = len(idx)
    return out


def confusion_pairs(cm: list[list[int]], labels: list[str], min_count: int = 5) -> list[dict]:
    pairs = []
    for i, true_lab in enumerate(labels):
        for j, pred_lab in enumerate(labels):
            if i == j:
                continue
            c = cm[i][j]
            if c >= min_count:
                pairs.append({"true": true_lab, "pred": pred_lab, "count": c})
    pairs.sort(key=lambda x: -x["count"])
    return pairs


def package_versions() -> dict:
    import sklearn

    out = {
        "python": platform.python_version(),
        "numpy": np.__version__,
        "scikit_learn": sklearn.__version__,
        "joblib": joblib.__version__,
        "PyYAML": yaml.__version__,
    }
    try:
        pip = subprocess.run(
            [sys.executable, "-m", "pip", "freeze"],
            capture_output=True,
            text=True,
            check=False,
        )
        out["pip_freeze"] = pip.stdout.strip().splitlines()
    except Exception:
        pass
    return out


def latency_benchmark(pipe: Pipeline, texts: list[str], cfg: dict) -> dict:
    lat_cfg = cfg.get("latency", {})
    warmup = int(lat_cfg.get("warmup_predictions", 50))
    batches = int(lat_cfg.get("timed_batches", 20))
    batch_size = int(lat_cfg.get("batch_size", 32))
    sample = texts[: max(batch_size, 1)]
    for _ in range(warmup):
        pipe.predict(sample)
    times = []
    for _ in range(batches):
        t0 = time.perf_counter()
        for i in range(0, len(sample), batch_size):
            pipe.predict(sample[i : i + batch_size])
        times.append(time.perf_counter() - t0)
    arr = np.array(times)
    per_msg_ms = 1000 * arr.mean() / len(sample)
    return {
        "batch_size": batch_size,
        "sample_messages": len(sample),
        "mean_batch_ms": float(arr.mean() * 1000),
        "per_message_ms_mean": float(per_msg_ms),
        "per_message_ms_p95": float(1000 * np.percentile(arr, 95) / len(sample)),
    }


SMOKE_CASES = [
    ("benign_emergency_information", "Evacuate to the school shelter with ID. Helpline 112."),
    ("direct_threat_or_intimidation", "If you cut the line again you will regret it. Stay away."),
    ("coercion_or_extortion", "Pay a fee at the desk or you will not get a kit."),
    ("possible_relief_worker_impersonation_or_fraud", "I am from Rapid Relief NGO send donation to verify slot."),
    ("report_or_warning_about_misconduct", "Warning: fake UPI message for flood kits. Do not pay strangers."),
    ("quoted_or_historical_threat", "Bulletin quotes witness: people were shouting threats during chaos."),
    ("ambiguous_needs_review", "Shelter maybe open? heard about fees not sure who said."),
    ("hard_neg_report", "Reports say looting happened nearby. Do not pay strangers online."),
    ("hard_neg_quote", "RT @District: do not block rescue trucks — sharing for info."),
    ("hard_neg_debunk", "Do not share this hoax about fake NGOs it is false rumor."),
]


def smoke_test(pipe: Pipeline) -> list[dict]:
    rows = []
    for name, text in SMOKE_CASES:
        pred_label = str(pipe.predict([clean_text(text)])[0])
        rows.append({"case": name, "text": text, "predicted_label": pred_label})
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs/synthetic_train.yaml")
    args = parser.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))

    leakage = assert_split_isolation(cfg)
    if not leakage["ok"]:
        print("ERROR: template_family_id leakage between splits", leakage["overlap"], file=sys.stderr)
        return 2

    train_rows = load_jsonl(ROOT / cfg["data"]["train_split"])
    val_rows = load_jsonl(ROOT / cfg["data"]["validation_split"])
    test_rows = load_jsonl(ROOT / cfg["data"]["test_split"])

    # Ensure splits only contain expected families
    tr_fam = load_family_set(ROOT / cfg["data"]["train_families_manifest"])
    for name, rows, fam in (
        ("train", train_rows, tr_fam),
        ("validation", val_rows, load_family_set(ROOT / cfg["data"]["validation_families_manifest"])),
        ("test", test_rows, load_family_set(ROOT / cfg["data"]["test_families_manifest"])),
    ):
        bad = {r["template_family_id"] for r in rows} - fam
        if bad:
            print(f"ERROR: {name} split has families not in manifest: {list(bad)[:5]}", file=sys.stderr)
            return 2

    x_train, y_train, _ = rows_to_xy(train_rows)
    x_val, y_val, _ = rows_to_xy(val_rows)
    x_test, y_test, test_meta = rows_to_xy(test_rows)

    labels = LABELS_ORDERED
    grid = cfg["tuning_grid"]
    best = {"macro_f1": -1.0}
    tuning_rows = []

    for mf in grid["word_max_features"]:
        for c in grid["C"]:
            pipe = build_pipeline(cfg, mf, c)
            pipe.fit(x_train, y_train)
            pred_val = pipe.predict(x_val)
            macro = float(f1_score(y_val, pred_val, labels=labels, average="macro", zero_division=0))
            tuning_rows.append({"word_max_features": mf, "C": c, "validation_macro_f1": macro})
            if macro > best["macro_f1"]:
                best = {
                    "macro_f1": macro,
                    "word_max_features": mf,
                    "C": c,
                    "pipeline": pipe,
                }

    pipe = best["pipeline"]
    frozen = {
        "word_max_features": best["word_max_features"],
        "C": best["C"],
        "word_ngram_range": cfg["features"]["word_ngram_range"],
        "min_df": cfg["features"]["min_df"],
        "sublinear_tf": cfg["features"]["sublinear_tf"],
        "class_weight": cfg["classifier"]["class_weight"],
        "random_seed": cfg["random_seed"],
    }

    val_pred = pipe.predict(x_val)
    test_pred = pipe.predict(x_test)

    val_metrics = evaluate(y_val, val_pred, labels)
    test_metrics = evaluate(y_test, test_pred, labels)

    val_by_lang = metrics_by_subtype(val_rows, val_pred, labels)
    test_by_lang = metrics_by_subtype(test_meta, test_pred, labels)

    concern_labels = {
        "report_or_warning_about_misconduct",
        "quoted_or_historical_threat",
        "ambiguous_needs_review",
    }
    test_confusions = confusion_pairs(test_metrics["confusion_matrix"], labels, min_count=3)
    concern_confusions = [p for p in test_confusions if p["true"] in concern_labels or p["pred"] in concern_labels]

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = ROOT / cfg["outputs_dir"] / f"synthetic_baseline_{run_id}"
    out_dir.mkdir(parents=True, exist_ok=True)
    models_dir = ROOT / cfg["models_dir"]
    models_dir.mkdir(parents=True, exist_ok=True)
    model_path = models_dir / f"{cfg['model_basename']}.joblib"

    clf_classes = [str(x) for x in pipe.named_steps["clf"].classes_]
    artifact = {
        "pipeline": pipe,
        "labels_ordered": labels,
        "classifier_classes": clf_classes,
        "frozen_hyperparameters": frozen,
        "trained_on": "splits/train.jsonl only",
        "selection_metric": cfg["selection_metric"],
        "run_id": run_id,
    }
    joblib.dump(artifact, model_path)
    model_bytes = model_path.stat().st_size

    smoke = smoke_test(pipe)

    report = {
        "run_id": run_id,
        "disclaimer": (
            "All metrics are on synthetic held-out data only. "
            "They do not demonstrate real-world misuse-detection accuracy, safety, or fairness."
        ),
        "data_audit_note": (
            "Pre-training spot-check found no material label/scenario mismatches; "
            "labels are generator-assigned."
        ),
        "package_versions": package_versions(),
        "commands": [
            f"{sys.executable} scripts/train_synthetic_baseline.py --config configs/synthetic_train.yaml",
        ],
        "leakage_check": leakage,
        "tuning_on_validation_only": tuning_rows,
        "selected_config": frozen,
        "validation_metrics": val_metrics,
        "test_metrics": test_metrics,
        "validation_metrics_by_language_subtype": val_by_lang,
        "test_metrics_by_language_subtype": test_by_lang,
        "test_notable_confusions": test_confusions[:15],
        "test_concern_label_confusions": concern_confusions[:15],
        "model_artifact": {
            "path": str(model_path),
            "bytes": model_bytes,
            "megabytes": round(model_bytes / (1024 * 1024), 3),
        },
        "latency": latency_benchmark(pipe, x_test, cfg),
        "inference_smoke_test": smoke,
        "train_rows": len(train_rows),
        "validation_rows": len(val_rows),
        "test_rows": len(test_rows),
    }

    (out_dir / "metrics.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    (out_dir / "frozen_config.json").write_text(json.dumps(frozen, indent=2), encoding="utf-8")

    md = _render_md(report)
    (out_dir / "report.md").write_text(md, encoding="utf-8")

    print(json.dumps({k: report[k] for k in ("run_id", "selected_config", "validation_metrics", "test_metrics")}, indent=2))
    print(f"Wrote {out_dir / 'metrics.json'}")
    print(f"Saved model {model_path} ({report['model_artifact']['megabytes']} MB)")
    return 0


def _render_md(report: dict) -> str:
    vm = report["validation_metrics"]
    tm = report["test_metrics"]
    lines = [
        f"# Synthetic misuse baseline `{report['run_id']}`",
        "",
        report["disclaimer"],
        "",
        "## Selected hyperparameters (validation)",
        "",
        "```json",
        json.dumps(report["selected_config"], indent=2),
        "```",
        "",
        "## Validation metrics",
        "",
        f"- Accuracy: {vm['accuracy']:.4f}",
        f"- Macro-F1: {vm['macro_f1']:.4f}",
        f"- Weighted-F1: {vm['weighted_f1']:.4f}",
        "",
        "## Test metrics (single final evaluation)",
        "",
        f"- Accuracy: {tm['accuracy']:.4f}",
        f"- Macro-F1: {tm['macro_f1']:.4f}",
        f"- Weighted-F1: {tm['weighted_f1']:.4f}",
        "",
        "### Test by language subtype",
        "",
    ]
    for st, m in report["test_metrics_by_language_subtype"].items():
        lines.append(f"- **{st}** (n={m['n']}): macro-F1={m['macro_f1']:.4f}, accuracy={m['accuracy']:.4f}")
    lines += [
        "",
        f"Model size: {report['model_artifact']['megabytes']} MB",
        f"Latency: ~{report['latency']['per_message_ms_mean']:.3f} ms/msg (local batch)",
        "",
    ]
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
