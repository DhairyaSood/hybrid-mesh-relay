from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

TESTER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTER_ROOT))

from neyra_tester.inference import MISUSE_LABELS, predict_all


def _pipe(classes, idx=0):
    clf = MagicMock()
    clf.classes_ = list(classes)
    pipe = MagicMock()
    pipe.named_steps = {"clf": clf}
    pipe.predict.return_value = [classes[idx]]
    n = len(classes)
    pipe.predict_proba.return_value = [[1.0 / n] * n]
    return pipe


@pytest.fixture
def fake_bundle():
    from neyra_tester.inference import ModelBundle

    t2 = {"pipeline": _pipe([0, 1, 2], 1), "label_names": {1: "flood"}}
    t3 = {"pipeline": _pipe(["Food and Medicine", "Other"], 0), "label_names": {0: "Food and Medicine"}}
    mu = {"pipeline": _pipe(MISUSE_LABELS, 1)}
    return ModelBundle(t2, t3, mu, {})


@patch("neyra_tester.inference._import_preprocessors")
@patch("neyra_tester.inference._detect_keywords")
def test_predict_all_schema(mock_kw, mock_import, fake_bundle):
    mock_import.return_value = (lambda t, **k: t, lambda t: t)
    mock_kw.return_value = {"primary_hint": "fire", "matches": [], "note": ""}
    out = predict_all(fake_bundle, "fire")
    assert "disaster_type_dc_t2" in out
    assert "disaster_hazard_keywords" in out
    assert "humanitarian_topic" in out
    assert "misuse_advisory" in out
