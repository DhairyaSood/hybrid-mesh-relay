"""Resolve template families from dataset config `scenario_sets`."""

from __future__ import annotations

from synthetic.scenarios import TemplateFamily, all_families as all_legacy_families
from synthetic.scenarios_civic import all_civic_families
from synthetic.scenarios_contrast_v3 import all_contrast_v3_families

SET_LEGACY = "legacy_v1"
SET_CIVIC = "civic_v2"
SET_CONTRAST_V3 = "contrast_v3"


def resolve_families(cfg: dict) -> list[TemplateFamily]:
    sets = cfg.get("scenario_sets", [SET_LEGACY])
    out: list[TemplateFamily] = []
    if SET_LEGACY in sets:
        out.extend(all_legacy_families())
    if SET_CIVIC in sets:
        out.extend(all_civic_families())
    if SET_CONTRAST_V3 in sets:
        out.extend(all_contrast_v3_families())
    if not out:
        raise ValueError(f"No families for scenario_sets={sets}")
    return out
