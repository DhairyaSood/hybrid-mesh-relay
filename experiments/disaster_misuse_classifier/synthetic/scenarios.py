"""Scenario template families — label is fixed per family at generation time."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

# Fictional place names only
PLACES_EN = [
    "Riverside camp sector B",
    "Hillview school shelter",
    "North embankment point",
    "Municipal hall relief desk",
    "Block 7 community kitchen",
    "Old bridge collection point",
    "East field medical tent",
]
PLACES_HI = [
    "सेक्टर बी रिवरसाइड शरण केंद्र",
    "हिलव्यू स्कूल शेल्टर",
    "उत्तरी तट बचाव बिंदु",
    "नगर भवन राहत डेस्क",
    "ब्लॉक ७ कम्युनिटी किचन",
]
PLACES_HINGLISH = [
    "Riverside camp sector B",
    "Hillview school shelter",
    "north embankment point",
    "nagar bhawan relief desk",
    "block 7 community kitchen",
]

DISASTERS_EN = ["flood", "earthquake", "wildfire smoke", "cyclone warning", "landslide risk"]
DISASTERS_HI = ["बाढ़", "भूकंप", "जंगल की आग का धुआँ", "चक्रवात चेतावनी", "भूस्खलन का खतरा"]
DISASTERS_HINGLISH = ["baadh", "bhookamp", "aag ka dhuaan", "cyclone alert", "landslide risk"]

LABELS = [
    "benign_emergency_information",
    "direct_threat_or_intimidation",
    "coercion_or_extortion",
    "possible_relief_worker_impersonation_or_fraud",
    "report_or_warning_about_misconduct",
    "quoted_or_historical_threat",
    "ambiguous_needs_review",
]


@dataclass(frozen=True)
class TemplateFamily:
    family_id: str
    label: str
    language_bucket: str  # english | hindi_hinglish
    language_subtype: str  # english | devanagari_hindi | latin_hinglish
    disaster_tags: tuple[str, ...]
    render: Callable[..., str]
    scenario_group: str = "legacy_v1"  # documented bucket for audits / splits


def _pick(rng, items):
    return items[int(rng.integers(0, len(items)))]


def _vary_spelling_en(rng, word: str) -> str:
    if rng.random() < 0.15 and len(word) > 4:
        return word.replace("e", "3", 1) if "e" in word else word + " pls"
    if rng.random() < 0.1:
        return word.upper() if rng.random() < 0.5 else word
    return word


# --- English families (multiple per label) ---

def _en_benign_families() -> list[TemplateFamily]:
    families = []
    patterns = [
        (
            "en_benign_evac_{i}",
            lambda rng, i: (
                f"{_pick(rng, DISASTERS_EN).title()} update: move to {_pick(rng, PLACES_EN)} "
                f"before {rng.integers(18, 23)}:00. Carry ID and one water bottle. "
                f"Helpline {_pick(rng, ['108', '112', 'district control']) }."
            ),
        ),
        (
            "en_benign_supply_{i}",
            lambda rng, i: (
                f"Volunteers at {_pick(rng, PLACES_EN)}: rice kits from {_pick(rng, ['10am', '11:30', '2pm'])}. "
                f"Queue by token number. Elderly lane on left."
            ),
        ),
        (
            "en_benign_missing_{i}",
            lambda rng, i: (
                f"Missing after {_pick(rng, DISASTERS_EN)}: {_pick(rng, ['teen boy', 'elderly woman', 'two children'])} "
                f"last seen near {_pick(rng, PLACES_EN)}. Contact desk with photo, not social media rumours."
            ),
        ),
        (
            "en_benign_medical_{i}",
            lambda rng, i: (
                f"Mobile clinic at {_pick(rng, PLACES_EN)} for minor injuries and ORS packets. "
                f"No payment. Bring prescription meds list if any."
            ),
        ),
    ]
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(24):
            families.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="benign_emergency_information",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _v=v, _fn=fn: _fn(rng, _v),
                )
            )
    return families


def _en_report_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_report_scam_{i}",
            lambda rng, i: (
                f"Warning: unverified {_pick(rng, ['WhatsApp', 'SMS', 'flyer'])} asking for "
                f"UPI for 'priority flood kits' is circulating. Official distribution is only at "
                f"{_pick(rng, PLACES_EN)}. Do not pay strangers."
            ),
        ),
        (
            "en_report_theft_{i}",
            lambda rng, i: (
                f"Camp volunteers report {_pick(rng, ['bags', 'blankets', 'medicine boxes'])} stolen "
                f"near {_pick(rng, PLACES_EN)} last night. If you saw anything, tell security desk—"
                f"do not accuse neighbours online."
            ),
        ),
        (
            "en_report_rumor_{i}",
            lambda rng, i: (
                f"Please ignore rumour that {_pick(rng, PLACES_EN)} is closing. "
                f"Official channel says it stays open for {_pick(rng, ['families', 'women and children', 'all residents'])}."
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(18):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="report_or_warning_about_misconduct",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn, _v=v: _fn(rng, _v),
                )
            )
    return out


def _en_quoted_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_quote_news_{i}",
            lambda rng, i: (
                f"Local bulletin quotes eyewitness at {_pick(rng, PLACES_EN)}: "
                f"'people were shouting threats' during {_pick(rng, DISASTERS_EN)} chaos. "
                f"Police asked citizens not to spread unverified clips."
            ),
        ),
        (
            "en_quote_rt_{i}",
            lambda rng, i: (
                f"RT @DistrictInfo: 'Do not block rescue trucks' — statement after incident near "
                f"{_pick(rng, PLACES_EN)}. Sharing for awareness, not endorsement."
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(20):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="quoted_or_historical_threat",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn: _fn(rng, 0),
                )
            )
    return out


def _en_threat_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_threat_direct_{i}",
            lambda rng, i: (
                f"If you cut the line at {_pick(rng, PLACES_EN)} again, you will regret it. "
                f"Stay away from our section after dark."
            ),
        ),
        (
            "en_threat_intimidate_{i}",
            lambda rng, i: (
                f"We control this side of the camp. Outsiders should leave before "
                f"{_pick(rng, ['tonight', 'morning', 'the next distribution'])} or face trouble."
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(22):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="direct_threat_or_intimidation",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn: _fn(rng, 0),
                )
            )
    return out


def _en_coercion_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_coerce_pay_{i}",
            lambda rng, i: (
                f"No kit unless you pay {_pick(rng, ['200', '500', 'a fee'])} to the desk guy at "
                f"{_pick(rng, PLACES_EN)}. That's the rule now."
            ),
        ),
        (
            "en_coerce_supply_{i}",
            lambda rng, i: (
                f"Hand over half your rations if you want a space under the tarp near "
                f"{_pick(rng, PLACES_EN)}. Not joking."
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(22):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="coercion_or_extortion",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn: _fn(rng, 0),
                )
            )
    return out


def _en_impersonation_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_imperson_ngo_{i}",
            lambda rng, i: (
                f"I am from 'Rapid Relief NGO' — transfer 'donation' to verify your family slot at "
                f"{_pick(rng, PLACES_EN)}. Share screenshot within 1 hour. (Unverified message.)"
            ),
        ),
        (
            "en_imperson_official_{i}",
            lambda rng, i: (
                f"Government relief team here. Send ID photo and bank details on this chat to skip "
                f"queue at {_pick(rng, PLACES_EN)}. Official urgent."
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(20):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="possible_relief_worker_impersonation_or_fraud",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn: _fn(rng, 0),
                )
            )
    return out


def _en_ambiguous_families() -> list[TemplateFamily]:
    patterns = [
        (
            "en_ambig_fragment_{i}",
            lambda rng, i: (
                f"{_pick(rng, PLACES_EN)} — maybe open? heard something about "
                f"{_pick(rng, ['threats', 'fees', 'new rules'])} but not sure who said. ask later"
            ),
        ),
        (
            "en_ambig_mixed_{i}",
            lambda rng, i: (
                f"Bring water. Also someone yelling near tent row {_pick(rng, ['4', '9', '12'])} "
                f"could be argument or worse idk coming now"
            ),
        ),
    ]
    out = []
    for i, (fid_t, fn) in enumerate(patterns):
        for v in range(20):
            out.append(
                TemplateFamily(
                    family_id=fid_t.format(i=v),
                    label="ambiguous_needs_review",
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("multi",),
                    render=lambda rng, _fn=fn: _fn(rng, 0),
                )
            )
    return out


# --- Devanagari Hindi ---

def _hi_dev_benign() -> list[TemplateFamily]:
    out = []
    for v in range(28):
        out.append(
            TemplateFamily(
                family_id=f"hi_dev_benign_{v}",
                label="benign_emergency_information",
                language_bucket="hindi_hinglish",
                language_subtype="devanagari_hindi",
                disaster_tags=("multi",),
                render=lambda rng, _v=v: (
                    f"{_pick(rng, DISASTERS_HI)} अपडेट: {_pick(rng, PLACES_HI)} पर जाएँ, "
                    f"पानी की एक बोतल और पहचान पत्र साथ रखें। हेल्पलाइन {_pick(rng, ['108', '112'])}।"
                ),
            )
        )
    return out


def _hi_dev_other(label: str, prefix: str, templates: list[str], n: int) -> list[TemplateFamily]:
    out = []
    for v in range(n):
        tpl = templates[v % len(templates)]
        out.append(
            TemplateFamily(
                family_id=f"hi_dev_{prefix}_{v}",
                label=label,
                language_bucket="hindi_hinglish",
                language_subtype="devanagari_hindi",
                disaster_tags=("multi",),
                render=lambda rng, _tpl=tpl: _tpl.format(
                    place=_pick(rng, PLACES_HI),
                    disaster=_pick(rng, DISASTERS_HI),
                    time=_pick(rng, ["आज रात", "कल सुबह", "अभी"]),
                ),
            )
        )
    return out


# --- Latin Hinglish ---

def _hing_latin_benign() -> list[TemplateFamily]:
    out = []
    for v in range(28):
        out.append(
            TemplateFamily(
                family_id=f"hing_lat_benign_{v}",
                label="benign_emergency_information",
                language_bucket="hindi_hinglish",
                language_subtype="latin_hinglish",
                disaster_tags=("multi",),
                render=lambda rng: (
                    f"{_pick(rng, DISASTERS_HINGLISH)} update: jao {_pick(rng, PLACES_HINGLISH)} "
                    f"with ID aur ek bottle paani. Helpline {_pick(rng, ['108', '112'])} ok?"
                ),
            )
        )
    return out


def _hing_latin_other(label: str, prefix: str, templates: list[str], n: int) -> list[TemplateFamily]:
    out = []
    for v in range(n):
        tpl = templates[v % len(templates)]
        out.append(
            TemplateFamily(
                family_id=f"hing_lat_{prefix}_{v}",
                label=label,
                language_bucket="hindi_hinglish",
                language_subtype="latin_hinglish",
                disaster_tags=("multi",),
                render=lambda rng, _tpl=tpl: _tpl.format(
                    place=_pick(rng, PLACES_HINGLISH),
                    disaster=_pick(rng, DISASTERS_HINGLISH),
                ),
            )
        )
    return out


HI_DEV_TEMPLATES = {
    "report_or_warning_about_misconduct": [
        "चेतावनी: {place} के लिए झूठा UPI मांगने वाला मैसेज फैल रहा है। अधिकारिक वितरण पर भरोसा करें, भुगतान न करें।",
        "रिपोर्ट: {disaster} के बाद {place} पर चोरी की शिकायत। सुरक्षा डेस्क को बताएँ, ऑनलाइन आरोप न लगाएँ।",
    ],
    "quoted_or_historical_threat": [
        "स्थानीय रिपोर्ट में उद्धरण: '{place} पर लोगों ने धमकी भरी आवाज़ सुनी' — पुलिस ने अप्रमाणित वीडियो न फैलाने को कहा।",
        "जिला बुलेटिन: 'बचाव गाड़ियों को रोकें नहीं' — {disaster} के बाद घोषणा।",
    ],
    "direct_threat_or_intimidation": [
        "अगर {place} पर फिर लाइन काटी तो परिणाम भुगतना पड़ेगा। हमारे इलाके से दूर रहें।",
        "{time} तक यहाँ से हट जाओ, वरना मुश्किल होगी।",
    ],
    "coercion_or_extortion": [
        "{place} पर किट के लिए फीस दो वरना मत मिलेगा — यही नियम है।",
        "छावनी में जगह चाहिए तो आधा राशन दो, मजाक नहीं।",
    ],
    "possible_relief_worker_impersonation_or_fraud": [
        "मैं 'रैपिड रिलीफ NGO' से हूँ — {place} स्लॉट के लिए 'डोनेशन' भेजकर स्क्रीनशॉट भेजो।",
        "सरकारी रिलीफ टीम — कतार छोड़ने के लिए इस चैट पर ID भेजो। तत्काल।",
    ],
    "ambiguous_needs_review": [
        "{place} खुला है क्या? सुना कुछ {disaster} में फीस या झगड़ा — पक्का नहीं।",
        "पानी लाओ। टेंट पास कोई चिल्ला रहा शायद झगड़ा या कुछ और समझ नहीं आया",
    ],
}

HING_LAT_TEMPLATES = {
    "report_or_warning_about_misconduct": [
        "Warning: fake UPI msg for {place} flood kits — pay mat karo, official desk pe jao only.",
        "Report: chori ki complaint {place} pe after {disaster}. Security ko bolo, FB pe blame mat karo.",
    ],
    "quoted_or_historical_threat": [
        "News quote: 'log dhakka de rahe the' at {place} during {disaster} — police said verify karo pehle.",
        "RT @District: 'do not block trucks' — sharing for info not endorsement.",
    ],
    "direct_threat_or_intimidation": [
        "line kaatoge phir se {place} pe toh dekh lenge. raat ko yahan mat aana.",
        "kal tak hat jao warna problem hogi — hamara section hai yeh.",
    ],
    "coercion_or_extortion": [
        "kit chahiye toh fee do {place} desk pe — rules change ho gaye.",
        "tarp ke neeche jagah = half ration do, joking nahi.",
    ],
    "possible_relief_worker_impersonation_or_fraud": [
        "main Rapid Relief NGO se — {place} slot ke liye donation proof bhejo chat pe.",
        "govt team yahan — queue skip ke liye ID photo bhejo, urgent bola.",
    ],
    "ambiguous_needs_review": [
        "{place} open hai kya? suna {disaster} me kuch fee ya fight — confirm nahi.",
        "paani lete aao, tent row me shouting shayad fight ya kuch aur pata nahi",
    ],
}


def all_families() -> list[TemplateFamily]:
    families: list[TemplateFamily] = []
    families.extend(_en_benign_families())
    families.extend(_en_report_families())
    families.extend(_en_quoted_families())
    families.extend(_en_threat_families())
    families.extend(_en_coercion_families())
    families.extend(_en_impersonation_families())
    families.extend(_en_ambiguous_families())

    families.extend(_hi_dev_benign())
    for label, tpls in HI_DEV_TEMPLATES.items():
        families.extend(_hi_dev_other(label, label[:8], tpls, 22))

    families.extend(_hing_latin_benign())
    for label, tpls in HING_LAT_TEMPLATES.items():
        families.extend(_hing_latin_other(label, label[:8], tpls, 22))

    return families
