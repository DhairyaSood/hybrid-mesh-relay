#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import yaml
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report, f1_score
from sklearn.pipeline import Pipeline

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT))

from multi_hazard.labels import MULTI_HAZARD_LABELS
from preprocess import clean_text


def load_jsonl(p: Path) -> list[dict]:
    return [json.loads(ln) for ln in p.read_text(encoding="utf-8").splitlines() if ln.strip()]


def main() -> int:
    cfg = yaml.safe_load((ROOT / "configs/multi_hazard_train.yaml").read_text())
    data = cfg["data"]
    train = load_jsonl(ROOT / data["train_split"])
    val = load_jsonl(ROOT / data["validation_split"])
    test = load_jsonl(ROOT / data["test_split"])

    def texts(rows):
        return [clean_text(r["message_text"], lowercase=True, strip_urls=True, strip_mentions=True) for r in rows]

    X_tr, y_tr = texts(train), [r["label"] for r in train]
    X_va, y_va = texts(val), [r["label"] for r in val]
    X_te, y_te = texts(test), [r["label"] for r in test]

    best = None
    for max_f in cfg["tuning_grid"]["word_max_features"]:
        for C in cfg["tuning_grid"]["C"]:
            pipe = Pipeline(
                [
                    ("tfidf", TfidfVectorizer(ngram_range=(1, 2), max_features=max_f, min_df=2, sublinear_tf=True)),
                    ("clf", LogisticRegression(C=C, class_weight="balanced", max_iter=3000, solver="lbfgs")),
                ]
            )
            pipe.fit(X_tr, y_tr)
            pred = pipe.predict(X_va)
            mf1 = f1_score(y_va, pred, labels=MULTI_HAZARD_LABELS, average="macro", zero_division=0)
            if best is None or mf1 > best["macro_f1"]:
                best = {"macro_f1": mf1, "max_features": max_f, "C": C, "pipeline": pipe}

    assert best
    pipe = best["pipeline"]
    test_pred = pipe.predict(X_te)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    model_dir = ROOT / cfg["models_dir"]
    model_dir.mkdir(parents=True, exist_ok=True)
    model_path = model_dir / f"{cfg['model_basename']}.joblib"
    artifact = {
        "pipeline": pipe,
        "labels": MULTI_HAZARD_LABELS,
        "task": "multi_hazard_disaster_type_v1",
        "trained_at_utc": ts,
        "disclaimer": "Synthetic training data; text-only advisory; not verified real-world events.",
    }
    joblib.dump(artifact, model_path)

    warm = texts(train[:50])
    for t in warm:
        pipe.predict([t])
    start = time.perf_counter()
    for _ in range(20):
        pipe.predict(warm)
    ms = (time.perf_counter() - start) / (20 * len(warm)) * 1000

    metrics = {
        "validation_macro_f1": best["macro_f1"],
        "test_accuracy": float(accuracy_score(y_te, test_pred)),
        "test_macro_f1": float(f1_score(y_te, test_pred, labels=MULTI_HAZARD_LABELS, average="macro", zero_division=0)),
        "test_report": classification_report(y_te, test_pred, zero_division=0, output_dict=True),
        "hyperparams": {"max_features": best["max_features"], "C": best["C"]},
        "model_path": str(model_path),
        "model_size_bytes": model_path.stat().st_size,
        "ms_per_text_approx": ms,
    }
    out_dir = ROOT / "outputs" / f"multi_hazard_train_{ts}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    print(json.dumps({"model": str(model_path), "test_macro_f1": metrics["test_macro_f1"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
