"""Tester-side MU-v1 refinements for civic/traffic and quote/report patterns (v1 model gaps)."""

from __future__ import annotations

import re
from dataclasses import dataclass

# --- strong positive signals (keep / force label) ---
DIRECT_THREAT = re.compile(
    r"\b("
    r"i\s+will\s+(kill|hurt|attack|shoot)|we\s+will\s+(hurt|kill|attack)|"
    r"kill\s+you|hurt\s+your\s+family|death\s+threat|"
    r"you\s+will\s+regret|or\s+else\s+we\s+will|"
    r"stay\s+away\s+or\s+(else|we)|"
    r"मार\s+दूंगा|धमकी|तुम्हें\s+मार"
    r")\b",
    re.I,
)
COERCION_PAY = re.compile(
    r"\b("
    r"send\s+money\s+now|pay\s+now\s+or|pay\s+or\s+die|money\s+before\s+rescue|"
    r"wire\s+.*(fee|upi)|verification\s+fee|"
    r"पैसे\s+भेजो|पैसा\s+भेज"
    r")\b",
    re.I,
)
IMPERSONATION_FRAUD = re.compile(
    r"\b("
    r"fake\s+(ngo|relief)|impersonat|relief\s+worker.*(pay|upi|fee)|"
    r"verification\s+fee.*relief|not\s+a\s+real\s+(ngo|charity)"
    r")\b",
    re.I,
)

# --- civic / traffic benign (override spurious threat/coercion) ---
TRAFFIC_CIVIC = re.compile(
    r"\b("
    r"traffic\s+police|police\s+have\s+closed|bridge\s+(is\s+)?closed|road\s+closed|"
    r"alternate\s+route|diversion|detour|reroute|"
    r"ambulance\s+corridor|clear(ed)?\s+traffic|congestion|gridlock|"
    r"lane\s+closed|highway\s+blocked|jam\s+on|long\s+queue\s+after\s+accident|"
    r"rail\s+delay|train\s+cancelled|bus\s+diverted|"
    r"ट्रैफिक|यातायात|ब्रिज\s+बंद|वैकल्पिक\s+मार्ग|"
    r"traffic\s+clear|diversion\s+via|sector\s+\d+"
    r")\b",
    re.I,
)
OFFICIAL_ADVISORY = re.compile(
    r"\b("
    r"official\s+helpline|disaster\s+relief\s+line|municipal\s+notice|"
    r"evacuate\s+to|shelter\s+at|boil\s+water|stay\s+indoors|"
    r"registration\s+free|no\s+fee\s+for\s+relief"
    r")\b",
    re.I,
)
SCHOOL_ROUTINE = re.compile(
    r"\b("
    r"help\s+(needed\s+)?in\s+school|school\s+is\s+closed|pick\s+up\s+students|"
    r"fire\s+drill|evacuation\s+drill|after\s+the\s+drill|parents\s+to\s+pick"
    r")\b",
    re.I,
)
REPORT_QUOTE = re.compile(
    r"\b("
    r"witness\s+reported|someone\s+reported|according\s+to\s+witness|"
    r"unconfirmed|allegedly|reportedly|they\s+said\s+that|"
    r"साक्षी\s+ने\s+बताया|किसी\s+ने\s+बताया"
    r")\b",
    re.I,
)
DEBUNK = re.compile(
    r"\b(hoax|false\s+rumou?r|fake\s+news|do\s+not\s+share|debunk|not\s+true)\b",
    re.I,
)
WARN_MISCONDUCT = re.compile(
    r"\b(warning:\s*fake|report\s+to\s+cyber|scam|do\s+not\s+pay\s+strangers)\b",
    re.I,
)


@dataclass(frozen=True)
class MisuseRefinement:
    prediction: str
    raw_prediction: str
    applied_rule: str | None
    note: str


def refine_misuse_label(message_text: str, raw_prediction: str) -> MisuseRefinement:
    """Adjust canonical MU-v1 label for known civic/traffic false positives."""
    text = message_text or ""
    norm = re.sub(r"\s+", " ", text.lower()).strip()
    raw = raw_prediction

    if not norm:
        return MisuseRefinement(raw, raw, None, "")

    has_threat = bool(DIRECT_THREAT.search(norm))
    has_coerce_pay = bool(COERCION_PAY.search(norm))
    has_impersonate = bool(IMPERSONATION_FRAUD.search(norm))
    traffic = bool(TRAFFIC_CIVIC.search(norm))
    official = bool(OFFICIAL_ADVISORY.search(norm))
    school = bool(SCHOOL_ROUTINE.search(norm))
    report = bool(REPORT_QUOTE.search(norm))
    debunk = bool(DEBUNK.search(norm))
    warn_scam = bool(WARN_MISCONDUCT.search(norm))

    # High-confidence overrides (order matters)
    if debunk:
        return MisuseRefinement(
            "benign_emergency_information",
            raw,
            "debunk_or_rumor_correction",
            "Public debunk / rumor-correction wording treated as benign coordination.",
        )

    if warn_scam and not has_coerce_pay:
        return MisuseRefinement(
            "report_or_warning_about_misconduct",
            raw,
            "scam_warning",
            "Warning others about scams/fakes — not a direct threat.",
        )

    if has_coerce_pay and not traffic:
        return MisuseRefinement(
            "coercion_or_extortion",
            raw,
            "coercion_payment",
            "Payment-or-harm demand pattern.",
        )

    if has_impersonate:
        return MisuseRefinement(
            "possible_relief_worker_impersonation_or_fraud",
            raw,
            "impersonation_fraud",
            "Relief/NGO impersonation or fee scam pattern.",
        )

    if report and (has_threat or "threaten" in norm):
        return MisuseRefinement(
            "quoted_or_historical_threat",
            raw,
            "quoted_threat_report",
            "Third-party / witness report — not treated as a direct threat by the sender.",
        )

    if (traffic or official or school) and not has_threat and not has_coerce_pay:
        return MisuseRefinement(
            "benign_emergency_information",
            raw,
            "traffic_or_civic_benign",
            "Traffic, routing, school, or official advisory wording — not misuse.",
        )

    if has_threat and not report:
        return MisuseRefinement(
            "direct_threat_or_intimidation",
            raw,
            "direct_threat_lexicon",
            "Direct threat or intimidation wording.",
        )

    if raw in ("direct_threat_or_intimidation", "coercion_or_extortion") and (traffic or school or official):
        return MisuseRefinement(
            "benign_emergency_information",
            raw,
            "traffic_civic_suppress",
            f"MU-v1 said `{raw}` but civic/traffic context — adjusted for demo accuracy.",
        )

    return MisuseRefinement(raw, raw, None, "")
