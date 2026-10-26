from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import joblib
import yaml

from neyra_tester.disaster_hybrid import build_hybrid_disaster_summary
from neyra_tester.disaster_type_advisory import SUPPORTED_DISASTER_TYPES, assess_disaster_type_advisory
from neyra_tester.paths import REPO_ROOT, TESTER_ROOT

DISASTER_TYPE_NAMES = {0: "non_disaster", 1: "flood", 2: "earthquake", "0": "non_disaster", "1": "flood", "2": "earthquake"}

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
    disaster_task2: Any
    disaster_task3: Any
    misuse: Any
    paths: dict[str, Path]
    misuse_v2: Any | None = None
    misuse_v3: Any | None = None
    multi_hazard: Any | None = None
    disaster_labels: tuple[str, ...] = field(default_factory=tuple)


def _resolve(path_str: str) -> Path:
    p = Path(path_str)
    return p if p.is_absolute() else REPO_ROOT / p


def load_config() -> dict:
    return yaml.safe_load((TESTER_ROOT / "config.yaml").read_text(encoding="utf-8"))


def _import_preprocessors() -> tuple:
    dc_src = REPO_ROOT / "experiments/disaster_classifier/src"
    mu_root = REPO_ROOT / "experiments/disaster_misuse_classifier"
    dc_root = REPO_ROOT / "experiments/disaster_classifier"
    for p in (dc_src, mu_root, dc_root):
        if str(p) not in sys.path:
            sys.path.insert(0, str(p))
    from preprocess import clean_text as disaster_clean
    from synthetic.preprocess import clean_text as misuse_clean
    return disaster_clean, misuse_clean


def _detect_keywords(text: str) -> dict:
    dc_root = REPO_ROOT / "experiments/disaster_classifier"
    if str(dc_root) not in sys.path:
        sys.path.insert(0, str(dc_root))
    from hazard_keywords.detector import detect_hazard_keywords
    return detect_hazard_keywords(text)


def load_models(cfg: dict | None = None) -> ModelBundle:
    cfg = cfg or load_config()
    paths = {
        "task2": _resolve(cfg["disaster_task2_model"]),
        "task3": _resolve(cfg["disaster_task3_model"]),
        "misuse": _resolve(cfg["misuse_model"]),
    }
    missing = [k for k, p in paths.items() if not p.is_file()]
    if missing:
        raise FileNotFoundError("Missing model(s): " + ", ".join(f"{k}={paths[k]}" for k in missing))
    bundle = ModelBundle(
        disaster_task2=joblib.load(paths["task2"]),
        disaster_task3=joblib.load(paths["task3"]),
        misuse=joblib.load(paths["misuse"]),
        paths=paths,
    )
    for key, art_key in (
        ("misuse_v2", "misuse_model_v2_experimental"),
        ("misuse_v3", "misuse_model_v3_experimental"),
        ("multi_hazard", "multi_hazard_model_experimental"),
    ):
        p = cfg.get(art_key)
        if p:
            path = _resolve(p)
            if path.is_file():
                paths[art_key] = path
                setattr(bundle, key, joblib.load(path))
    if bundle.multi_hazard:
        bundle.disaster_labels = tuple(bundle.multi_hazard.get("labels") or ())
    return bundle


def _disaster_label(artifact: dict, raw: Any) -> str:
    names = artifact.get("label_names") or {}
    if raw in names:
        return str(names[raw])
    return DISASTER_TYPE_NAMES.get(raw, str(raw))


def predict_disaster_type_dc_t2(bundle: ModelBundle, text: str, disaster_clean) -> dict:
    cleaned = disaster_clean(text, lowercase=True, strip_urls=True, strip_mentions=True)
    if not cleaned.strip():
        return {"error": "empty_input", "prediction": None, "model_scores": {}}
    pipe = bundle.disaster_task2["pipeline"]
    pred = pipe.predict([cleaned])[0]
    pred_label = _disaster_label(bundle.disaster_task2, pred)
    scores = {}
    if hasattr(pipe, "predict_proba"):
        for c, p in zip(pipe.named_steps["clf"].classes_, pipe.predict_proba([cleaned])[0]):
            scores[_disaster_label(bundle.disaster_task2, c)] = float(p)
    advisory = assess_disaster_type_advisory(text, pred_label, scores)
    return {
        "model_version": "DC-T2 (canonical)",
        "supported_labels": list(SUPPORTED_DISASTER_TYPES),
        "prediction": pred_label,
        "model_scores": scores,
        "advisory": {
            "level": advisory.level,
            "codes": list(advisory.codes),
            "message": advisory.user_message,
        },
        "score_note": "Corpus-trained 3-class only; not full disaster taxonomy.",
    }


def predict_humanitarian_help(bundle: ModelBundle, text: str, disaster_clean) -> dict:
    cleaned = disaster_clean(text, lowercase=True, strip_urls=True, strip_mentions=True)
    if not cleaned.strip():
        return {"error": "empty_input", "prediction": None, "model_scores": {}}
    pipe = bundle.disaster_task3["pipeline"]
    pred = _disaster_label(bundle.disaster_task3, pipe.predict([cleaned])[0])
    scores = {}
    if hasattr(pipe, "predict_proba"):
        for c, p in zip(pipe.named_steps["clf"].classes_, pipe.predict_proba([cleaned])[0]):
            scores[_disaster_label(bundle.disaster_task3, c)] = float(p)
    return {
        "model_version": "DC-T3 (canonical)",
        "prediction": pred,
        "model_scores": scores,
        "topic_note": "Help topic only — not severity, disaster type, or misuse.",
    }


def predict_multi_hazard_experimental(bundle: ModelBundle, text: str, disaster_clean) -> dict:
    if bundle.multi_hazard is None:
        return {"error": "mh_model_missing", "model_version": "MH-v1 (experimental)"}
    cleaned = disaster_clean(text, lowercase=True, strip_urls=True, strip_mentions=True)
    pipe = bundle.multi_hazard["pipeline"]
    pred = str(pipe.predict([cleaned])[0])
    scores = {}
    if hasattr(pipe, "predict_proba"):
        for c, p in zip(pipe.named_steps["clf"].classes_, pipe.predict_proba([cleaned])[0]):
            scores[str(c)] = float(p)
    return {
        "model_version": "MH-v1 (experimental, synthetic)",
        "prediction": pred,
        "supported_labels": list(bundle.disaster_labels),
        "model_scores": dict(sorted(scores.items(), key=lambda x: -x[1])),
    }


def _misuse_raw(bundle: ModelBundle, artifact: dict, text: str, misuse_clean, version: str) -> dict:
    cleaned = misuse_clean(text)
    if not cleaned.strip():
        return {"error": "empty_input", "model_version": version}
    pipe = artifact["pipeline"]
    pred = str(pipe.predict([cleaned])[0])
    scores = {}
    for c, p in zip(pipe.named_steps["clf"].classes_, pipe.predict_proba([cleaned])[0]):
        scores[str(c)] = float(p)
    for lab in MISUSE_LABELS:
        scores.setdefault(lab, 0.0)
    return {
        "model_version": version,
        "prediction": pred,
        "model_scores": dict(sorted(scores.items(), key=lambda x: -x[1])),
    }


def predict_misuse_mu_v1(bundle: ModelBundle, text: str, misuse_clean) -> dict:
    from neyra_tester.misuse_refinement import refine_misuse_label

    raw = _misuse_raw(bundle, bundle.misuse, text, misuse_clean, "MU-v1 (canonical)")
    if raw.get("error"):
        return raw
    refined = refine_misuse_label(text, raw["prediction"])
    raw["prediction"] = refined.prediction
    raw["raw_model_prediction"] = refined.raw_prediction
    raw["refinement_rule"] = refined.applied_rule
    raw["refinement_note"] = refined.note
    raw["score_note"] = "MU-v1 scores + tester civic/traffic refinement on displayed label."
    return raw


def predict_all(bundle: ModelBundle, text: str) -> dict:
    if not str(text or "").strip():
        return {"error": "Please enter a non-empty message."}
    try:
        disaster_clean, misuse_clean = _import_preprocessors()
    except ImportError as e:
        return {"error": f"Preprocessor import failed: {e}"}
    try:
        dc_t2 = predict_disaster_type_dc_t2(bundle, text, disaster_clean)
        keywords = _detect_keywords(text)
        mh = predict_multi_hazard_experimental(bundle, text, disaster_clean)
        hybrid = build_hybrid_disaster_summary(dc_t2, keywords, mh if not mh.get("error") else None)
        out = {
            "disaster_type_dc_t2": dc_t2,
            "disaster_hazard_keywords": keywords,
            "disaster_hybrid_summary": hybrid,
            "disaster_type_mh_v1_experimental": mh,
            "humanitarian_topic": predict_humanitarian_help(bundle, text, disaster_clean),
            "misuse_advisory": predict_misuse_mu_v1(bundle, text, misuse_clean),
        }
        if bundle.misuse_v2:
            out["misuse_advisory_v2_experimental"] = _misuse_raw(
                bundle, bundle.misuse_v2, text, misuse_clean, "MU-v2 (experimental)"
            )
        if bundle.misuse_v3:
            out["misuse_advisory_v3_experimental"] = _misuse_raw(
                bundle, bundle.misuse_v3, text, misuse_clean, "MU-v3 (experimental)"
            )
        return out
    except Exception as e:
        return {"error": f"Inference failed: {type(e).__name__}: {e}"}


def compare_expected(misuse_pred: str | None, expected_misuse: str | None) -> dict | None:
    if not expected_misuse or expected_misuse == "(none)":
        return None
    return {"expected": expected_misuse, "predicted": misuse_pred, "match": expected_misuse == misuse_pred}
