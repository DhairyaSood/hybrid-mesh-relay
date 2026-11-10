"""Regression: keyword + DC-T2 advisory behavior (no hardcoded model preds)."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "disaster_classifier"))
sys.path.insert(0, str(ROOT / "neyra_local_tester"))

from hazard_keywords.detector import detect_hazard_keywords

CASES = [
    ("Fire in the building.", "fire"),
    ("The fire drill is tomorrow.", "non_disaster"),
    ("Flood water entered houses.", "flood"),
    ("No flooding has been reported.", "unknown_or_insufficient_information"),
    ("Officials denied the cyclone rumour.", "unknown_or_insufficient_information"),
    ("Traffic police closed the bridge; use alternate route.", "non_disaster"),
    ("Help needed in school.", "non_disaster"),
]


def test_keyword_regression():
    for text, expected in CASES:
        hint = detect_hazard_keywords(text)["primary_hint"]
        assert hint == expected, f"{text!r} got {hint}"


def test_fire_drill_not_fire():
    assert detect_hazard_keywords("The fire drill is tomorrow.")["primary_hint"] == "non_disaster"
