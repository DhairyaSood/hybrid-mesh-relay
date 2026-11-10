"""Text cleaning for synthetic misuse classifier (local to this experiment)."""

from __future__ import annotations

import re

_URL_RE = re.compile(r"https?://\S+|www\.\S+", re.I)
_MENTION_RE = re.compile(r"@\w+")
_WS_RE = re.compile(r"\s+")


def clean_text(text: str) -> str:
    if not text:
        return ""
    out = str(text)
    out = _URL_RE.sub(" ", out)
    out = _MENTION_RE.sub(" ", out)
    out = out.lower()
    return _WS_RE.sub(" ", out).strip()
