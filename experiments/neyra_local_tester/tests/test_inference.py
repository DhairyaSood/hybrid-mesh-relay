from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

TESTER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTER_ROOT))

from neyra_tester.inference import (  # noqa: E402
    MISUSE_LABELS,
    compare_expected,
    predict_all,
    predict_disaster_type,
    predict_misuse,
)

MH_LABELS = ["non_disaster", "flood", "earthquake", "fire"]


def _fake_pipeline(classes, pred_idx=0, proba=None):
    clf = MagicMock()
    clf.classes_ = classes
    pipe = MagicMock()
    pipe.named_steps = {"clf": clf}
    pipe.predict.return_value = [classes[pred_idx]]
    if proba is None:
        proba = [1.0 / len(classes)] * len(classes)
    pipe.predict_proba.return_value = [proba]
    return pipe


@pytest.fixture
def fake_bundle():
    from neyra_tester.inference import ModelBundle

    mh = {"pipeline": _fake_pipeline(MH_LABELS, pred_idx=3, proba=[0.1, 0.1, 0.1, 0.7]), "labels": MH_LABELS}
    mu = {"pipeline": _fake_pipeline(MISUSE_LABELS, pred_idx=1, proba=[0.05] * len(MISUSE_LABELS))}
    return ModelBundle(mh, mu, {}, tuple(MH_LABELS))


def test_empty_input_returns_error(fake_bundle):
    r = predict_disaster_type(fake_bundle, "   ", lambda t, **k: t)
    assert r["error"] == "empty_input"
    assert predict_all(fake_bundle, "")["error"]


def test_compare_expected():
    assert compare_expected("a", "(none)") is None
    assert compare_expected("a", "a")["match"] is True


@patch("neyra_tester.inference._import_preprocessors")
def test_predict_all_structure(mock_import, fake_bundle):
    mock_import.return_value = (lambda t, **k: t.lower(), lambda t: t.lower())
    out = predict_all(fake_bundle, "fire in building")
    assert out["disaster_type"]["prediction"] == "fire"
    assert "misuse_advisory" in out


@patch("neyra_tester.inference._import_preprocessors")
def test_misuse_scores_all_labels(mock_import, fake_bundle):
    mock_import.return_value = (lambda t, **k: t, lambda t: t)
    mu = predict_misuse(fake_bundle, "test", lambda t: t)
    for lab in MISUSE_LABELS:
        assert lab in mu["model_scores"]


def test_load_models_missing_raises():
    from neyra_tester.inference import load_models

    cfg = {
        "disaster_type_model": "nonexistent/mh.joblib",
        "misuse_model": "nonexistent/mu.joblib",
    }
    with pytest.raises(FileNotFoundError):
        load_models(cfg)
