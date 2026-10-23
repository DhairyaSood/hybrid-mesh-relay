#!/usr/bin/env python3
"""Train and evaluate a lightweight TF-IDF + Logistic Regression disaster classifier.

Isolated research prototype for Neyra. Does not touch Android/mesh code.
Does not send data to external services.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import yaml
from sklearn.base import clone
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    ConfusionMatrixDisplay,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import FeatureUnion, Pipeline
from sklearn.preprocessing import FunctionTransformer

from data import (
    URGENT_HELP_LABEL,
    filter_corpus,
    label_distribution,
    load_task_frame,
)
from preprocess import as_text_list, clean_text

ROOT = Path(__file__).resolve().parents[1]


def _load_config(path: Path) -> dict:
    with path.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def _resolve_dir(path_value: str) -> Path:
    path = Path(path_value)
    if not path.is_absolute():
        path = ROOT / path
    path.mkdir(parents=True, exist_ok=True)
    return path


def _clean_series(series: pd.Series, cfg: dict) -> np.ndarray:
    feat = cfg["features"]
    return np.asarray(
        [
            clean_text(
                t,
                lowercase=bool(feat.get("lowercase", True)),
                strip_urls=bool(feat.get("strip_urls", True)),
                strip_mentions=bool(feat.get("strip_mentions", True)),
            )
            for t in series.tolist()
        ],
        dtype=object,
    )


def build_pipeline(cfg: dict) -> Pipeline:
    feat = cfg["features"]
    word = TfidfVectorizer(
        analyzer="word",
        ngram_range=tuple(feat["word_ngram_range"]),
        max_features=int(feat["word_max_features"]),
        lowercase=False,  # cleaning already lowercases when configured
        min_df=2,
        sublinear_tf=True,
    )
    mode = str(feat.get("mode", "union"))
    if mode == "word_only":
        features = word
    elif mode == "union":
        char = TfidfVectorizer(
            analyzer="char_wb",
            ngram_range=tuple(feat["char_ngram_range"]),
            max_features=int(feat["char_max_features"]),
            lowercase=False,
            min_df=2,
            sublinear_tf=True,
        )
        features = FeatureUnion([("word", word), ("char", char)])
    else:
        raise ValueError(f"Unknown features.mode: {mode}")
    clf_cfg = cfg["classifier"]
    class_weight = clf_cfg.get("class_weight")
    if class_weight in (None, "null", "None"):
        class_weight = None
    classifier = LogisticRegression(
        class_weight=class_weight,
        max_iter=int(clf_cfg.get("max_iter", 2000)),
        solver=str(clf_cfg.get("solver", "lbfgs")),
        C=float(clf_cfg.get("C", 1.0)),
        random_state=int(cfg["random_seed"]),
    )
    # ColumnTransformer keeps a single text column interface for joblib.
    text_pipe = Pipeline(
        steps=[
            ("select", FunctionTransformer(as_text_list, validate=False)),
            ("tfidf", features),
            ("clf", classifier),
        ]
    )
    return text_pipe


def stratified_splits(
    texts: np.ndarray,
    labels: np.ndarray,
    groups: np.ndarray | None,
    cfg: dict,
) -> dict[str, dict[str, np.ndarray]]:
    seed = int(cfg["random_seed"])
    train_frac = float(cfg["split"]["train"])
    val_frac = float(cfg["split"]["validation"])
    test_frac = float(cfg["split"]["test"])
    if abs(train_frac + val_frac + test_frac - 1.0) > 1e-9:
        raise ValueError("split fractions must sum to 1.0")

    idx = np.arange(len(labels))
    idx_train, idx_temp, y_train, y_temp = train_test_split(
        idx,
        labels,
        test_size=(1.0 - train_frac),
        random_state=seed,
        stratify=labels,
    )
    relative_test = test_frac / (val_frac + test_frac)
    idx_val, idx_test, y_val, y_test = train_test_split(
        idx_temp,
        y_temp,
        test_size=relative_test,
        random_state=seed,
        stratify=y_temp,
    )

    def pack(indices: np.ndarray) -> dict[str, np.ndarray]:
        out = {"text": texts[indices], "label": labels[indices], "index": indices}
        if groups is not None:
            out["group"] = groups[indices]
        return out

    return {"train": pack(idx_train), "validation": pack(idx_val), "test": pack(idx_test)}


def majority_baseline_f1(y_true: np.ndarray, labels: list[int]) -> dict[str, float]:
    values, counts = np.unique(y_true, return_counts=True)
    majority = int(values[np.argmax(counts)])
    pred = np.full_like(y_true, majority)
    return {
        "majority_class": majority,
        "macro_f1": float(f1_score(y_true, pred, labels=labels, average="macro", zero_division=0)),
        "weighted_f1": float(
            f1_score(y_true, pred, labels=labels, average="weighted", zero_division=0)
        ),
    }


def evaluate_split(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    label_ids: list[int],
    label_names: dict[int, str],
) -> dict:
    target_names = [label_names[i] for i in label_ids]
    report = classification_report(
        y_true,
        y_pred,
        labels=label_ids,
        target_names=target_names,
        output_dict=True,
        zero_division=0,
    )
    precision, recall, f1, support = precision_recall_fscore_support(
        y_true, y_pred, labels=label_ids, zero_division=0
    )
    per_class = {}
    for i, lid in enumerate(label_ids):
        per_class[label_names[lid]] = {
            "precision": float(precision[i]),
            "recall": float(recall[i]),
            "f1": float(f1[i]),
            "support": int(support[i]),
        }
    cm = confusion_matrix(y_true, y_pred, labels=label_ids)
    return {
        "per_class": per_class,
        "macro_f1": float(report["macro avg"]["f1-score"]),
        "weighted_f1": float(report["weighted avg"]["f1-score"]),
        "accuracy": float(report["accuracy"]),
        "classification_report": report,
        "confusion_matrix": cm.tolist(),
        "confusion_matrix_labels": target_names,
    }


def save_confusion_matrix(cm: list[list[int]], labels: list[str], path: Path) -> None:
    fig, ax = plt.subplots(figsize=(8, 6))
    disp = ConfusionMatrixDisplay(
        confusion_matrix=np.asarray(cm), display_labels=labels
    )
    disp.plot(ax=ax, cmap="Blues", colorbar=False, xticks_rotation=45)
    ax.set_title("Held-out test confusion matrix")
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def redact_example(text: str, max_len: int = 120) -> str:
    """Shorten and lightly redact for error-analysis reporting."""
    cleaned = clean_text(text, lowercase=False, strip_urls=True, strip_mentions=True)
    cleaned = " ".join(cleaned.split())
    if len(cleaned) > max_len:
        cleaned = cleaned[: max_len - 3] + "..."
    return cleaned


def error_analysis(
    texts: np.ndarray,
    y_true: np.ndarray,
    y_pred: np.ndarray,
    label_names: dict[int, str],
    *,
    urgent_label_name: str | None,
    max_per_bucket: int = 5,
) -> dict:
    rows = []
    for text, true_id, pred_id in zip(texts, y_true, y_pred):
        if true_id == pred_id:
            continue
        rows.append(
            {
                "true_label": label_names[int(true_id)],
                "pred_label": label_names[int(pred_id)],
                "text_redacted": redact_example(str(text)),
            }
        )

    # Prefer false negatives for the urgent assistance-related class.
    urgent_fn = []
    if urgent_label_name:
        urgent_fn = [
            r
            for r in rows
            if r["true_label"] == urgent_label_name and r["pred_label"] != urgent_label_name
        ][:max_per_bucket]

    # Representative mix of other mistakes.
    other = [r for r in rows if r not in urgent_fn][:max_per_bucket]

    return {
        "n_errors": len(rows),
        "urgent_false_negatives": urgent_fn,
        "other_errors": other,
        "note": (
            "Examples are shortened and URL/mention-stripped. They are not claims about "
            "real persons. Classification is not proof of truthfulness or wrongdoing."
        ),
    }


def event_holdout_report(
    pipeline: Pipeline,
    df: pd.DataFrame,
    cleaned: np.ndarray,
    label_ids: list[int],
    label_names: dict[int, str],
) -> dict | None:
    """Secondary leakage check: train on two event groups, test on the held-out event.

    Not used for hyperparameter tuning — diagnostic only.
    """
    if "event_group" not in df.columns:
        return None
    groups = sorted(df["event_group"].unique().tolist())
    # Need at least two groups for a leave-one-group-out diagnostic.
    if len(groups) < 2:
        return None

    reports = {}
    y = df["label"].to_numpy(dtype=int)
    for holdout in groups:
        train_mask = df["event_group"].to_numpy() != holdout
        test_mask = ~train_mask
        if train_mask.sum() < 100 or test_mask.sum() < 50:
            continue
        pipe = clone(pipeline)
        pipe.fit(cleaned[train_mask], y[train_mask])
        pred = pipe.predict(cleaned[test_mask])
        reports[holdout] = {
            "train_size": int(train_mask.sum()),
            "test_size": int(test_mask.sum()),
            "macro_f1": float(
                f1_score(y[test_mask], pred, labels=label_ids, average="macro", zero_division=0)
            ),
            "weighted_f1": float(
                f1_score(
                    y[test_mask], pred, labels=label_ids, average="weighted", zero_division=0
                )
            ),
            "per_class_f1": {
                label_names[i]: float(
                    f1_score(
                        y[test_mask],
                        pred,
                        labels=[i],
                        average="macro",
                        zero_division=0,
                    )
                )
                for i in label_ids
            },
        }
    if not reports:
        return None
    return {
        "groups": groups,
        "limitation": (
            "Few independent event/source groups are available; leave-one-group-out "
            "is a diagnostic, not a definitive generalisation proof."
        ),
        "results": reports,
    }


def estimate_artifact_size(path: Path) -> dict:
    if not path.exists():
        return {"exists": False}
    bytes_size = path.stat().st_size
    return {
        "exists": True,
        "path": str(path),
        "bytes": bytes_size,
        "megabytes": round(bytes_size / (1024 * 1024), 3),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=ROOT / "configs" / "default.yaml",
        help="YAML config path",
    )
    parser.add_argument(
        "--task",
        choices=["task1_binary", "task2_type", "task3_help"],
        default=None,
        help="Override config task",
    )
    args = parser.parse_args(argv)

    cfg = _load_config(args.config)
    if args.task:
        cfg["task"] = args.task

    dataset_root = Path(cfg["dataset_root"])
    if not dataset_root.is_dir():
        print(
            f"ERROR: dataset_root not found: {dataset_root}\n"
            "Place the corpus on disk and set dataset_root in configs/default.yaml.\n"
            "Do not copy the dataset into the git repository.",
            file=sys.stderr,
        )
        return 2

    outputs_dir = _resolve_dir(cfg["outputs_dir"])
    models_dir = _resolve_dir(cfg["models_dir"])
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_" + cfg["task"]
    run_dir = outputs_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    print(f"Loading task={cfg['task']} from {dataset_root}")
    corpus = load_task_frame(dataset_root, cfg["task"])
    df, filter_notes = filter_corpus(
        corpus,
        min_text_chars=int(cfg.get("min_text_chars", 3)),
        drop_duplicate_text=bool(cfg.get("drop_duplicate_text", True)),
    )
    for note in filter_notes:
        print(f"  [filter] {note}")

    if len(df) < 500:
        print(
            "ERROR: filtered corpus is too small for a reliable train/val/test split "
            f"(n={len(df)}).",
            file=sys.stderr,
        )
        return 3

    label_names = {int(k): v for k, v in corpus.label_names.items()}
    label_ids = sorted(label_names.keys())
    class_dist = label_distribution(df["label"], label_names)
    print(f"Class distribution after filtering: {class_dist}")

    cleaned = _clean_series(df["text"], cfg)
    # Drop rows that became empty after cleaning.
    keep = np.array([len(t) >= int(cfg.get("min_text_chars", 3)) for t in cleaned])
    removed_clean = int((~keep).sum())
    if removed_clean:
        print(f"  [filter] Removed {removed_clean} rows empty after cleaning")
        df = df.loc[keep].reset_index(drop=True)
        cleaned = cleaned[keep]

    y = df["label"].to_numpy(dtype=int)
    groups = df["event_group"].to_numpy() if "event_group" in df.columns else None
    splits = stratified_splits(cleaned, y, groups, cfg)
    print(
        "Split sizes:",
        {k: int(len(v["label"])) for k, v in splits.items()},
    )
    print("Train class dist:", label_distribution(splits["train"]["label"], label_names))
    print("Val class dist:", label_distribution(splits["validation"]["label"], label_names))
    print("Test class dist:", label_distribution(splits["test"]["label"], label_names))

    pipeline = build_pipeline(cfg)
    print("Fitting TF-IDF + LogisticRegression on training split only...")
    pipeline.fit(splits["train"]["text"], splits["train"]["label"])

    # Validation metrics (for reporting only; no tuning loop inspects test).
    val_pred = pipeline.predict(splits["validation"]["text"])
    val_metrics = evaluate_split(
        splits["validation"]["label"], val_pred, label_ids, label_names
    )
    print(
        f"Validation macro-F1={val_metrics['macro_f1']:.4f} "
        f"weighted-F1={val_metrics['weighted_f1']:.4f}"
    )

    # Held-out test — evaluated once after validation reporting.
    test_pred = pipeline.predict(splits["test"]["text"])
    test_metrics = evaluate_split(splits["test"]["label"], test_pred, label_ids, label_names)
    baseline = majority_baseline_f1(splits["test"]["label"], label_ids)
    print(
        f"Test macro-F1={test_metrics['macro_f1']:.4f} "
        f"weighted-F1={test_metrics['weighted_f1']:.4f} "
        f"(majority baseline macro-F1={baseline['macro_f1']:.4f})"
    )

    cm_path = run_dir / "confusion_matrix_test.png"
    save_confusion_matrix(
        test_metrics["confusion_matrix"],
        test_metrics["confusion_matrix_labels"],
        cm_path,
    )

    urgent_name = URGENT_HELP_LABEL if cfg["task"] == "task3_help" else None
    errors = error_analysis(
        splits["test"]["text"],
        splits["test"]["label"],
        test_pred,
        label_names,
        urgent_label_name=urgent_name,
    )

    # Urgent false-negative rate for Task 3.
    urgent_stats = None
    if urgent_name and urgent_name in label_names.values():
        urgent_id = next(i for i, n in label_names.items() if n == urgent_name)
        mask = splits["test"]["label"] == urgent_id
        support = int(mask.sum())
        fn = int(((splits["test"]["label"] == urgent_id) & (test_pred != urgent_id)).sum())
        tp = int(((splits["test"]["label"] == urgent_id) & (test_pred == urgent_id)).sum())
        urgent_stats = {
            "label": urgent_name,
            "support": support,
            "true_positives": tp,
            "false_negatives": fn,
            "recall": float(tp / support) if support else None,
            "interpretation": (
                "Food and Medicine is a humanitarian Help class in this corpus "
                "(casualties, hospitals, water, ration, medical need per the corpus README). "
                "It is not a validated urgency detector and does not indicate criminality "
                "or terrorism."
            ),
        }
        print(
            f"Urgent-related class '{urgent_name}': recall={urgent_stats['recall']:.4f} "
            f"FN={fn}/{support}"
        )

    holdout = event_holdout_report(pipeline, df, cleaned, label_ids, label_names)
    if holdout:
        print("Event/source hold-out diagnostic (not used for tuning):")
        print(f"  {holdout.get('limitation')}")
        for event, stats in holdout["results"].items():
            print(
                f"  holdout={event} macro-F1={stats['macro_f1']:.4f} "
                f"n_test={stats['test_size']}"
            )

    feat_mode = str(cfg.get("features", {}).get("mode", "union"))
    if cfg.get("model_basename"):
        model_basename = str(cfg["model_basename"])
    elif feat_mode == "word_only":
        model_basename = f"{cfg['task']}_compact_word"
    else:
        model_basename = f"{cfg['task']}_tfidf_logreg"
    model_path = models_dir / f"{model_basename}.joblib"
    model_info = {"saved": False}
    if bool(cfg.get("save_model", True)):
        joblib.dump(
            {
                "pipeline": pipeline,
                "task": cfg["task"],
                "label_names": label_names,
                "config": cfg,
                "trained_at_utc": run_id,
            },
            model_path,
        )
        model_info = {"saved": True, **estimate_artifact_size(model_path)}
        print(f"Saved model artifact: {model_path} ({model_info.get('megabytes')} MB)")

    tfidf = pipeline.named_steps["tfidf"]
    if hasattr(tfidf, "transformer_list"):
        n_features = sum(len(vec.vocabulary_) for _name, vec in tfidf.transformer_list)
    else:
        n_features = len(tfidf.vocabulary_)

    summary = {
        "run_id": run_id,
        "task": cfg["task"],
        "dataset_root": str(dataset_root),
        "filter_notes": filter_notes,
        "class_distribution_filtered": class_dist,
        "split_sizes": {k: int(len(v["label"])) for k, v in splits.items()},
        "split_class_distribution": {
            "train": label_distribution(splits["train"]["label"], label_names),
            "validation": label_distribution(splits["validation"]["label"], label_names),
            "test": label_distribution(splits["test"]["label"], label_names),
        },
        "validation_metrics": {
            "macro_f1": val_metrics["macro_f1"],
            "weighted_f1": val_metrics["weighted_f1"],
            "accuracy": val_metrics["accuracy"],
            "per_class": val_metrics["per_class"],
        },
        "test_metrics": {
            "macro_f1": test_metrics["macro_f1"],
            "weighted_f1": test_metrics["weighted_f1"],
            "accuracy": test_metrics["accuracy"],
            "per_class": test_metrics["per_class"],
            "confusion_matrix": test_metrics["confusion_matrix"],
            "confusion_matrix_labels": test_metrics["confusion_matrix_labels"],
        },
        "majority_baseline_on_test": baseline,
        "urgent_assistance_proxy": urgent_stats,
        "error_analysis": errors,
        "event_holdout_diagnostic": holdout,
        "model_artifact": model_info,
        "n_tfidf_features": n_features,
        "safety_notes": [
            "Scope is disaster-management content classification only.",
            "Predictions assess message text, not sender identity.",
            "A label is not proof a message is true or that a person did wrong.",
            "Do not silently block, delete, delay, or suppress mesh delivery.",
            "Not connected to the live Android messaging pipeline.",
        ],
        "licence_note": (
            "Corpus is treated as published under CC BY 4.0 for this experiment; "
            "local package did not embed a LICENSE file. CrisisNLP/CrisisLex provenance "
            "may add third-party restrictions. Keep raw data and model artifacts out of git."
        ),
    }

    metrics_path = run_dir / "metrics.json"
    with metrics_path.open("w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2, ensure_ascii=False)

    # Human-readable markdown report (no full tweet dump).
    report_path = run_dir / "report.md"
    report_path.write_text(_render_report(summary), encoding="utf-8")
    print(f"Wrote {metrics_path}")
    print(f"Wrote {report_path}")
    print(f"Wrote {cm_path}")
    return 0


def _render_report(summary: dict) -> str:
    lines = [
        f"# Disaster classifier run `{summary['run_id']}`",
        "",
        f"**Task:** `{summary['task']}`",
        "",
        "## Class distribution (filtered)",
        "",
        "```",
        json.dumps(summary["class_distribution_filtered"], indent=2),
        "```",
        "",
        "## Held-out test metrics",
        "",
        f"- Macro-F1: **{summary['test_metrics']['macro_f1']:.4f}**",
        f"- Weighted-F1: **{summary['test_metrics']['weighted_f1']:.4f}**",
        f"- Accuracy: **{summary['test_metrics']['accuracy']:.4f}**",
        (
            f"- Majority-class baseline macro-F1: "
            f"**{summary['majority_baseline_on_test']['macro_f1']:.4f}**"
        ),
        "",
        "### Per-class",
        "",
        "| Class | Precision | Recall | F1 | Support |",
        "|---|---:|---:|---:|---:|",
    ]
    for name, m in summary["test_metrics"]["per_class"].items():
        lines.append(
            f"| {name} | {m['precision']:.3f} | {m['recall']:.3f} | {m['f1']:.3f} | {m['support']} |"
        )
    if summary.get("urgent_assistance_proxy"):
        u = summary["urgent_assistance_proxy"]
        lines += [
            "",
            "## Food and Medicine class (humanitarian label, not validated urgency)",
            "",
            f"- Recall: **{u['recall']:.4f}** ({u['true_positives']}/{u['support']}; FN={u['false_negatives']})",
            f"- {u['interpretation']}",
        ]
    lines += [
        "",
        "## Error analysis (redacted)",
        "",
        summary["error_analysis"]["note"],
        "",
        f"Total test errors: {summary['error_analysis']['n_errors']}",
        "",
    ]
    if summary["error_analysis"]["urgent_false_negatives"]:
        lines.append("### Food and Medicine false negatives")
        for ex in summary["error_analysis"]["urgent_false_negatives"]:
            lines.append(
                f"- true=`{ex['true_label']}` pred=`{ex['pred_label']}` :: {ex['text_redacted']}"
            )
    if summary["error_analysis"]["other_errors"]:
        lines.append("")
        lines.append("### Other mistakes")
        for ex in summary["error_analysis"]["other_errors"]:
            lines.append(
                f"- true=`{ex['true_label']}` pred=`{ex['pred_label']}` :: {ex['text_redacted']}"
            )
    lines += [
        "",
        "## Model artifact",
        "",
        "```",
        json.dumps(summary["model_artifact"], indent=2),
        "```",
        "",
        f"TF-IDF feature count: {summary['n_tfidf_features']}",
        "",
        "## Licence / safety",
        "",
        summary["licence_note"],
        "",
    ]
    for note in summary["safety_notes"]:
        lines.append(f"- {note}")
    lines.append("")
    return "\n".join(lines)


if __name__ == "__main__":
    # Allow running as a script from experiments/disaster_classifier/
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
