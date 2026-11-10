from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from neyra_tester.misuse_refinement import refine_misuse_label


def test_traffic_not_direct_threat():
    r = refine_misuse_label(
        "Traffic police have closed the bridge; use the alternate route via sector 9.",
        "direct_threat_or_intimidation",
    )
    assert r.prediction == "benign_emergency_information"
    assert r.applied_rule


def test_school_help_benign():
    r = refine_misuse_label("Help needed in school after the drill.", "direct_threat_or_intimidation")
    assert r.prediction == "benign_emergency_information"


def test_coercion_payment():
    r = refine_misuse_label("Send money now or we will hurt your family.", "possible_relief_worker_impersonation_or_fraud")
    assert r.prediction == "coercion_or_extortion"


def test_quoted_threat_report():
    r = refine_misuse_label(
        "A witness reported that someone threatened the shopkeeper.",
        "coercion_or_extortion",
    )
    assert r.prediction == "quoted_or_historical_threat"


def test_real_threat_kept():
    r = refine_misuse_label("I will kill you if you come near the supplies.", "benign_emergency_information")
    assert r.prediction == "direct_threat_or_intimidation"
