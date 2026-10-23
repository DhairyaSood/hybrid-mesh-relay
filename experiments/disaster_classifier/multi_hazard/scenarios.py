"""Authored synthetic scenarios (labels by scenario intent, not keywords)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

PLACES = ["Riverside ward", "Sector 9", "Hillview market", "NH-48 km 42", "नगर वार्ड ५"]


@dataclass(frozen=True)
class Family:
    family_id: str
    label: str
    language_subtype: str
    render: Callable


def _families() -> list[Family]:
    specs: list[tuple[str, str, str, list[str]]] = [
        ("non_disaster", "english", "en_nd", [
            "Coffee shop opens on Main Street tomorrow.",
            "Match was great last night, no emergency.",
        ]),
        ("fire", "english", "en_fire", [
            "Smoke on the third floor—building on fire, people evacuating.",
            "Flames spreading in the warehouse wing.",
        ]),
        ("flood", "english", "en_flood", [
            "River overflow flooded low-lying houses near {p}.",
            "Flood water entered our village last night.",
        ]),
        ("earthquake", "english", "en_eq", [
            "Strong earthquake damaged several buildings near {p}.",
            "Tremors felt for a minute; walls cracked.",
        ]),
        ("landslide_or_mudslide", "english", "en_ls", [
            "Landslide blocked the highway outside {p}.",
            "Mudslide buried three homes on the hillside.",
        ]),
        ("cyclone_or_hurricane", "english", "en_cy", [
            "Cyclone approaching the coast; shelters opening at {p}.",
            "Hurricane warning issued for the district.",
        ]),
        ("severe_storm_or_high_winds", "english", "en_st", [
            "Thunderstorm with hail damaged roofs in {p}.",
            "High winds uprooted trees along the embankment.",
        ]),
        ("heavy_rain_or_waterlogging", "english", "en_rain", [
            "Heavy rainfall caused waterlogging on ring road near {p}.",
            "School closed due to waterlogging after heavy rain in {p}.",
        ]),
        ("fire", "devanagari_hindi", "hi_fire", ["इमारत में आग लग गई, लोग निकल रहे हैं।"]),
        ("flood", "devanagari_hindi", "hi_flood", ["बाढ़ में घर डूब गए, {p} के पास।"]),
        ("earthquake", "devanagari_hindi", "hi_eq", ["भूकंप के झटके महसूस हुए, दीवारें क्षतिग्रस्त।"]),
        ("fire", "latin_hinglish", "hing_fire", ["building me aag lag gayi, smoke aa raha hai"]),
        ("flood", "latin_hinglish", "hing_flood", ["baadh me ghar doob gaye {p} ke paas"]),
        ("cyclone_or_hurricane", "latin_hinglish", "hing_cy", ["cyclone coast ke paas aa raha hai"]),
        ("transport_accident", "english", "en_tr", ["Train derailed near the station outside {p}."]),
        ("industrial_or_chemical_accident", "english", "en_ch", ["Gas leak reported at factory near {p}."]),
        ("building_collapse_or_structural_failure", "english", "en_bc", ["Bridge collapsed after the crash near {p}."]),
        ("extreme_heat_or_heatwave", "english", "en_heat", ["Heatwave warning for tomorrow in {p} district."]),
        ("non_disaster", "english", "en_drill", [
            "The fire drill is scheduled tomorrow at {p} school.",
            "Fire station is two streets away from here.",
        ]),
        ("non_disaster", "english", "en_neg", [
            "No flooding has been reported in {p} ward.",
            "Officials denied the cyclone rumour on radio.",
        ]),
        ("non_disaster", "devanagari_hindi", "hi_drill", ["कल अग्निशमन अभ्यास है।"]),
        ("unknown_or_insufficient_information", "english", "en_unk", [
            "Something bad happening??? not sure what",
            "Help??? noise outside???",
        ]),
        ("tsunami", "english", "en_ts", ["Tsunami warning after offshore quake; move to high ground."]),
        ("drought_or_water_shortage", "english", "en_dr", ["Drought left wells dry around {p}."]),
        ("extreme_cold_or_snowstorm", "english", "en_cold", ["Blizzard conditions expected tonight near {p}."]),
        ("other_disaster_or_emergency", "english", "en_oth", ["Unidentified emergency at {p}; details unclear."]),
    ]
    out: list[Family] = []
    for label, subtype, prefix, templates in specs:
        for i, tpl in enumerate(templates):
            for v in range(12):
                fid = f"{prefix}_{i}_{v}"
                out.append(
                    Family(
                        family_id=fid,
                        label=label,
                        language_subtype=subtype if subtype != "english" else "english",
                        render=lambda _rng, t=tpl, v=v: t.format(p=PLACES[v % len(PLACES)]),
                    )
                )
    return out


def all_families() -> list[Family]:
    return _families()
