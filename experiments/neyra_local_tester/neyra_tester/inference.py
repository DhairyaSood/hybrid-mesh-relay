from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import yaml

from neyra_tester.paths import REPO_ROOT, TESTER_ROOT

MISUSE_LABELS = [
    "ambiguous_needs_review",
    "benign_emergency_information",
    "coercion_or_extortion",
    "direct_threat_or_intimidation",
    "possible_relief_worker_impersonation_or_fraud",
    "quoted_or_historical_threat",
    "report_or_warning_about_misconduct",
]


@dataclass
class ModelBundle:
    disaster_type: Any
    misuse: Any
    paths: dict[str, Path]
    disaster_labels: tuple[str, ...]


def _resolve(path_str: str) -> Path:
    p = Path(path_str)
    return p if p.is_absolute() else REPO_ROOT / p


def load_config() -> dict:
    return yaml.safe_load((TESTER_ROOT / "config.yaml").read_text(encoding="utf-8"))


def _import_preprocessors() -> tuple:
    dc_src = REPO_ROOT / "experiments/disaster_classifier/src"
    mu_root = REPO_ROOT / "experiments/disaster_misuse_classifier"
    if str(dc_src) not in sys.path:
        sys.path.insert(0, str(dc_src))
    if str(mu_root) not in sys.path:
        sys.path.insert(0, str(mu_root))
    from preprocess import clean_text as disaster_clean

    from synthetic.preprocess import clean_text as misuse_clean

    return disaster_clean, misuse_clean


def load_models(cfg: dict | None = None) -> ModelBundle:
    cfg = cfg or load_config()
    paths = {
        "disaster_type": _resolve(cfg["disaster_type_model"]),
        "misuse": _resolve(cfg["misuse_model"]),
    }
    missing = [k for k, p in paths.items() if not p.is_file()]
    if missing:
        raise FileNotFoundError(
            "Missing model file(s): "
            + ", ".join(f"{k}={paths[k]}" for k in missing)
            + ". Run experiments/disaster_classifier/scripts/train_multi_hazard.py"
        )
    disaster_art = joblib.load(paths["disaster_type"])
    labels = tuple(disaster_art.get("labels") or ())
    return ModelBundle(
        disaster_type=disaster_art,
        misuse=joblib.load(paths["misuse"]),
        paths=paths,
        disaster_labels=labels,
    )


def predict_disaster_type(bundle: ModelBundle, text: str, disaster_clean) -> dict:
    cleaned = disaster_clean(text, lowercase=True, strip_urls=True, strip_mentions=True)
    if not cleaned.strip():
        return {"error": "empty_input", "prediction": None, "model_scores": {}}
    pipe = bundle.disaster_type["pipeline"]
    pred = str(pipe.predict([cleaned])[0])
    scores: dict[str, float] = {}
    if hasattr(pipe, "predict_proba"):
        classes = [str(c) for c in pipe.named_steps["clf"].classes_]
        proba = pipe.predict_proba([cleaned])[0]
        for c, p in zip(classes, proba):
            scores[c] = float(p)
    for lab in bundle.disaster_labels:
        scores.setdefault(lab, 0.0)
    scores = dict(sorted(scores.items(), key=lambda x: -x[1]))
    return {
        "model_version": "MH-v1 (17-class disaster type)",
        "supported_labels": list(bundle.disaster_labels),
        "prediction": pred,
        "prediction_note": (
            "Text-based hazard category from synthetic-trained model — "
            "not confirmation that an event is occurring."
        ),
        "model_scores": scores,
        "score_note": "Raw logistic scores — not calibrated probabilities.",
    }


def predict_misuse(bundle: ModelBundle, text: str, misuse_clean) -> dict:
    from neyra_tester.misuse_refinement import refine_misuse_label

    cleaned = misuse_clean(text)
    if not cleaned.strip():
        return {"error": "empty_input", "prediction": None, "model_scores": {}, "model_version": "MU-v1"}
    pipe = bundle.misuse["pipeline"]
    raw_pred = str(pipe.predict([cleaned])[0])
    scores: dict[str, float] = {}
    classes = [str(c) for c in pipe.named_steps["clf"].classes_]
    proba = pipe.predict_proba([cleaned])[0]
    for c, p in zip(classes, proba):
        scores[c] = float(p)
    for lab in MISUSE_LABELS:
        scores.setdefault(lab, 0.0)
    refined = refine_misuse_label(text, raw_pred)
    return {
        "model_version": "MU-v1 (canonical) + civic/traffic refinement",
        "prediction": refined.prediction,
        "raw_model_prediction": refined.raw_prediction,
        "refinement_rule": refined.applied_rule,
        "refinement_note": refined.note,
        "model_scores": dict(sorted(scores.items(), key=lambda x: -x[1])),
        "score_note": "Scores from MU-v1 joblib; label may be adjusted by local civic/traffic rules.",
    }


def predict_all(bundle: ModelBundle, text: str) -> dict:
    if text is None or not str(text).strip():
        return {"error": "Please enter a non-empty message."}
    try:
        disaster_clean, misuse_clean = _import_preprocessors()
    except ImportError as e:
        return {"error": f"Preprocessor import failed: {e}"}
    try:
        return {
            "disaster_type": predict_disaster_type(bundle, text, disaster_clean),
            "misuse_advisory": predict_misuse(bundle, text, misuse_clean),
        }
    except Exception as e:
        return {"error": f"Inference failed: {type(e).__name__}: {e}"}


def compare_expected(misuse_pred: str | None, expected_misuse: str | None) -> dict | None:
    if not expected_misuse or expected_misuse == "(none)":
        return None
    return {
        "expected": expected_misuse,
        "predicted": misuse_pred,
        "match": expected_misuse == misuse_pred,
    }
