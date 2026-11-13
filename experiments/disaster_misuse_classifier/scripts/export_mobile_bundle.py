#!/usr/bin/env python3
"""Export MU TF-IDF+LR to neyra_tfidf_logreg_v2 JSON for Kotlin."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import joblib
import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def export_bundle(joblib_path: Path, out_path: Path, min_abs_weight: float = 1e-5) -> dict:
    blob = joblib.load(joblib_path)
    pipeline = blob["pipeline"]
    tfidf = pipeline.named_steps["tfidf"]
    clf = pipeline.named_steps["clf"]
    vocab = tfidf.vocabulary_
    idf = np.asarray(tfidf.idf_)
    coef = clf.coef_
    intercept = clf.intercept_
    classes = [str(c) for c in clf.classes_]

    per_class: dict[str, dict[str, float]] = {}
    for row, label in enumerate(classes):
        weights = {}
        for tok, j in vocab.items():
            w = float(coef[row, j] * idf[j])
            if abs(w) >= min_abs_weight:
                weights[tok] = w
        per_class[label] = weights

    bundle = {
        "format": "neyra_tfidf_logreg_v2",
        "task": blob.get("task", "misuse_advisory"),
        "class_order": classes,
        "intercepts": {label: float(intercept[i]) for i, label in enumerate(classes)},
        "token_weights_by_class": per_class,
        # v2: idf for every vocabulary term (including ones whose weights were
        # pruned above) so the client can apply TfidfVectorizer's L2 normalisation.
        "idf_by_token": {tok: float(idf[j]) for tok, j in vocab.items()},
        "vectorizer": {
            "token_pattern": tfidf.token_pattern,
            "norm": tfidf.norm,
            "sublinear_tf": bool(tfidf.sublinear_tf),
        },
        "ngram_range": list(tfidf.ngram_range),
        "preprocess": {
            "profile": "misuse_synthetic",
            "lowercase": True,
            "strip_urls": True,
            "strip_mentions": True,
            "sublinear_tf": bool(tfidf.sublinear_tf),
        },
    }
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(bundle, ensure_ascii=False), encoding="utf-8")
    return {"output": str(out_path), "bytes": out_path.stat().st_size, "classes": classes}


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--joblib", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    print(json.dumps(export_bundle(args.joblib, args.output), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
