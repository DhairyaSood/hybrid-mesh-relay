#!/usr/bin/env python3
"""Split synthetic dataset by template_family_id; write manifests and check leakage."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
import yaml

ROOT = Path(__file__).resolve().parents[1]


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")


def split_by_family(rows: list[dict], cfg: dict) -> dict:
    split_cfg = cfg["split"]
    seed = int(split_cfg["split_seed"])
    rng = np.random.default_rng(seed)

    families: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        families[r["template_family_id"]].append(r)

    family_ids = sorted(families.keys())
    # Stratify families by majority label
    by_label: dict[str, list[str]] = defaultdict(list)
    for fid in family_ids:
        labels = [r["label"] for r in families[fid]]
        majority = Counter(labels).most_common(1)[0][0]
        by_label[majority].append(fid)

    train_f, val_f, test_f = set(), set(), set()
    tr_frac = float(split_cfg["train_family_fraction"])
    va_frac = float(split_cfg["validation_family_fraction"])

    for label, fids in by_label.items():
        rng.shuffle(fids)
        n = len(fids)
        n_train = int(round(n * tr_frac))
        n_val = int(round(n * va_frac))
        n_test = n - n_train - n_val
        if n_test < 0:
            n_test = 0
            n_val = max(0, n - n_train)
        train_f.update(fids[:n_train])
        val_f.update(fids[n_train : n_train + n_val])
        test_f.update(fids[n_train + n_val :])

    overlap_tv = train_f & val_f
    overlap_tt = train_f & test_f
    overlap_vt = val_f & test_f
    leakage = bool(overlap_tv or overlap_tt or overlap_vt)

    splits = {"train": [], "validation": [], "test": []}
    for fid, recs in families.items():
        if fid in train_f:
            splits["train"].extend(recs)
        elif fid in val_f:
            splits["validation"].extend(recs)
        else:
            splits["test"].extend(recs)

    label_coverage = {}
    for name, part in splits.items():
        label_coverage[name] = dict(sorted(Counter(r["label"] for r in part).items()))

    rare = []
    for lab in cfg["label_counts"]:
        for name in splits:
            if label_coverage[name].get(lab, 0) < 5:
                rare.append({"split": name, "label": lab, "count": label_coverage[name].get(lab, 0)})

    return {
        "family_counts": {
            "train": len(train_f),
            "validation": len(val_f),
            "test": len(test_f),
            "total_families": len(family_ids),
        },
        "row_counts": {k: len(v) for k, v in splits.items()},
        "label_coverage_by_split": label_coverage,
        "rare_label_warnings": rare,
        "family_leakage": leakage,
        "overlapping_families": {
            "train_val": sorted(overlap_tv),
            "train_test": sorted(overlap_tt),
            "val_test": sorted(overlap_vt),
        },
        "splits": splits,
        "manifests": {
            "train_families": sorted(train_f),
            "validation_families": sorted(val_f),
            "test_families": sorted(test_f),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs/synthetic_dataset.yaml")
    parser.add_argument("--dataset", type=Path, default=None)
    args = parser.parse_args()
    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    out_dir = ROOT / cfg["output_dir"]
    dataset = args.dataset or out_dir / cfg["dataset_filename"]
    rows = load_jsonl(dataset)
    result = split_by_family(rows, cfg)

    for name in ("train", "validation", "test"):
        write_jsonl(out_dir / "splits" / f"{name}.jsonl", result["splits"][name])

    manifest_dir = out_dir / "manifests"
    manifest_dir.mkdir(parents=True, exist_ok=True)
    for key, ids in result["manifests"].items():
        (manifest_dir / f"{key}.txt").write_text("\n".join(ids) + "\n", encoding="utf-8")

    report = {k: v for k, v in result.items() if k != "splits"}
    report_path = out_dir / "split_report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 1 if report["family_leakage"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
