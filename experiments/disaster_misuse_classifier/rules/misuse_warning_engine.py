"""Lightweight offline rules for optional misuse *content* warnings (research prototype)."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Iterable

_URL_RE = re.compile(r"https?://\S+|www\.\S+", re.I)
_WS_RE = re.compile(r"\s+")


class ConcernCategory(str, Enum):
    POSSIBLE_THREAT = "possible_threat"
    POSSIBLE_COERCION = "possible_coercion"
    POSSIBLE_IMPERSONATION_FRAUD = "possible_impersonation_fraud"


class Confidence(str, Enum):
    NONE = "none"
    LOW = "low"
    MEDIUM = "medium"


@dataclass(frozen=True)
class MisuseWarningResult:
    may_warrant_review: bool
    categories: tuple[ConcernCategory, ...] = field(default_factory=tuple)
    user_message: str = ""
    confidence: Confidence = Confidence.NONE

    def to_dict(self) -> dict:
        return {
            "may_warrant_review": self.may_warrant_review,
            "categories": [c.value for c in self.categories],
            "user_message": self.user_message,
            "confidence": self.confidence.value,
        }


# --- discourse / suppression ---

DEBUNK_WARNING = re.compile(
    r"\b(hoax|false\s+rumou?r|fake\s+news|don'?t\s+share|do\s+not\s+share|"
    r"debunk|misinformation|not\s+true|please\s+ignore|ignore\s+this)\b",
    re.I,
)
REPORT_FRAME = re.compile(
    r"\b(reports?\s+say|reportedly|allegedly|unconfirmed|rumou?r\s+has\s+it|"
    r"according\s+to|rt\s+@|retweet)\b",
    re.I,
)
DENIAL_FRAME = re.compile(
    r"\b(not\s+looting|no\s+looting|looting\s+rumou?r\s+is\s+false|"
    r"denies|denied|refutes|false\s+claim)\b",
    re.I,
)
NEGATION = re.compile(
    r"\b(don'?t|do\s+not|never|not\s+a|isn'?t|aren'?t|no\s+one\s+should)\b",
    re.I,
)
ENDORSE = re.compile(
    r"\b(we\s+should|we\s+must|you\s+must|need\s+to|go\s+loot|start\s+looting|loot\s+too)\b",
    re.I,
)
LOOTING_ENDORSE = re.compile(r"\b(go\s+loot|start\s+looting|loot\s+too|should\s+loot)\b", re.I)

# --- concern patterns (content, not sender) ---

THREAT = re.compile(
    r"\b(kill\s+(you|them|us)|shoot\s+(you|them)|burn\s+(your|their)\s+house|"
    r"i\s+will\s+(hurt|kill|attack)|we\s+will\s+(attack|kill)|death\s+threat)\b",
    re.I,
)
COERCION = re.compile(
    r"\b(pay\s+or\s+die|pay\s+for\s+rescue|money\s+before\s+rescue|"
    r"blackmail|ransom|or\s+else\s+we|do\s+this\s+or\s+we\s+will)\b",
    re.I,
)
IMPERSONATION = re.compile(
    r"\b(fake\s+ngo|fake\s+relief|impersonat(e|ing)\s+(official|police|army|ngo)|"
    r"scam\s+donation|fraudulent\s+relief|not\s+a\s+real\s+(ngo|charity)|"
    r"send\s+money\s+to\s+this\s+(account|wallet|upi))\b",
    re.I,
)

BENIGN_EMERGENCY = re.compile(
    r"\b(evacuate\s+to|shelter\s+at|first\s+aid|call\s+112|call\s+911|"
    r"official\s+helpline|disaster\s+relief\s+line|stay\s+indoors|boil\s+water|"
    r"traffic\s+police|alternate\s+route|road\s+closed|bridge\s+closed|diversion|detour|"
    r"help\s+needed\s+in\s+school|school\s+is\s+closed)\b",
    re.I,
)


def normalize(text: str) -> str:
    t = _URL_RE.sub(" ", text or "")
    t = t.lower()
    return _WS_RE.sub(" ", t).strip()


def _window(text: str, start: int, end: int, radius: int = 40) -> str:
    a = max(0, start - radius)
    b = min(len(text), end + radius)
    return text[a:b]


def _negated_near(text: str, match: re.Match) -> bool:
    win = _window(text, match.start(), match.end())
    return bool(NEGATION.search(win))


def _category_messages(cats: Iterable[ConcernCategory]) -> str:
    parts = []
    for c in cats:
        if c == ConcernCategory.POSSIBLE_THREAT:
            parts.append("language that could describe a threat of harm")
        elif c == ConcernCategory.POSSIBLE_COERCION:
            parts.append("language that could describe coercion or exploitation")
        elif c == ConcernCategory.POSSIBLE_IMPERSONATION_FRAUD:
            parts.append("language that could relate to impersonation or donation fraud")
    if not parts:
        return ""
    joined = "; ".join(parts)
    return (
        "This message may contain "
        f"{joined}. "
        "This is an automated hint only — not proof of wrongdoing. "
        "Verify with official sources before acting."
    )


def analyze_message(text: str) -> MisuseWarningResult:
    """Analyze a single message body. No side effects."""
    raw = text or ""
    norm = normalize(raw)

    if not norm or len(norm) < 4:
        return MisuseWarningResult(False, (), "", Confidence.NONE)

    if BENIGN_EMERGENCY.search(norm) and not (
        THREAT.search(norm) or COERCION.search(norm) or IMPERSONATION.search(norm)
    ):
        return MisuseWarningResult(False, (), "", Confidence.NONE)

    if DEBUNK_WARNING.search(norm) or DENIAL_FRAME.search(norm):
        return MisuseWarningResult(False, (), "", Confidence.NONE)

    reportish = bool(REPORT_FRAME.search(norm))
    endorsed = bool(ENDORSE.search(norm) or LOOTING_ENDORSE.search(norm))

    hits: list[ConcernCategory] = []

    if reportish and not endorsed:
        # Unverified relay of alarming wording — no automated flag without endorsement.
        return MisuseWarningResult(False, (), "", Confidence.NONE)

    for pat, cat in (
        (THREAT, ConcernCategory.POSSIBLE_THREAT),
        (COERCION, ConcernCategory.POSSIBLE_COERCION),
        (IMPERSONATION, ConcernCategory.POSSIBLE_IMPERSONATION_FRAUD),
    ):
        m = pat.search(norm)
        if not m:
            continue
        if _negated_near(norm, m):
            continue
        hits.append(cat)

    if endorsed and LOOTING_ENDORSE.search(norm):
        hits.append(ConcernCategory.POSSIBLE_COERCION)

    if not hits:
        return MisuseWarningResult(False, (), "", Confidence.NONE)

    conf = Confidence.MEDIUM if len(hits) > 1 or endorsed else Confidence.LOW
    msg = _category_messages(hits)
    return MisuseWarningResult(True, tuple(hits), msg, conf)
