"""Advisory when DC-T2 label space (3 classes) disagrees with message vocabulary."""

from __future__ import annotations

import re
from dataclasses import dataclass

SUPPORTED_DISASTER_TYPES = ("non_disaster", "flood", "earthquake")

OOS_VOCAB = re.compile(
    r"\b(fire|cyclone|landslide|tsunami|gas leak|chemical|heatwave|building collapse|"
    r"train derail|आग|चक्रवात|भूस्खलन)\b",
    re.I,
)


@dataclass(frozen=True)
class DisasterTypeAdvisory:
    level: str
    codes: tuple[str, ...]
    user_message: str


def assess_disaster_type_advisory(text: str, prediction: str, scores: dict) -> DisasterTypeAdvisory:
    if OOS_VOCAB.search(text or ""):
        return DisasterTypeAdvisory(
            "strong",
            ("unsupported_event_vocabulary",),
            "Message mentions hazard types outside DC-T2 training labels "
            f"(non_disaster, flood, earthquake). Top label `{prediction}` is text-only, not verified.",
        )
    top = max(scores.values()) if scores else 0.0
    if top < 0.45:
        return DisasterTypeAdvisory(
            "caution",
            ("low_top_class_score",),
            "Low confidence among three supported classes — verify manually.",
        )
    return DisasterTypeAdvisory("none", (), "")
