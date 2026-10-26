"""Combine DC-T2, keyword layer, and optional MH-v1 experimental output."""

from __future__ import annotations

from typing import Any

DC_T2_LABELS = {"non_disaster", "flood", "earthquake"}


def build_hybrid_disaster_summary(
    dc_t2: dict[str, Any],
    keywords: dict[str, Any],
    mh_experimental: dict[str, Any] | None = None,
) -> dict[str, Any]:
    kw_hint = keywords.get("primary_hint")
    dc_pred = dc_t2.get("prediction")
    messages: list[str] = []
    uncertainty = False

    if kw_hint and kw_hint not in ("non_disaster", "unknown_or_insufficient_information"):
        mapped = kw_hint
        if mapped not in DC_T2_LABELS and dc_pred not in (mapped, "earthquake", "flood"):
            uncertainty = True
            messages.append(
                f"Keyword layer suggests `{mapped}`; DC-T2 only supports {sorted(DC_T2_LABELS)}. "
                "Treat DC-T2 top label as nearest supported class, not ground truth."
            )

    adv = dc_t2.get("advisory") or {}
    if adv.get("level") in ("strong", "caution") and adv.get("message"):
        messages.append(adv["message"])
        uncertainty = True

    if keywords.get("suppressed"):
        messages.append(f"Keyword context flags: {', '.join(keywords['suppressed'])}.")

    mh_note = None
    if mh_experimental and not mh_experimental.get("error"):
        mh_pred = mh_experimental.get("prediction")
        mh_note = (
            f"Experimental MH-v1 (synthetic) predicts `{mh_pred}` — separate from canonical DC-T2."
        )

    return {
        "components": {
            "canonical_classifier": "DC-T2 (3-class, corpus-trained)",
            "keyword_baseline": "hazard_keywords (heuristic)",
            "experimental_multi_hazard": "MH-v1 (optional, synthetic)",
        },
        "uncertain_or_oos": uncertainty,
        "messages": messages or ["Compare layers; none verify real-world events."],
        "mh_experimental_note": mh_note,
    }
