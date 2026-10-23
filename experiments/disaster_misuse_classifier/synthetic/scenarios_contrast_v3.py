"""Targeted contrast families addressing civic v2 error patterns (v3 expansion)."""

from __future__ import annotations

from synthetic.scenarios import PLACES_EN, PLACES_HI, PLACES_HINGLISH, TemplateFamily, _pick

GROUP = "contrast_v3"


def _bundle(
    label: str,
    prefix: str,
    en: list[str],
    hi: list[str],
    hing: list[str],
    n: int = 6,
) -> list[TemplateFamily]:
    out: list[TemplateFamily] = []

    def _en(rng, tpl):
        return tpl.format(place=_pick(rng, PLACES_EN))

    def _hi(rng, tpl):
        return tpl.format(place=_pick(rng, PLACES_HI))

    def _hing(rng, tpl):
        return tpl.format(place=_pick(rng, PLACES_HINGLISH))

    for i, tpl in enumerate(en):
        for v in range(n):
            out.append(
                TemplateFamily(
                    family_id=f"{prefix}_en_{i}_{v}",
                    label=label,
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("contrast",),
                    scenario_group=GROUP,
                    render=lambda rng, t=tpl: _en(rng, t),
                )
            )
    for i, tpl in enumerate(hi):
        for v in range(n):
            out.append(
                TemplateFamily(
                    family_id=f"{prefix}_hi_{i}_{v}",
                    label=label,
                    language_bucket="hindi_hinglish",
                    language_subtype="devanagari_hindi",
                    disaster_tags=("contrast",),
                    scenario_group=GROUP,
                    render=lambda rng, t=tpl: _hi(rng, t),
                )
            )
    for i, tpl in enumerate(hing):
        for v in range(n):
            out.append(
                TemplateFamily(
                    family_id=f"{prefix}_hing_{i}_{v}",
                    label=label,
                    language_bucket="hindi_hinglish",
                    language_subtype="latin_hinglish",
                    disaster_tags=("contrast",),
                    scenario_group=GROUP,
                    render=lambda rng, t=tpl: _hing(rng, t),
                )
            )
    return out


def all_contrast_v3_families() -> list[TemplateFamily]:
    benign_en = [
        "Help needed in school at {place}—parents pick up by 3pm, no payment involved.",
        "I need help carrying water to {place}; not asking for money, just hands.",
        "We need food and medicine at {place} tent row—volunteers coordinating.",
        "Can someone help my family reach {place} clinic? No threats, just transport.",
        "Please send money to help with rescue boats if you want—totally optional donation jar.",
        "Traffic police closed the bridge; use alternate route to {place} per desk notice.",
        "School is closed tomorrow due to heavy rain near {place}—check board website.",
        "Do not pay strangers claiming to be relief workers at {place}.",
    ]
    benign_hi = [
        "स्कूल में मदद चाहिए {place} पर—कोई फीस नहीं, बस समन्वय।",
        "परिवार को {place} क्लिनिक तक लाने में मदद चाहिए—धमकी नहीं।",
        "खाना और दवा चाहिए {place} पर—स्वयंसेवक समूह।",
    ]
    benign_hing = [
        "help needed in school {place} pe—fee nahi, bas pickup timing.",
        "family ko {place} clinic tak help chahiye—no threat.",
        "food medicine chahiye {place} tent—volunteer coordination.",
    ]
    quote_en = [
        "A witness reported that someone threatened the shopkeeper at {place}—I am repeating their account.",
        "She quoted him saying 'pay or else' near {place}; reporting for the log, not threatening you.",
    ]
    quote_hi = [
        "गवाह ने बताया किसी ने {place} पर दुकानदार को धमकाया—मैं रिपोर्ट कर रहा हूँ।",
    ]
    quote_hing = [
        "witness bola kisi ne shopkeeper ko threaten kiya {place} pe—main report kar raha.",
    ]
    families: list[TemplateFamily] = []
    families.extend(_bundle("benign_emergency_information", "cv3_benign", benign_en, benign_hi, benign_hing, 7))
    families.extend(_bundle("quoted_or_historical_threat", "cv3_quote", quote_en, quote_hi, quote_hing, 8))
    families.extend(
        _bundle(
            "report_or_warning_about_misconduct",
            "cv3_report",
            ["Neighbour warns a scam at {place} relief desk—do not pay unknown 'fees'."],
            ["पड़ोसी ने {place} पर धोखाधड़ी की चेतावनी दी—अज्ञात फीस न दें।"],
            ["scam warning {place} desk pe—unknown fee mat do."],
            8,
        )
    )
    families.extend(
        _bundle(
            "coercion_or_extortion",
            "cv3_coerce",
            ["Send money now or we will hurt your family at {place}—this is a demand, not a request."],
            ["अभी पैसे भेजो वरना परिवार को नुकसान होगा {place} पर—धमकी है।"],
            ["paisa bhejo abhi warna family ko hurt karenge {place} pe—demand hai."],
            8,
        )
    )
    families.extend(
        _bundle(
            "possible_relief_worker_impersonation_or_fraud",
            "cv3_fraud",
            ["I am relief logistics at {place}: wire verification fee to unlock your crate slot."],
            ["मैं राहत टीम {place} से—स्लॉट के लिए 'सत्यापन शुल्क' भेजें।"],
            ["main relief team {place} se—verification fee bhejo slot ke liye."],
            8,
        )
    )
    return families
