"""Load and filter corpus files without embedding the dataset in the repo."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import pandas as pd

TASK1_FILES = ("TweetMasterData.csv", "INDIANTWEETMASTER.csv")
TASK2_FILES = ("TWEETDATASET( CLASS 0,1 AND 2).csv", "INDIANTWEETDATASET.csv")
TASK3_FILES = ("NEPALEARTHQUAKE.csv", "INDIANEARTHQUAKE.csv", "INDIANFLOOD.csv")

TASK1_LABEL_NAMES = {0: "non_disaster", 1: "disaster"}
TASK2_LABEL_NAMES = {0: "non_disaster", 1: "flood", 2: "earthquake"}

# Canonical Task 3 Help labels from the corpus README.
HELP_LABELS = (
    "Other useful information",
    "Donations and volunteering",
    "Sympathy and support",
    "Food and Medicine",
    "Infrastructure and utilities",
)

# Closest corpus label to "urgent assistance" for false-negative analysis.
URGENT_HELP_LABEL = "Food and Medicine"


@dataclass(frozen=True)
class LoadedCorpus:
    frame: pd.DataFrame
    task: str
    label_column: str
    label_names: dict[int | str, str]
    filter_notes: list[str]


def _read_csv(path: Path) -> pd.DataFrame:
    # utf-8-sig strips BOM present on NEPALEARTHQUAKE.csv
    return pd.read_csv(path, encoding="utf-8-sig", dtype=str, keep_default_na=False)


def _source_tag(filename: str) -> str:
    return Path(filename).stem.replace(" ", "_")


def _normalize_help(value: str) -> str:
    return " ".join(str(value).strip().split())


def load_task_frame(dataset_root: Path, task: str) -> LoadedCorpus:
    root = Path(dataset_root)
    notes: list[str] = []

    if task == "task1_binary":
        frames = []
        for name in TASK1_FILES:
            path = root / name
            df = _read_csv(path)
            df["source_file"] = name
            df["event_group"] = _source_tag(name)
            frames.append(df)
        frame = pd.concat(frames, ignore_index=True)
        frame["text"] = frame["Tweet_text"].astype(str)
        frame["label_raw"] = frame["Target"].astype(str).str.strip()
        frame["label"] = frame["label_raw"].map({"0": 0, "1": 1})
        notes.append(f"Loaded Task 1 from {list(TASK1_FILES)}")
        return LoadedCorpus(frame, task, "label", TASK1_LABEL_NAMES, notes)

    if task == "task2_type":
        frames = []
        for name in TASK2_FILES:
            path = root / name
            df = _read_csv(path)
            df["source_file"] = name
            df["event_group"] = _source_tag(name)
            frames.append(df)
        frame = pd.concat(frames, ignore_index=True)
        frame["text"] = frame["Tweet_text"].astype(str)
        frame["label_raw"] = frame["Target"].astype(str).str.strip()
        frame["label"] = frame["label_raw"].map({"0": 0, "1": 1, "2": 2})
        notes.append(f"Loaded Task 2 from {list(TASK2_FILES)}")
        return LoadedCorpus(frame, task, "label", TASK2_LABEL_NAMES, notes)

    if task == "task3_help":
        frames = []
        for name in TASK3_FILES:
            path = root / name
            df = _read_csv(path)
            df["source_file"] = name
            # Event-level group for leakage-aware reporting (not used as sole split
            # because only three events exist).
            if "NEPAL" in name.upper():
                df["event_group"] = "nepal_earthquake"
            elif "FLOOD" in name.upper():
                df["event_group"] = "india_flood"
            else:
                df["event_group"] = "india_earthquake"
            if "State" in df.columns:
                df["geo_group"] = df["State"].astype(str).str.strip().replace("", "UNKNOWN_STATE")
            else:
                df["geo_group"] = "NEPAL"
            frames.append(df)
        frame = pd.concat(frames, ignore_index=True)
        frame["text"] = frame["Tweet_text"].astype(str)
        frame["label_raw"] = frame["Help"].map(_normalize_help)
        # Encode as categorical codes aligned with HELP_LABELS order.
        label_to_id = {name: i for i, name in enumerate(HELP_LABELS)}
        frame["label"] = frame["label_raw"].map(label_to_id)
        notes.append(f"Loaded Task 3 Help from {list(TASK3_FILES)}")
        notes.append(f"Canonical Help labels: {list(HELP_LABELS)}")
        return LoadedCorpus(
            frame,
            task,
            "label",
            {i: name for i, name in enumerate(HELP_LABELS)},
            notes,
        )

    raise ValueError(f"Unknown task: {task}")


def filter_corpus(
    corpus: LoadedCorpus,
    *,
    min_text_chars: int,
    drop_duplicate_text: bool,
) -> tuple[pd.DataFrame, list[str]]:
    df = corpus.frame.copy()
    notes = list(corpus.filter_notes)
    n0 = len(df)

    df["text"] = df["text"].fillna("").astype(str)
    empty_mask = df["text"].str.strip().str.len() < min_text_chars
    n_empty = int(empty_mask.sum())
    df = df.loc[~empty_mask].copy()
    notes.append(f"Removed {n_empty} rows with text shorter than {min_text_chars} chars")

    bad_label = df["label"].isna()
    n_bad = int(bad_label.sum())
    if n_bad:
        unknown = sorted(df.loc[bad_label, "label_raw"].unique().tolist())
        notes.append(f"Removed {n_bad} rows with unknown labels: {unknown}")
        df = df.loc[~bad_label].copy()
    df["label"] = df["label"].astype(int)

    if drop_duplicate_text:
        before = len(df)
        # Normalize lightly for duplicate detection only (lowercase + collapse space).
        key = df["text"].str.lower().str.replace(r"\s+", " ", regex=True).str.strip()
        df = df.loc[~key.duplicated(keep="first")].copy()
        notes.append(f"Removed {before - len(df)} exact duplicate texts (kept first)")

    notes.append(f"Rows after filtering: {len(df)} (from {n0})")
    return df, notes


def label_distribution(labels: Iterable[int], label_names: dict[int | str, str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value, count in pd.Series(list(labels)).value_counts().sort_index().items():
        name = label_names.get(int(value), str(value))
        counts[name] = int(count)
    return counts
