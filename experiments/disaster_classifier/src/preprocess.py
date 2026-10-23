"""Lightweight, reproducible text cleaning for CrisisLex-style tweets."""

from __future__ import annotations

import re

_URL_RE = re.compile(r"https?://\S+|www\.\S+", re.IGNORECASE)
_MENTION_RE = re.compile(r"@\w+")
_WHITESPACE_RE = re.compile(r"\s+")


def as_text_list(x):
    """Sklearn FunctionTransformer helper (must live in importable module for joblib)."""
    import numpy as np
    import pandas as pd

    if isinstance(x, pd.Series):
        return x.tolist()
    if isinstance(x, np.ndarray):
        return x.ravel().tolist()
    return list(x)


def clean_text(
    text: str,
    *,
    lowercase: bool = True,
    strip_urls: bool = True,
    strip_mentions: bool = True,
) -> str:
    if text is None:
        return ""
    out = str(text)
    if strip_urls:
        out = _URL_RE.sub(" ", out)
    if strip_mentions:
        out = _MENTION_RE.sub(" ", out)
    if lowercase:
        out = out.lower()
    out = _WHITESPACE_RE.sub(" ", out).strip()
    return out
