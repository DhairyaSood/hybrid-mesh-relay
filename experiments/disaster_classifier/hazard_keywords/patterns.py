"""Phrase-level hazard hints (heuristic, not event verification)."""

from __future__ import annotations

import re

HAZARD_RULES: list[tuple[str, re.Pattern[str], int]] = [
    ("fire", re.compile(r"\b(fire|flames|smoke in building|building on fire|आग|aag lag)\b", re.I), 3),
    ("flood", re.compile(r"\b(flood|flooded|floodwater|बाढ़|baadh|paani bhar)\b", re.I), 3),
    ("earthquake", re.compile(r"\b(earthquake|tremor|भूकंप|bhukamp)\b", re.I), 3),
    ("landslide_or_mudslide", re.compile(r"\b(landslide|mudslide|भूस्खलन)\b", re.I), 3),
    ("cyclone_or_severe_storm", re.compile(r"\b(cyclone|hurricane|typhoon|चक्रवात|severe storm|thunderstorm)\b", re.I), 3),
    ("heavy_rain_or_waterlogging", re.compile(r"\b(waterlogging|heavy rain|जलभराव)\b", re.I), 2),
    ("heatwave_or_extreme_heat", re.compile(r"\b(heatwave|extreme heat|लू)\b", re.I), 2),
    ("building_collapse_or_structural_failure", re.compile(r"\b(building collapse|bridge collapse|collapsed|इमारत गिर|pul gir)\b", re.I), 3),
    ("transport_accident", re.compile(r"\b(train derail|bus crash|road accident|accident on highway)\b", re.I), 2),
    ("chemical_or_gas_leak", re.compile(r"\b(gas leak|chemical leak|toxic fumes|गैस रिसाव)\b", re.I), 3),
]

DRILL_SUPPRESS = re.compile(r"\b(fire drill|collapse drill|अग्निशमन अभ्यास|drill was|mock drill)\b", re.I)
NEGATION = re.compile(r"\b(no flooding|not flooded|denied the cyclone|false rumou?r|hoax|not true)\b", re.I)
ROUTINE_TRAFFIC = re.compile(
    r"\b(traffic police|alternate route|diversion|detour|road closed|lane closed)\b", re.I
)
FIRE_STATION = re.compile(r"\b(fire station|fire truck parked)\b", re.I)
QUOTE_FRAME = re.compile(r"\b(reported that|witness|according to|last year)\b", re.I)
