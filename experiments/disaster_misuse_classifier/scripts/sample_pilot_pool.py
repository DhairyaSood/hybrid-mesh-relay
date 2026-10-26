#!/usr/bin/env python3
"""Sample a diverse 1,000-message pilot pool (local corpus only)."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]

def load_keyword_patterns(audit_path: Path) -> dict[str, re.Pattern]:
    cfg = yaml.safe_load(audit_path.read_text(encoding="utf-8"))
    return {
        name: re.compile(spec["pattern"], re.I)
        for name, spec in cfg["keyword_buckets"].items()
    }


def anonymized_id(source_file: str, text: str, row_index: int) -> str:
    digest = hashlib.sha256(f"{source_file}\n{row_index}\n{text}".encode("utf-8")).hexdigest()[:32]
    return f"PILOT-{digest}"


def read_corpus_rows(dataset_root: Path, event_id_by_file: dict[str, str]) -> list[dict]:
    records = []
    for path in sorted(dataset_root.glob("*.csv")):
        event_id = event_id_by_file.get(path.name)
        if not event_id:
            print(f"WARNING: no event_id mapping for {path.name}, skipping", file=sys.stderr)
            continue
        with path.open(newline="", encoding="utf-8-sig", errors="replace") as fh:
            reader = csv.DictReader(fh)
            for idx, row in enumerate(reader):
                text = (row.get("Tweet_text") or "").strip()
                if not text:
                    continue
                records.append(
                    {
                        "message_text": text,
                        "event_id": event_id,
                        "source_collection": path.name,
                        "corpus_target": (row.get("Target") or "").strip() or None,
                        "corpus_help": (row.get("Help") or "").strip() or None,
                        "row_index": idx,
                    }
                )
    return records


def norm_text(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def dedupe_records(records: list[dict]) -> list[dict]:
    seen: set[str] = set()
    out = []
    for r in records:
        key = norm_text(r["message_text"])
        if key in seen:
            continue
        seen.add(key)
        out.append(r)
    return out


def language_guess(text: str) -> str:
    if re.search(r"[\u0900-\u097F]", text):
        return "mixed" if re.search(r"[a-zA-Z]", text) else "other"
    return "en" if re.search(r"[a-zA-Z]", text) else "unknown"


def bucket_match(text: str, patterns: dict[str, re.Pattern]) -> str | None:
    for name, pat in patterns.items():
        if pat.search(text):
            return name
    return None


def sample_random_stratified(pool: list[dict], n: int, rng) -> list[dict]:
    by_event: dict[str, list[dict]] = defaultdict(list)
    for r in pool:
        by_event[r["event_id"]].append(r)
    events = sorted(by_event)
    picked: list[dict] = []
    per_event = n // len(events)
    remainder = n % len(events)
    for i, event in enumerate(events):
        k = per_event + (1 if i < remainder else 0)
        candidates = by_event[event]
        if len(candidates) <= k:
            picked.extend(candidates)
        else:
            idx = rng.choice(len(candidates), size=k, replace=False)
            picked.extend(candidates[i] for i in idx)
    return picked


def sample_keyword_candidates(
    pool: list[dict],
    patterns: dict[str, re.Pattern],
    n: int,
    rng,
    exclude_texts: set[str],
) -> list[dict]:
    buckets: dict[str, list[dict]] = defaultdict(list)
    for r in pool:
        if norm_text(r["message_text"]) in exclude_texts:
            continue
        b = bucket_match(r["message_text"], patterns)
        if b and b != "denial_rumor_warning":
            buckets[b].append({**r, "keyword_bucket_hint": b})
    # Balance across buckets
    names = sorted(buckets)
    if not names:
        return []
    per = max(1, n // len(names))
    picked: list[dict] = []
    for name in names:
        cands = buckets[name]
        k = min(per, len(cands))
        if k == 0:
            continue
        idx = rng.choice(len(cands), size=k, replace=False)
        picked.extend(cands[i] for i in idx)
    # Top up if short
    if len(picked) < n:
        remaining = [
            {**r, "keyword_bucket_hint": bucket_match(r["message_text"], patterns)}
            for r in pool
            if norm_text(r["message_text"]) not in exclude_texts
            and bucket_match(r["message_text"], patterns)
            and bucket_match(r["message_text"], patterns) != "denial_rumor_warning"
        ]
        rng.shuffle(remaining)
        for r in remaining:
            if len(picked) >= n:
                break
            picked.append(r)
    return picked[:n]


def sample_discourse_candidates(
    pool: list[dict],
    patterns: dict[str, re.Pattern],
    n: int,
    rng,
    exclude_texts: set[str],
) -> list[dict]:
    pat = patterns.get("denial_rumor_warning")
    if not pat:
        return []
    cands = []
    for r in pool:
        if norm_text(r["message_text"]) in exclude_texts:
            continue
        if pat.search(r["message_text"]):
            cands.append({**r, "keyword_bucket_hint": "denial_rumor_warning"})
    if len(cands) <= n:
        return cands
    idx = rng.choice(len(cands), size=n, replace=False)
    return [cands[i] for i in idx]


def to_pool_record(r: dict, strategy: str) -> dict:
    text = r["message_text"]
    return {
        "sample_id": anonymized_id(r["source_collection"], text, r["row_index"]),
        "message_text": text,
        "event_id": r["event_id"],
        "source_collection": r["source_collection"],
        "sampling_strategy": strategy,
        "keyword_bucket_hint": r.get("keyword_bucket_hint"),
        "contains_url": bool(re.search(r"https?://|www\.", text, re.I)),
        "corpus_target": r.get("corpus_target"),
        "corpus_help": r.get("corpus_help"),
        "split_role": "pilot_annotation",
        "language_guess": language_guess(text),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs/pilot_sampling.yaml")
    parser.add_argument(
        "--i-accept-local-corpus-terms",
        action="store_true",
        help="Required: you verified licence/permissions per docs/PROVENANCE_AND_LICENCE.md",
    )
    args = parser.parse_args()

    if not args.i_accept_local_corpus_terms:
        print(
            "ERROR: Refusing to sample without --i-accept-local-corpus-terms.\n"
            "Read docs/PROVENANCE_AND_LICENCE.md first.",
            file=sys.stderr,
        )
        return 2

    cfg = yaml.safe_load(args.config.read_text(encoding="utf-8"))
    dataset_root = Path(cfg["dataset_root"])
    if not dataset_root.is_dir():
        print(f"ERROR: dataset_root not found: {dataset_root}", file=sys.stderr)
        return 2

    import numpy as np

    rng = np.random.default_rng(int(cfg["random_seed"]))
    kw_cfg = Path(cfg["keyword_config"])
    patterns = load_keyword_patterns(kw_cfg if kw_cfg.is_absolute() else ROOT / kw_cfg)

    min_len = int(cfg.get("min_text_chars", 3))
    records = read_corpus_rows(dataset_root, cfg["event_id_by_file"])
    records = [r for r in records if len(r["message_text"]) >= min_len]
    if cfg.get("drop_duplicate_text", True):
        before = len(records)
        records = dedupe_records(records)
        print(f"Deduped {before} -> {len(records)} rows")

    alloc = cfg["allocation"]
    total = int(cfg["pilot_size"])
    if sum(alloc.values()) != total:
        print("ERROR: allocation must sum to pilot_size", file=sys.stderr)
        return 2

    random_part = sample_random_stratified(records, alloc["random_stratified"], rng)
    used_texts = {norm_text(r["message_text"]) for r in random_part}

    kw_part = sample_keyword_candidates(
        records, patterns, alloc["keyword_candidate"], rng, used_texts
    )
    used_texts |= {norm_text(r["message_text"]) for r in kw_part}
    disc_part = sample_discourse_candidates(
        records, patterns, alloc["discourse_candidate"], rng, used_texts
    )

    # Top-up random stratified if strata overlapped on text and reduced pool size.
    combined = random_part + kw_part + disc_part
    if len(combined) < total:
        need = total - len(combined)
        remaining = [r for r in records if norm_text(r["message_text"]) not in used_texts]
        if len(remaining) < need:
            print(f"WARNING: only {len(remaining)} unique rows left for top-up", file=sys.stderr)
            need = len(remaining)
        if need > 0:
            idx = rng.choice(len(remaining), size=need, replace=False)
            topup = [remaining[i] for i in idx]
            random_part = random_part + topup
            used_texts |= {norm_text(r["message_text"]) for r in topup}

    pool = (
        [to_pool_record(r, "random_stratified") for r in random_part]
        + [to_pool_record(r, "keyword_candidate") for r in kw_part]
        + [to_pool_record(r, "discourse_candidate") for r in disc_part]
    )

    # Dedupe by sample_id and normalised text (keep first occurrence).
    seen_ids: set[str] = set()
    seen_text: set[str] = set()
    deduped_pool = []
    for row in pool:
        norm = re.sub(r"\s+", " ", row["message_text"].lower()).strip()
        if row["sample_id"] in seen_ids or norm in seen_text:
            continue
        seen_ids.add(row["sample_id"])
        seen_text.add(norm)
        deduped_pool.append(row)
    pool = deduped_pool
    seen_text = {re.sub(r"\s+", " ", p["message_text"].lower()).strip() for p in pool}
    if len(pool) < total:
        need = total - len(pool)
        fillers = [r for r in records if norm_text(r["message_text"]) not in seen_text]
        if len(fillers) < need:
            print(
                f"WARNING: pool size {len(pool)} < target {total}; only {len(fillers)} unique rows left",
                file=sys.stderr,
            )
            need = len(fillers)
        if need > 0:
            idx = rng.choice(len(fillers), size=need, replace=False)
            for i in idx:
                r = fillers[i]
                pool.append(to_pool_record(r, "random_stratified"))
                seen_text.add(norm_text(r["message_text"]))

    ids = [p["sample_id"] for p in pool]
    if len(set(ids)) != len(ids):
        print("ERROR: duplicate sample_id in pool", file=sys.stderr)
        return 3

    eval_frac = float(cfg.get("eval_reserve_fraction", 0.15))
    n_eval = int(round(len(pool) * eval_frac))
    eval_idx = set(rng.choice(len(pool), size=n_eval, replace=False).tolist())
    for i, row in enumerate(pool):
        if i in eval_idx:
            row["split_role"] = "pilot_eval_reserve"

    out_dir = ROOT / cfg.get("output_dir", "data/pilot")
    out_dir.mkdir(parents=True, exist_ok=True)
    pool_path = out_dir / "pool_1000.jsonl"
    with pool_path.open("w", encoding="utf-8") as fh:
        for row in pool:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")

    manifest = {
        "created_at_utc": datetime.now(timezone.utc).isoformat(),
        "terms_acknowledged": True,
        "dataset_root": str(dataset_root),
        "pilot_size": len(pool),
        "allocation": alloc,
        "eval_reserve_count": n_eval,
        "sampling_strategy_counts": dict(
            sorted(
                {k: sum(1 for p in pool if p["sampling_strategy"] == k) for k in {p["sampling_strategy"] for p in pool}}.items()
            )
        ),
        "event_id_counts": dict(
            sorted({e: sum(1 for p in pool if p["event_id"] == e) for e in {p["event_id"] for p in pool}}.items())
        ),
        "keyword_hint_counts": dict(
            sorted(
                {
                    str(p["keyword_bucket_hint"]): sum(
                        1 for x in pool if x["keyword_bucket_hint"] == p["keyword_bucket_hint"]
                    )
                    for p in pool
                    if p["keyword_bucket_hint"]
                }.items()
            )
        ),
        "licence_note": "See docs/PROVENANCE_AND_LICENCE.md. Pool file is gitignored.",
        "no_sender_ids_stored": True,
    }
    manifest_path = out_dir / "pool_1000_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print(f"Wrote {pool_path} ({len(pool)} records)")
    print(f"Wrote {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
