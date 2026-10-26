"""Context-aware keyword hazard detector (baseline layer)."""

from __future__ import annotations

import re
import unicodedata

from hazard_keywords.patterns import (
    DRILL_SUPPRESS,
    FIRE_STATION,
    HAZARD_RULES,
    NEGATION,
    QUOTE_FRAME,
    ROUTINE_TRAFFIC,
)

SUPPORTED_HAZARD_HINTS = [
    "non_disaster",
    "flood",
    "earthquake",
    "fire",
    "landslide_or_mudslide",
    "cyclone_or_severe_storm",
    "heavy_rain_or_waterlogging",
    "heatwave_or_extreme_heat",
    "building_collapse_or_structural_failure",
    "transport_accident",
    "chemical_or_gas_leak",
    "unknown_or_insufficient_information",
]


def _norm(text: str) -> str:
    t = unicodedata.normalize("NFKC", text or "")
    t = re.sub(r"\s+", " ", t.lower()).strip()
    return t


def detect_hazard_keywords(text: str) -> dict:
    norm = _norm(text)
    if not norm:
        return {
            "primary_hint": "unknown_or_insufficient_information",
            "matches": [],
            "suppressed": [],
            "note": "Empty input.",
        }
    suppressed: list[str] = []
    if NEGATION.search(norm):
        return {
            "primary_hint": "unknown_or_insufficient_information",
            "matches": [],
            "suppressed": ["negated_or_denied_hazard"],
            "note": "Negated or denied hazard wording — not treated as an active event.",
        }
    if DRILL_SUPPRESS.search(norm):
        return {
            "primary_hint": "non_disaster",
            "matches": [],
            "suppressed": ["drill_or_exercise"],
            "note": "Drill/exercise wording — not treated as active fire or collapse.",
        }
    if FIRE_STATION.search(norm) and "building on fire" not in norm and "आग लग" not in norm:
        suppressed.append("fire_station_routine")
    if ROUTINE_TRAFFIC.search(norm) and not re.search(r"\b(fire|flood|collapse|gas leak)\b", norm, re.I):
        return {
            "primary_hint": "non_disaster",
            "matches": [{"label": "non_disaster", "evidence": "traffic/routing advisory", "heuristic_score": 1}],
            "suppressed": suppressed + ["routine_traffic"],
            "note": "Routing/traffic wording without active hazard lexicon — not a verified event.",
        }
    matches: list[dict] = []
    for label, pat, score in HAZARD_RULES:
        m = pat.search(norm)
        if not m:
            continue
        if label == "fire" and ("drill" in suppressed or "fire_station_routine" in suppressed):
            continue
        if "negated_or_denied_hazard" in suppressed and label in ("flood", "cyclone_or_severe_storm"):
            continue
        matches.append({"label": label, "evidence": m.group(0), "heuristic_score": score})

    if not matches:
        if "help" in norm and "school" in norm:
            return {
                "primary_hint": "non_disaster",
                "matches": [],
                "suppressed": suppressed,
                "note": "Coordination/help wording without hazard keywords.",
            }
        return {
            "primary_hint": "unknown_or_insufficient_information",
            "matches": [],
            "suppressed": suppressed,
            "note": "No strong hazard phrase match.",
        }

    matches.sort(key=lambda x: -x["heuristic_score"])
    primary = matches[0]["label"]
    if len(matches) > 1:
        primary = matches[0]["label"]  # top score; multi-hazard listed in matches
    if QUOTE_FRAME.search(norm):
        suppressed.append("quoted_or_historical_context")
    return {
        "primary_hint": primary,
        "matches": matches,
        "suppressed": suppressed,
        "note": "Heuristic phrase match only — not proof an event is occurring.",
    }
