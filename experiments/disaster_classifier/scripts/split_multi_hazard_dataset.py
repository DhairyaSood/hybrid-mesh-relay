#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    cfg = yaml.safe_load((ROOT / "configs/multi_hazard_synthetic.yaml").read_text())
    sp = cfg["split"]
    data_path = ROOT / cfg["output_dir"] / cfg["dataset_filename"]
    rows = [json.loads(ln) for ln in data_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    by_family: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_family[r["template_family_id"]].append(r)
    rng = np.random.default_rng(int(sp["split_seed"]))
    families = sorted(by_family.keys())
    rng.shuffle(families)
    n = len(families)
    n_tr = int(n * float(sp["train_family_fraction"]))
    n_va = int(n * float(sp["validation_family_fraction"]))
    train_f = set(families[:n_tr])
    val_f = set(families[n_tr : n_tr + n_va])
    test_f = set(families[n_tr + n_va :])
    splits = {"train": [], "validation": [], "test": []}
    for fid, items in by_family.items():
        if fid in train_f:
            splits["train"].extend(items)
        elif fid in val_f:
            splits["validation"].extend(items)
        else:
            splits["test"].extend(items)
    out_dir = ROOT / cfg["output_dir"] / "splits"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, part in splits.items():
        p = out_dir / f"{name}.jsonl"
        with p.open("w", encoding="utf-8") as fh:
            for r in part:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    leak = any(
        train_f & val_f or train_f & test_f or val_f & test_f
        for _ in [0]
    )
    print("family_leakage", leak, {k: len(v) for k, v in splits.items()})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
