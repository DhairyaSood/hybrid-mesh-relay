#!/usr/bin/env python3
"""Diagnose random-split vs event-holdout performance gaps (Task 3).

Read-only analysis relative to the messaging app. Writes JSON/Markdown under outputs/.
Does not tune models against held-out test metrics.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import yaml
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.metrics import f1_score
from sklearn.base import clone

from data import filter_corpus, label_distribution, load_task_frame
from preprocess import clean_text
from train_eval import build_pipeline, evaluate_split, stratified_splits

ROOT = Path(__file__).resolve().parents[1]
_TOKEN_RE = re.compile(r"[a-z0-9']+")


def _tokens(text: str) -> set[str]:
    return set(_TOKEN_RE.findall(text.lower()))


def jaccard(a: set[str], b: set[str]) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def near_duplicate_pairs(
    texts: list[str],
    groups: list[str],
    *,
    threshold: float = 0.85,
    max_compare_per_bucket: int = 400,
) -> dict:
    """Approximate near-duplicate rate within and across event groups.

    Uses token Jaccard on a capped sample for tractability.
    """
    by_group: dict[str, list[str]] = {}
    for t, g in zip(texts, groups):
        by_group.setdefault(g, []).append(t)

    within = {}
    for g, items in by_group.items():
        sample = items[:max_compare_per_bucket]
        toks = [_tokens(t) for t in sample]
        hits = 0
        comparisons = 0
        for i in range(len(toks)):
            for j in range(i + 1, len(toks)):
                comparisons += 1
                if jaccard(toks[i], toks[j]) >= threshold:
                    hits += 1
        within[g] = {
            "sample_size": len(sample),
            "comparisons": comparisons,
            "near_dup_pairs": hits,
            "pair_rate": float(hits / comparisons) if comparisons else 0.0,
        }

    # Cross-event: sample pairs between each group pair.
    names = sorted(by_group)
    cross = {}
    for i, g1 in enumerate(names):
        for g2 in names[i + 1 :]:
            a = by_group[g1][: max_compare_per_bucket // 2]
            b = by_group[g2][: max_compare_per_bucket // 2]
            ta = [_tokens(t) for t in a]
            tb = [_tokens(t) for t in b]
            hits = 0
            comparisons = 0
            for x in ta:
                for y in tb:
                    comparisons += 1
                    if jaccard(x, y) >= threshold:
                        hits += 1
            cross[f"{g1}__vs__{g2}"] = {
                "comparisons": comparisons,
                "near_dup_pairs": hits,
                "pair_rate": float(hits / comparisons) if comparisons else 0.0,
            }

    return {"threshold": threshold, "within_event": within, "across_events": cross}


def distinctive_terms(df: pd.DataFrame, cleaned: np.ndarray, top_k: int = 25) -> dict:
    """Top terms with highest relative frequency in each event vs the rest."""
    vec = CountVectorizer(min_df=5, max_features=20000, token_pattern=r"[a-z0-9']+")
    x = vec.fit_transform(cleaned)
    vocab = np.array(vec.get_feature_names_out())
    out = {}
    for event in sorted(df["event_group"].unique()):
        mask = (df["event_group"].to_numpy() == event)
        in_freq = np.asarray(x[mask].sum(axis=0)).ravel() + 1e-6
        out_freq = np.asarray(x[~mask].sum(axis=0)).ravel() + 1e-6
        in_rate = in_freq / in_freq.sum()
        out_rate = out_freq / out_freq.sum()
        lift = in_rate / out_rate
        # Prefer terms that appear often enough inside the event.
        score = lift * np.log1p(in_freq)
        idx = np.argsort(-score)[:top_k]
        out[event] = [
            {
                "term": vocab[i],
                "lift": float(lift[i]),
                "count_in_event": int(in_freq[i] - 1e-6),
            }
            for i in idx
        ]
    return out


def random_split_event_mix(
    labels: np.ndarray,
    groups: np.ndarray,
    cfg: dict,
) -> dict:
    splits = stratified_splits(
        np.arange(len(labels)).astype(object), labels, groups, cfg
    )
    mix = {}
    for name, part in splits.items():
        g = part["group"]
        counts = Counter(g.tolist())
        total = sum(counts.values())
        mix[name] = {
            "n": total,
            "event_fractions": {k: round(v / total, 4) for k, v in sorted(counts.items())},
        }
    return mix


def train_test_near_dup_leakage(
    train_texts: np.ndarray,
    test_texts: np.ndarray,
    *,
    threshold: float = 0.9,
    train_sample: int = 1500,
    test_sample: int = 800,
) -> dict:
    """Estimate fraction of test texts with a near-duplicate in train."""
    rng = np.random.default_rng(42)
    tr = train_texts
    te = test_texts
    if len(tr) > train_sample:
        tr = tr[rng.choice(len(tr), size=train_sample, replace=False)]
    if len(te) > test_sample:
        te = te[rng.choice(len(te), size=test_sample, replace=False)]
    train_toks = [_tokens(str(t)) for t in tr]
    leaked = 0
    for t in te:
        tt = _tokens(str(t))
        if any(jaccard(tt, u) >= threshold for u in train_toks):
            leaked += 1
    return {
        "threshold": threshold,
        "train_sample": len(tr),
        "test_sample": len(te),
        "test_with_near_dup_in_train": leaked,
        "rate": float(leaked / len(te)) if len(te) else 0.0,
        "note": (
            "Approximate sample-based estimate. Exact duplicate texts were already "
            "removed before splitting in the training pipeline."
        ),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs" / "default.yaml")
    args = parser.parse_args(argv)

    with args.config.open(encoding="utf-8") as fh:
        cfg = yaml.safe_load(fh)

    dataset_root = Path(cfg["dataset_root"])
    if not dataset_root.is_dir():
        print(f"ERROR: dataset_root not found: {dataset_root}", file=sys.stderr)
        return 2

    # Force Task 3 for this diagnostic.
    cfg = dict(cfg)
    cfg["task"] = "task3_help"

    corpus = load_task_frame(dataset_root, "task3_help")
    df, notes = filter_corpus(
        corpus,
        min_text_chars=int(cfg.get("min_text_chars", 3)),
        drop_duplicate_text=bool(cfg.get("drop_duplicate_text", True)),
    )
    feat = cfg["features"]
    cleaned = np.asarray(
        [
            clean_text(
                t,
                lowercase=bool(feat.get("lowercase", True)),
                strip_urls=bool(feat.get("strip_urls", True)),
                strip_mentions=bool(feat.get("strip_mentions", True)),
            )
            for t in df["text"].tolist()
        ],
        dtype=object,
    )
    keep = np.array([len(t) >= int(cfg.get("min_text_chars", 3)) for t in cleaned])
    df = df.loc[keep].reset_index(drop=True)
    cleaned = cleaned[keep]
    y = df["label"].to_numpy(dtype=int)
    groups = df["event_group"].to_numpy()
    label_names = {int(k): v for k, v in corpus.label_names.items()}
    label_ids = sorted(label_names.keys())

    # Per-event class distributions.
    per_event_dist = {}
    for event in sorted(df["event_group"].unique()):
        mask = df["event_group"] == event
        per_event_dist[event] = {
            "n": int(mask.sum()),
            "labels": label_distribution(df.loc[mask, "label"], label_names),
        }

    mix = random_split_event_mix(y, groups, cfg)

    # Build the same random split used by training and measure near-dup leakage.
    splits = stratified_splits(cleaned, y, groups, cfg)
    leakage = train_test_near_dup_leakage(splits["train"]["text"], splits["test"]["text"])

    # Exact cleaned-text overlap across events (should be near zero after dedupe).
    exact_cross = {}
    event_text_sets = {
        e: set(cleaned[df["event_group"].to_numpy() == e].tolist())
        for e in sorted(df["event_group"].unique())
    }
    names = sorted(event_text_sets)
    for i, g1 in enumerate(names):
        for g2 in names[i + 1 :]:
            inter = event_text_sets[g1] & event_text_sets[g2]
            exact_cross[f"{g1}__vs__{g2}"] = len(inter)

    near = near_duplicate_pairs(cleaned.tolist(), groups.tolist())
    distinctive = distinctive_terms(df, cleaned, top_k=20)

    # Recompute event hold-out with full per-class metrics (diagnostic only).
    pipeline = build_pipeline(cfg)
    holdouts = {}
    for holdout in sorted(df["event_group"].unique()):
        train_mask = df["event_group"].to_numpy() != holdout
        test_mask = ~train_mask
        pipe = clone(pipeline)
        pipe.fit(cleaned[train_mask], y[train_mask])
        pred = pipe.predict(cleaned[test_mask])
        metrics = evaluate_split(y[test_mask], pred, label_ids, label_names)
        # Majority baseline on the held-out event.
        values, counts = np.unique(y[test_mask], return_counts=True)
        majority = int(values[np.argmax(counts)])
        maj_pred = np.full_like(y[test_mask], majority)
        holdouts[holdout] = {
            "train_size": int(train_mask.sum()),
            "test_size": int(test_mask.sum()),
            "test_label_distribution": label_distribution(y[test_mask], label_names),
            "macro_f1": metrics["macro_f1"],
            "weighted_f1": metrics["weighted_f1"],
            "per_class": metrics["per_class"],
            "majority_baseline_macro_f1": float(
                f1_score(
                    y[test_mask],
                    maj_pred,
                    labels=label_ids,
                    average="macro",
                    zero_division=0,
                )
            ),
        }

    # Random held-out metrics for comparison (same protocol as train_eval).
    pipe = build_pipeline(cfg)
    pipe.fit(splits["train"]["text"], splits["train"]["label"])
    random_test_pred = pipe.predict(splits["test"]["text"])
    random_test = evaluate_split(
        splits["test"]["label"], random_test_pred, label_ids, label_names
    )

    # Also report random-test performance broken down by event membership of test rows.
    test_groups = splits["test"]["group"]
    by_event_in_random_test = {}
    for event in sorted(set(test_groups.tolist())):
        mask = test_groups == event
        if mask.sum() == 0:
            continue
        by_event_in_random_test[event] = {
            "n": int(mask.sum()),
            "macro_f1": float(
                f1_score(
                    splits["test"]["label"][mask],
                    random_test_pred[mask],
                    labels=label_ids,
                    average="macro",
                    zero_division=0,
                )
            ),
            "weighted_f1": float(
                f1_score(
                    splits["test"]["label"][mask],
                    random_test_pred[mask],
                    labels=label_ids,
                    average="weighted",
                    zero_division=0,
                )
            ),
        }

    summary = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "task": "task3_help",
        "filter_notes": notes,
        "n_rows": int(len(df)),
        "per_event_class_distribution": per_event_dist,
        "random_split_event_mixture": mix,
        "random_held_out_test": {
            "macro_f1": random_test["macro_f1"],
            "weighted_f1": random_test["weighted_f1"],
            "per_class": random_test["per_class"],
        },
        "random_test_macro_f1_by_event_membership": by_event_in_random_test,
        "event_holdout": holdouts,
        "exact_cleaned_text_overlap_across_events": exact_cross,
        "near_duplicate_analysis": near,
        "train_test_near_duplicate_leakage_estimate": leakage,
        "distinctive_terms_by_event": distinctive,
        "interpretation": [
            "Random stratified splits mix all events into train and test, so event-specific "
            "tokens (place names, disaster names) appear on both sides.",
            "Event hold-out removes an entire disaster collection from training; lower scores "
            "indicate domain shift, not necessarily a bug in the random-split score.",
            "Only three coarse event groups exist; conclusions about cross-event "
            "generalisation are limited.",
        ],
    }

    out_dir = ROOT / "outputs" / (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_task3_generalization"
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    metrics_path = out_dir / "generalization_analysis.json"
    with metrics_path.open("w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2, ensure_ascii=False)

    md = [
        "# Task 3 generalisation diagnostic",
        "",
        f"Random held-out macro-F1: **{random_test['macro_f1']:.4f}**",
        "",
        "## Event mixture in random splits",
        "```",
        json.dumps(mix, indent=2),
        "```",
        "",
        "## Event hold-out macro-F1",
        "",
    ]
    for event, stats in holdouts.items():
        md.append(
            f"- `{event}`: macro-F1={stats['macro_f1']:.4f} "
            f"(majority baseline={stats['majority_baseline_macro_f1']:.4f}, n={stats['test_size']})"
        )
    md += [
        "",
        "## Near-duplicate leakage estimate (train↔test, sample)",
        "```",
        json.dumps(leakage, indent=2),
        "```",
        "",
        "## Exact cleaned-text overlap across events",
        "```",
        json.dumps(exact_cross, indent=2),
        "```",
        "",
        "## Top distinctive terms (not full tweets)",
        "",
    ]
    for event, terms in distinctive.items():
        md.append(f"### {event}")
        md.append(", ".join(t["term"] for t in terms[:15]))
        md.append("")
    report_path = out_dir / "generalization_report.md"
    report_path.write_text("\n".join(md), encoding="utf-8")
    print(f"Wrote {metrics_path}")
    print(f"Wrote {report_path}")
    print(f"Random macro-F1={random_test['macro_f1']:.4f}")
    for event, stats in holdouts.items():
        print(f"Holdout {event}: macro-F1={stats['macro_f1']:.4f}")
    print(f"Near-dup train/test rate≈{leakage['rate']:.4f}")
    return 0


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
