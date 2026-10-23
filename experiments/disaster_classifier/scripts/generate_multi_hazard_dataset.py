#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from multi_hazard.labels import MULTI_HAZARD_LABELS
from multi_hazard.scenarios import all_families

PREAMBLES = ("", "Update: ", "URGENT — ", "Ward message: ", "Forwarding: ", "सूचना: ")
TAILS = ("", " Please verify.", " Share with volunteers.", " More details soon.")


def norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower()).strip()


def diversify(base: str, rng: np.random.Generator, seq: int) -> str:
    pre = PREAMBLES[int(rng.integers(0, len(PREAMBLES)))]
    tail = TAILS[int(rng.integers(0, len(TAILS)))]
    text = f"{pre}{base}{tail}".strip()
    if seq % 19 == 0 and seq:
        text = f"{text} (ref {seq})"
    return re.sub(r"\s+", " ", text).strip()


def main() -> int:
    cfg = yaml.safe_load((ROOT / "configs/multi_hazard_synthetic.yaml").read_text())
    rng = np.random.default_rng(int(cfg["random_seed"]))
    per_label = int(cfg["per_label_count"])
    families = all_families()
    seen: set[str] = set()
    rows = []
    for label in MULTI_HAZARD_LABELS:
        pool = [f for f in families if f.label == label]
        if not pool:
            continue
        n, fi, attempts = 0, 0, 0
        cap = per_label * 40
        while n < per_label and attempts < cap:
            attempts += 1
            fam = pool[fi % len(pool)]
            fi += 1
            text = diversify(fam.render(rng), rng, n + attempts)
            key = norm(text)
            if key in seen:
                continue
            seen.add(key)
            sid = hashlib.sha256(f"{fam.family_id}:{n}:{text}".encode()).hexdigest()[:32]
            st = fam.language_subtype
            bucket = "english" if st == "english" else "hindi_hinglish"
            rows.append(
                {
                    "sample_id": f"MH-{sid}",
                    "message_text": text,
                    "label": label,
                    "language_bucket": bucket,
                    "language_subtype": st,
                    "template_family_id": fam.family_id,
                    "synthetic": True,
                }
            )
            n += 1
    out_dir = ROOT / cfg["output_dir"]
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / cfg["dataset_filename"]
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    report = {
        "total": len(rows),
        "label_counts": dict(sorted(Counter(r["label"] for r in rows).items())),
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    (out_dir / "generation_report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
