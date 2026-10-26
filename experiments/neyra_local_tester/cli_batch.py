#!/usr/bin/env python3
"""CLI batch evaluation without the web UI."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from neyra_tester.batch import run_batch
from neyra_tester.inference import load_models


def main() -> int:
    p = argparse.ArgumentParser(description="Batch Neyra model inference (offline)")
    p.add_argument("input", type=Path, help="CSV or JSONL with message_text")
    p.add_argument("-o", "--output", type=Path, default=None)
    args = p.parse_args()
    try:
        bundle = load_models()
        out = run_batch(bundle, args.input, args.output)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
