#!/usr/bin/env python3
"""Export a fitted word-TF-IDF + logistic regression pipeline for on-device inference.

Produces JSON (vocabulary + sparse-friendly token weights per class) that can be
implemented in Kotlin without scikit-learn. Char features and sklearn joblib
pipelines are not supported by this exporter.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import joblib
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
import preprocess  # noqa: F401 — registers pickle dependencies for joblib


def export_bundle(joblib_path: Path, out_path: Path) -> dict:
    blob = joblib.load(joblib_path)
    pipeline = blob["pipeline"]
    label_names = {int(k): v for k, v in blob["label_names"].items()}
    tfidf_step = pipeline.named_steps["tfidf"]
    clf = pipeline.named_steps["clf"]

    if hasattr(tfidf_step, "transformer_list"):
        raise ValueError(
            "export_mobile_bundle supports word-only TF-IDF pipelines only "
            "(set features.mode=word_only in training config)."
        )

    vocab = tfidf_step.vocabulary_
    idf = tfidf_step.idf_
    # sklearn stores vocabulary_ as token -> index
    tokens = sorted(vocab.keys(), key=lambda t: vocab[t])
    token_ids = [vocab[t] for t in tokens]

    # LogisticRegression coef_ shape (n_classes, n_features) for multinomial
    coef = clf.coef_
    intercept = clf.intercept_
    classes = [int(c) for c in clf.classes_]

    # Precompute idf * coef contribution per token for inference hint:
    # score[class] = intercept + sum_j tf_j * idf_j * coef[class,j]
    idf_vec = np.asarray(idf)
    weighted_coef = coef * idf_vec  # broadcast along features

    per_class_weights: dict[str, dict[str, float]] = {}
    for row_idx, class_id in enumerate(classes):
        name = label_names.get(class_id, str(class_id))
        weights = {}
        for tok, feat_idx in vocab.items():
            w = float(weighted_coef[row_idx, feat_idx])
            if w != 0.0:
                weights[tok] = w
        per_class_weights[name] = weights

    bundle = {
        "format": "neyra_tfidf_logreg_v2",
        "task": blob.get("task"),
        "label_names": {str(k): v for k, v in sorted(label_names.items())},
        "class_order": [label_names[c] for c in classes],
        "intercepts": {
            label_names[c]: float(intercept[i]) for i, c in enumerate(classes)
        },
        "token_weights_by_class": per_class_weights,
        "n_features": len(vocab),
        # v2: idf for every vocabulary term, so the client can apply the same
        # L2 normalisation as TfidfVectorizer (weights above already include idf).
        "idf_by_token": {tok: float(idf_vec[j]) for tok, j in vocab.items()},
        "vectorizer": {
            "token_pattern": tfidf_step.token_pattern,
            "norm": tfidf_step.norm,
            "sublinear_tf": bool(tfidf_step.sublinear_tf),
        },
        "ngram_range": list(tfidf_step.ngram_range),
        "preprocess": {
            "lowercase": True,
            "strip_urls": True,
            "strip_mentions": True,
            "sublinear_tf": bool(tfidf_step.sublinear_tf),
        },
        "inference_note": (
            "Apply the same clean_text rules as experiments/disaster_classifier/src/preprocess.py, "
            "tokenize with vectorizer.token_pattern, build word n-grams, keep terms in idf_by_token, "
            "x_j = sublinear TF * idf_j, divide by the L2 norm of x, score = intercept + "
            "sum_j (sublinear TF_j * token_weight_j) / norm, argmax. "
            "This is humanitarian Help/disaster-type classification only — not misuse."
        ),
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(bundle, ensure_ascii=False), encoding="utf-8")
    meta = {
        "source_joblib": str(joblib_path),
        "output": str(out_path),
        "bytes": out_path.stat().st_size,
        "megabytes": round(out_path.stat().st_size / (1024 * 1024), 3),
        "n_tokens_with_weights": sum(len(w) for w in per_class_weights.values()),
    }
    return meta


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--joblib", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.joblib.is_file():
        print(f"ERROR: missing {args.joblib}", file=sys.stderr)
        return 2
    meta = export_bundle(args.joblib, args.output)
    print(json.dumps(meta, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
