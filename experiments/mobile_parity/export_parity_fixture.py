#!/usr/bin/env python3
"""Write sklearn reference predictions for the Android TF-IDF + LR parity test.

The Android unit test TfidfLogRegModelParityTest scores every text in the fixture
with the shipped JSON assets and must reproduce sklearn's label and decision
scores. Regenerate this fixture whenever a model or its export changes:

    experiments/disaster_classifier/.venv/bin/python \\
        experiments/mobile_parity/export_parity_fixture.py

Only synthetic texts already in the repo plus hand-written edge cases are used,
so no real tweet text ends up in the Android test resources.
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

import joblib

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "experiments/disaster_classifier/src"))
import preprocess  # noqa: E402  (needed to unpickle the DC pipelines)

MODELS = {
    "dc_t2_disaster_type": REPO / "experiments/disaster_classifier/models/compact_word/task2_type_compact_word.joblib",
    "dc_t3_humanitarian_help": REPO / "experiments/disaster_classifier/models/compact_word/task3_help_compact_word.joblib",
    "mu_v1_misuse": REPO / "model/synthetic_misuse_tfidf_logreg.joblib",
}
SOURCES = [
    REPO / "experiments/disaster_misuse_classifier/data/synthetic_misuse_10k/splits/test.jsonl",
    REPO / "experiments/disaster_classifier/data/synthetic_multi_hazard_v1/splits/test.jsonl",
]
PER_SOURCE = 150
OUTPUT = REPO / "android/app/src/test/resources/ml/parity_fixture.json"

EDGE_CASES = [
    "",
    "   ",
    "!!!",
    "a b c",
    "Fire!!! Building on fire, help!!",
    "flood-water rising... at 5pm",
    "URGENT: Send money now or your family will be hurt",
    "visit https://x.co/abc @rescue_team flood www.example.org",
    "बाढ़ का पानी बढ़ रहा है, मदद करें",
    "भूकंप के बाद इमारत गिर गई",
    "Earthquake near Kathmandu",
    "earthquake earthquake earthquake",
    "Relief worker here, pay ₹500 registration fee to get food kit",
    "Witness reported someone threatened the shopkeeper",
    "Traffic police: alternate route via NH-48, bridge closed",
    "School closed tomorrow due to flooding",
    "I will kill you if you come back",
    "😀 flood 🌊 help",
    "Ünïcödé façade naïve flood",
    "x² flood ²³",
    "under_score flood_water",
    "C'est l'inondation",
    "FLOOD IN CHENNAI",
    "Food and medicine needed at camp 3, two injured",
    "@राहत_दल flood update",
]


def texts() -> list[str]:
    rng = random.Random(20261113)
    out = list(EDGE_CASES)
    for source in SOURCES:
        rows = [json.loads(line)["message_text"] for line in source.open(encoding="utf-8")]
        out += rng.sample(rows, min(PER_SOURCE, len(rows)))
    return out


def main() -> int:
    samples = texts()
    cleaned = [preprocess.clean_text(t) for t in samples]
    expected = {}
    for name, path in MODELS.items():
        blob = joblib.load(path)
        pipeline = blob["pipeline"]
        classes = [str(c) for c in pipeline.named_steps["clf"].classes_]
        label_names = blob.get("label_names")
        if label_names:
            classes = [label_names[int(c)] if int(c) in label_names else label_names[str(c)] for c in classes]
        scores = pipeline.decision_function(cleaned)
        expected[name] = [
            {"label": classes[int(row.argmax())], "scores": {c: float(v) for c, v in zip(classes, row)}}
            for row in scores
        ]
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(
        json.dumps({"texts": samples, "expected": expected}, ensure_ascii=False, indent=0),
        encoding="utf-8",
    )
    print(f"wrote {len(samples)} texts x {len(MODELS)} models to {OUTPUT.relative_to(REPO)} "
          f"({OUTPUT.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
