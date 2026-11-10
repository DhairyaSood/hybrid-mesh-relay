from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

TESTER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTER_ROOT))


@patch("neyra_tester.inference._detect_keywords")
@patch("neyra_tester.inference._import_preprocessors")
def test_schema(mock_import, mock_kw):
    mock_import.return_value = (lambda t, **k: t, lambda t: t)
    mock_kw.return_value = {"primary_hint": "non_disaster", "matches": []}
    from neyra_tester.inference import MISUSE_LABELS, ModelBundle, predict_all

    def _pipe(labels, i=0):
        clf = MagicMock()
        clf.classes_ = list(labels)
        pipe = MagicMock()
        pipe.named_steps = {"clf": clf}
        pipe.predict.return_value = [labels[i]]
        pipe.predict_proba.return_value = [[1.0 / len(labels)] * len(labels)]
        return pipe

    b = ModelBundle(
        {"pipeline": _pipe([0, 1, 2], 1), "label_names": {1: "flood"}},
        {"pipeline": _pipe(["Help"], 0), "label_names": {0: "Help"}},
        {"pipeline": _pipe(MISUSE_LABELS, 1)},
        {},
    )
    out = predict_all(b, "hello")
    assert "disaster_hybrid_summary" in out
