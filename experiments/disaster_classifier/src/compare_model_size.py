#!/usr/bin/env python3
"""Compare compact text classifiers; primary metric = leave-one-group-out macro-F1."""

from __future__ import annotations

import argparse
import json
import resource
import sys
import time
import tracemalloc
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import yaml
from sklearn.base import clone
from sklearn.feature_extraction.text import HashingVectorizer, TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, precision_recall_fscore_support
from sklearn.pipeline import FeatureUnion, Pipeline
from sklearn.preprocessing import FunctionTransformer

from data import filter_corpus, load_task_frame
from preprocess import clean_text
from preprocess import as_text_list
from train_eval import evaluate_split, stratified_splits

ROOT = Path(__file__).resolve().parents[1]


def load_yaml(path: Path) -> dict:
    with path.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def clean_array(texts: pd.Series, feat_cfg: dict) -> np.ndarray:
    return np.asarray(
        [
            clean_text(
                t,
                lowercase=bool(feat_cfg.get("lowercase", True)),
                strip_urls=bool(feat_cfg.get("strip_urls", True)),
                strip_mentions=bool(feat_cfg.get("strip_mentions", True)),
            )
            for t in texts.tolist()
        ],
        dtype=object,
    )


def build_classifier(cfg: dict) -> LogisticRegression:
    c = cfg["classifier"]
    cw = c.get("class_weight")
    if cw in (None, "null", "None"):
        cw = None
    return LogisticRegression(
        class_weight=cw,
        max_iter=int(c.get("max_iter", 2000)),
        solver=str(c.get("solver", "lbfgs")),
        C=float(c.get("C", 1.0)),
        random_state=int(cfg["random_seed"]),
    )


def build_features(variant: dict):
    vtype = variant["type"]
    if vtype == "tfidf_word":
        return TfidfVectorizer(
            analyzer="word",
            ngram_range=tuple(variant["word_ngram_range"]),
            max_features=int(variant["word_max_features"]),
            lowercase=False,
            min_df=2,
            sublinear_tf=True,
        )
    if vtype == "tfidf_union":
        word = TfidfVectorizer(
            analyzer="word",
            ngram_range=tuple(variant["word_ngram_range"]),
            max_features=int(variant["word_max_features"]),
            lowercase=False,
            min_df=2,
            sublinear_tf=True,
        )
        char = TfidfVectorizer(
            analyzer="char_wb",
            ngram_range=tuple(variant["char_ngram_range"]),
            max_features=int(variant["char_max_features"]),
            lowercase=False,
            min_df=2,
            sublinear_tf=True,
        )
        return FeatureUnion([("word", word), ("char", char)])
    if vtype == "hash_word":
        return HashingVectorizer(
            analyzer="word",
            ngram_range=tuple(variant["word_ngram_range"]),
            n_features=int(variant["n_features"]),
            alternate_sign=False,
            lowercase=False,
            norm="l2",
        )
    if vtype == "hash_union":
        word = HashingVectorizer(
            analyzer="word",
            ngram_range=tuple(variant["word_ngram_range"]),
            n_features=int(variant["word_n_features"]),
            alternate_sign=False,
            lowercase=False,
            norm="l2",
        )
        char = HashingVectorizer(
            analyzer="char_wb",
            ngram_range=tuple(variant["char_ngram_range"]),
            n_features=int(variant["char_n_features"]),
            alternate_sign=False,
            lowercase=False,
            norm="l2",
        )
        return FeatureUnion([("word", word), ("char", char)])
    raise ValueError(f"Unknown variant type: {vtype}")


def build_pipeline(cfg: dict, variant: dict) -> Pipeline:
    feats = build_features(variant)
    return Pipeline(
        steps=[
            ("select", FunctionTransformer(as_text_list, validate=False)),
            ("feats", feats),
            ("clf", build_classifier(cfg)),
        ]
    )


def feature_dimension(pipeline: Pipeline) -> int | None:
    feats = pipeline.named_steps["feats"]
    if isinstance(feats, FeatureUnion):
        total = 0
        for _name, trans in feats.transformer_list:
            if hasattr(trans, "vocabulary_"):
                total += len(trans.vocabulary_)
            elif hasattr(trans, "n_features"):
                total += int(trans.n_features)
        return total
    if hasattr(feats, "vocabulary_"):
        return len(feats.vocabulary_)
    if hasattr(feats, "n_features"):
        return int(feats.n_features)
    return None


def leave_one_group_out(
    pipeline: Pipeline,
    cleaned: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    label_ids: list[int],
    label_names: dict[int, str],
) -> dict:
    holdout_results = {}
    macro_scores = []
    for holdout in sorted(set(groups.tolist())):
        train_mask = groups != holdout
        test_mask = ~train_mask
        if train_mask.sum() < 100 or test_mask.sum() < 50:
            continue
        pipe = clone(pipeline)
        pipe.fit(cleaned[train_mask], y[train_mask])
        pred = pipe.predict(cleaned[test_mask])
        metrics = evaluate_split(y[test_mask], pred, label_ids, label_names)
        recall = {name: metrics["per_class"][name]["recall"] for name in metrics["per_class"]}
        holdout_results[holdout] = {
            "train_size": int(train_mask.sum()),
            "test_size": int(test_mask.sum()),
            "macro_f1": metrics["macro_f1"],
            "weighted_f1": metrics["weighted_f1"],
            "per_class_recall": recall,
        }
        macro_scores.append(metrics["macro_f1"])
    return {
        "holdouts": holdout_results,
        "mean_macro_f1": float(np.mean(macro_scores)) if macro_scores else None,
        "min_macro_f1": float(np.min(macro_scores)) if macro_scores else None,
        "n_holdouts": len(macro_scores),
    }


def random_holdout_metrics(
    pipeline: Pipeline,
    cleaned: np.ndarray,
    y: np.ndarray,
    groups: np.ndarray,
    cfg: dict,
    label_ids: list[int],
    label_names: dict[int, str],
) -> dict:
    split_cfg = {
        "random_seed": cfg["random_seed"],
        "split": {"train": 0.70, "validation": 0.15, "test": 0.15},
    }
    splits = stratified_splits(cleaned, y, groups, split_cfg)
    pipe = clone(pipeline)
    pipe.fit(splits["train"]["text"], splits["train"]["label"])
    pred = pipe.predict(splits["test"]["text"])
    metrics = evaluate_split(splits["test"]["label"], pred, label_ids, label_names)
    recall = {name: metrics["per_class"][name]["recall"] for name in metrics["per_class"]}
    return {
        "macro_f1": metrics["macro_f1"],
        "weighted_f1": metrics["weighted_f1"],
        "per_class_recall": recall,
        "test_size": int(len(splits["test"]["label"])),
    }


def measure_latency_and_memory(
    pipeline: Pipeline,
    sample_texts: np.ndarray,
    repeats: int,
) -> dict:
    texts = sample_texts[: min(500, len(sample_texts))]
    if len(texts) == 0:
        return {"latency_ms_per_text": None, "peak_tracemalloc_mb": None, "rss_after_predict_mb": None}

    # Warm-up
    pipeline.predict(texts[:10])

    tracemalloc.start()
    t0 = time.perf_counter()
    for _ in range(repeats):
        pipeline.predict(texts)
    elapsed = time.perf_counter() - t0
    _current, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS ru_maxrss is bytes; Linux often KB — report approximate MB from peak tracemalloc + file size note
    peak_mb = peak / (1024 * 1024)
    per_text_ms = (elapsed / (repeats * len(texts))) * 1000.0
    return {
        "latency_ms_per_text": round(per_text_ms, 4),
        "latency_batch_size": len(texts),
        "latency_repeats": repeats,
        "peak_tracemalloc_mb": round(peak_mb, 3),
        "rss_max_kb_reported": int(rss_kb),
        "note": (
            "Latency is single-process batch predict on CPU. rss_max varies by OS; "
            "use peak_tracemalloc_mb as a relative comparison across variants."
        ),
    }


def prepare_task_frame(cfg: dict, task: str):
    dataset_root = Path(cfg["dataset_root"])
    corpus = load_task_frame(dataset_root, task)
    df, notes = filter_corpus(
        corpus,
        min_text_chars=int(cfg.get("min_text_chars", 3)),
        drop_duplicate_text=bool(cfg.get("drop_duplicate_text", True)),
    )
    cleaned = clean_array(df["text"], cfg["features"])
    keep = np.array([len(t) >= int(cfg.get("min_text_chars", 3)) for t in cleaned])
    df = df.loc[keep].reset_index(drop=True)
    cleaned = cleaned[keep]
    y = df["label"].to_numpy(dtype=int)
    groups = df["event_group"].to_numpy()
    label_names = {int(k): v for k, v in corpus.label_names.items()}
    label_ids = sorted(label_names.keys())
    return df, cleaned, y, groups, label_ids, label_names, notes


def run_comparison(cfg: dict, run_dir: Path, models_dir: Path) -> dict:
    variants = cfg["variants"]
    all_results = {"tasks": {}, "recommendation": {}}

    for task in cfg["tasks"]:
        print(f"\n=== Task {task} ===")
        _df, cleaned, y, groups, label_ids, label_names, notes = prepare_task_frame(cfg, task)
        task_entry = {
            "filter_notes": notes,
            "n_rows": int(len(y)),
            "group_column": "event_group",
            "groups": sorted(set(groups.tolist())),
            "variants": {},
        }

        # Fit full corpus once per variant for size/latency artifact (secondary to LOO metrics).
        for vname, vdef in variants.items():
            print(f"  variant={vname} ...")
            pipeline = build_pipeline(cfg, vdef)
            loo = leave_one_group_out(
                pipeline, cleaned, y, groups, label_ids, label_names
            )
            random_m = random_holdout_metrics(
                pipeline, cleaned, y, groups, cfg, label_ids, label_names
            )

            # Artifact: fit on all data for size/latency only (not used as primary metric).
            fit_pipe = clone(pipeline)
            fit_pipe.fit(cleaned, y)
            model_path = models_dir / f"{task}_{vname}.joblib"
            joblib.dump(
                {
                    "pipeline": fit_pipe,
                    "task": task,
                    "variant": vname,
                    "label_names": label_names,
                },
                model_path,
            )
            size_bytes = model_path.stat().st_size
            perf = measure_latency_and_memory(
                fit_pipe,
                cleaned,
                int(cfg.get("latency_repeats", 5)),
            )
            task_entry["variants"][vname] = {
                "description": vdef.get("description", ""),
                "type": vdef["type"],
                "feature_dimension": feature_dimension(fit_pipe),
                "model_path": str(model_path),
                "model_bytes": size_bytes,
                "model_megabytes": round(size_bytes / (1024 * 1024), 4),
                "primary_holdout": loo,
                "secondary_random_split": random_m,
                "inference": perf,
            }
            print(
                f"    LOO mean macro-F1={loo['mean_macro_f1']:.4f} "
                f"min={loo['min_macro_f1']:.4f} "
                f"size={size_bytes/1024:.1f}KB "
                f"lat={perf['latency_ms_per_text']}ms/text"
            )

        all_results["tasks"][task] = task_entry

    # Recommendation per task: smallest model meeting ratio vs baseline_full LOO mean.
    ratio_req = float(cfg.get("min_holdout_macro_f1_ratio", 0.95))
    recs = {}
    for task, task_entry in all_results["tasks"].items():
        baseline = task_entry["variants"].get("baseline_full", {})
        base_mean = baseline.get("primary_holdout", {}).get("mean_macro_f1")
        if base_mean is None:
            continue
        threshold = base_mean * ratio_req
        candidates = []
        for vname, v in task_entry["variants"].items():
            mean_f1 = v["primary_holdout"]["mean_macro_f1"]
            if mean_f1 is None:
                continue
            candidates.append(
                (
                    v["model_bytes"],
                    vname,
                    mean_f1,
                    v["primary_holdout"]["min_macro_f1"],
                )
            )
        candidates.sort(key=lambda x: x[0])
        chosen = None
        for size, vname, mean_f1, min_f1 in candidates:
            if mean_f1 >= threshold:
                chosen = {
                    "variant": vname,
                    "model_bytes": size,
                    "mean_holdout_macro_f1": mean_f1,
                    "min_holdout_macro_f1": min_f1,
                    "baseline_mean_holdout_macro_f1": base_mean,
                    "threshold": threshold,
                    "ratio_rule": ratio_req,
                }
                break
        recs[task] = {
            "baseline_full_mean_holdout_macro_f1": base_mean,
            "smallest_acceptable": chosen,
            "interpretation": (
                "Primary metric is mean leave-one-group-out macro-F1. "
                "Acceptable = >= {:.0%} of baseline_full mean. "
                "Dataset supports disaster-message classification only."
            ).format(ratio_req),
        }
    all_results["recommendation"] = recs
    return all_results


def render_markdown(results: dict, run_id: str) -> str:
    lines = [
        f"# Model size comparison `{run_id}`",
        "",
        "Primary metric: **mean leave-one-group-out macro-F1** (event or source).",
        "Secondary: stratified random 70/15/15 test (not used for selection).",
        "",
    ]
    for task, task_entry in results["tasks"].items():
        lines += [f"## {task}", "", "| Variant | LOO mean F1 | LOO min F1 | Random test F1 | Size (MB) | ms/text | Feat dim |", "|---|---:|---:|---:|---:|---:|---:|"]
        for vname, v in task_entry["variants"].items():
            loo = v["primary_holdout"]
            rnd = v["secondary_random_split"]
            lines.append(
                f"| `{vname}` | {loo['mean_macro_f1']:.4f} | {loo['min_macro_f1']:.4f} | "
                f"{rnd['macro_f1']:.4f} | {v['model_megabytes']:.3f} | "
                f"{v['inference']['latency_ms_per_text']} | {v['feature_dimension']} |"
            )
        lines.append("")
        rec = results["recommendation"].get(task, {})
        if rec.get("smallest_acceptable"):
            s = rec["smallest_acceptable"]
            lines.append(
                f"**Smallest acceptable (≥{s['ratio_rule']:.0%} of baseline LOO mean):** "
                f"`{s['variant']}` — mean LOO F1 {s['mean_holdout_macro_f1']:.4f}, "
                f"{s['model_bytes']/1024:.1f} KB"
            )
        lines.append("")
    lines.append("## Per-class recall (hold-out folds)")
    for task, task_entry in results["tasks"].items():
        lines.append(f"### {task}")
        for vname in ("baseline_full", "hash_word_8k", "compact_word", "reduced_vocab"):
            if vname not in task_entry["variants"]:
                continue
            lines.append(f"#### `{vname}`")
            for holdout, h in task_entry["variants"][vname]["primary_holdout"]["holdouts"].items():
                recalls = ", ".join(
                    f"{k}={v:.2f}" for k, v in h["per_class_recall"].items()
                )
                lines.append(f"- `{holdout}` macro-F1={h['macro_f1']:.3f}: {recalls}")
            lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=ROOT / "configs" / "size_comparison.yaml",
    )
    args = parser.parse_args(argv)
    cfg = load_yaml(args.config)
    dataset_root = Path(cfg["dataset_root"])
    if not dataset_root.is_dir():
        print(f"ERROR: dataset_root not found: {dataset_root}", file=sys.stderr)
        return 2

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_size_comparison"
    run_dir = ROOT / cfg.get("outputs_dir", "outputs") / run_id
    models_dir = ROOT / cfg.get("models_dir", "models/size_comparison")
    run_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)

    results = run_comparison(cfg, run_dir, models_dir)
    results["run_id"] = run_id
    results["config"] = str(args.config)
    results["safety"] = (
        "Humanitarian disaster-message classification only. "
        "Not urgency, criminality, or terrorism detection."
    )

    json_path = run_dir / "size_comparison.json"
    json_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    md_path = run_dir / "size_comparison_report.md"
    md_path.write_text(render_markdown(results, run_id), encoding="utf-8")
    print(f"\nWrote {json_path}")
    print(f"Wrote {md_path}")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
