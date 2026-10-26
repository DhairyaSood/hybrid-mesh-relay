#!/usr/bin/env python3
"""CLI entry: generate synthetic 10k misuse-training dataset."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from synthetic.generate import load_config, main as gen_main


def cli() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--config",
        type=Path,
        default=ROOT / "configs/synthetic_dataset.yaml",
    )
    args = parser.parse_args()
    return gen_main(args.config)


if __name__ == "__main__":
    raise SystemExit(cli())
