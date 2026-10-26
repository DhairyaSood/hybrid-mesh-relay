#!/usr/bin/env python3
"""Independent civic challenge v3 — not used for training or v2 tuning."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROWS = [
    ("C3-01", "english", "benign_emergency_information", "Marshalling point moved to school hall B; no fee."),
    ("C3-02", "english", "benign_emergency_information", "Water tanker escorted by police through sector 4—thanks."),
    ("C3-03", "english", "benign_emergency_information", "Optional donation jar for generator fuel at table 2; receipts available."),
    ("C3-04", "english", "benign_emergency_information", "Share-auto stand shifted to cinema junction due to rubble."),
    ("C3-05", "english", "quoted_or_historical_threat", "She said he yelled 'I'll break your legs' by the pump—I am logging her words."),
    ("C3-06", "english", "report_or_warning_about_misconduct", "That viral dam-burst audio is from 2019—please stop forwarding."),
    ("C3-07", "english", "coercion_or_extortion", "Wire 2000 now or we leave your family in the camp."),
    ("C3-08", "english", "direct_threat_or_intimidation", "Come near the supplies again and you will regret it."),
    ("C3-09", "devanagari_hindi", "benign_emergency_information", "टीचर ने लिखा: ४ बजे पूर्वी गेट—अभ्यास खत्म, धमकी नहीं।"),
    ("C3-10", "devanagari_hindi", "report_or_warning_about_misconduct", "वायरल 'बांध टूटा' संदेश पुराना है—कृपया दोबारा न भेजें।"),
    ("C3-11", "devanagari_hindi", "ambiguous_needs_review", "फॉरवर्ड किया गया नोटिस—लेटरहेड अजीब, कोई लैंडलाइन से पुष्टि करे?"),
    ("C3-12", "latin_hinglish", "benign_emergency_information", "traffic police ne bridge band kiya, alternate route sector 9 se jao"),
    ("C3-13", "latin_hinglish", "benign_emergency_information", "school me help chahiye drill ke baad, parents pick up 3pm"),
    ("C3-14", "latin_hinglish", "quoted_or_historical_threat", "witness bola usne dhamki di queue me—main repeat kar raha hun"),
    ("C3-15", "latin_hinglish", "possible_relief_worker_impersonation_or_fraud", "main relief team se—verification fee bhejo slot ke liye"),
    ("C3-16", "english", "benign_emergency_information", "Shelter headcount at 220; bring ID if you have it."),
    ("C3-17", "english", "ambiguous_needs_review", "Strange man asking for Aadhaar photos near gate—unsure if official."),
    ("C3-18", "english", "benign_emergency_information", "Bridge closed for inspection; footpath still open until 6pm."),
    ("C3-19", "devanagari_hindi", "coercion_or_extortion", "पैसे भेजो अभी वरना राशन नहीं मिलेगा।"),
    ("C3-20", "latin_hinglish", "direct_threat_or_intimidation", "hamara area hai, raat ko mat ghumo warna problem"),
]


def main() -> int:
    out = []
    for tid, sub, lab, text in ROWS:
        out.append(
            {
                "test_id": tid,
                "message_text": text,
                "expected_label": lab,
                "language_subtype": sub,
                "scenario_rationale": f"Independent v3 case for {lab}.",
            }
        )
    path = ROOT / "eval_suites/civic_emergency_challenge_v3.jsonl"
    path.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in out) + "\n", encoding="utf-8")
    print(len(out), path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
