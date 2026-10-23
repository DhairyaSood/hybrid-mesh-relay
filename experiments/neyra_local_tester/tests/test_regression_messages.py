from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

TESTER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTER_ROOT))

LABELS = (
    "non_disaster",
    "flood",
    "earthquake",
    "fire",
    "landslide_or_mudslide",
    "cyclone_or_hurricane",
    "severe_storm_or_high_winds",
    "heavy_rain_or_waterlogging",
    "tsunami",
    "drought_or_water_shortage",
    "extreme_heat_or_heatwave",
    "extreme_cold_or_snowstorm",
    "building_collapse_or_structural_failure",
    "industrial_or_chemical_accident",
    "transport_accident",
    "other_disaster_or_emergency",
    "unknown_or_insufficient_information",
)


@pytest.fixture
def mock_bundle():
    from neyra_tester.inference import MISUSE_LABELS, ModelBundle

    def _pipe(labels, pred_idx=0):
        clf = MagicMock()
        clf.classes_ = list(labels)
        pipe = MagicMock()
        pipe.named_steps = {"clf": clf}
        pipe.predict.return_value = [labels[pred_idx]]
        pipe.predict_proba.return_value = [[1.0 / len(labels)] * len(labels)]
        return pipe

    mh = {"pipeline": _pipe(LABELS, 3), "labels": list(LABELS)}
    mu = {"pipeline": _pipe(MISUSE_LABELS, 1)}
    return ModelBundle(mh, mu, {}, LABELS)


@patch("neyra_tester.inference._import_preprocessors")
def test_schema(mock_import, mock_bundle):
    mock_import.return_value = (lambda t, **k: t, lambda t: t)
    from neyra_tester.inference import predict_all

    out = predict_all(mock_bundle, "Fire in the building.")
    assert "disaster_type" in out
    assert "misuse_advisory" in out
    assert len(out["disaster_type"]["supported_labels"]) == 17


def test_empty_input():
    from neyra_tester.inference import predict_all

    assert "error" in predict_all(MagicMock(), "   ")
