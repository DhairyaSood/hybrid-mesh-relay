"""Orchestrate synthetic dataset generation from template families."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import yaml

from synthetic.scenario_registry import resolve_families
from synthetic.scenarios import LABELS, TemplateFamily

ROOT = Path(__file__).resolve().parents[1]


def norm_text(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def sample_id(text: str, family_id: str, seq: int) -> str:
    digest = hashlib.sha256(f"{family_id}\n{seq}\n{text}".encode("utf-8")).hexdigest()[:32]
    return f"SYNTH-{digest}"


def _families_for_bucket(
    families: list[TemplateFamily],
    language_bucket: str,
    language_subtype: str | None,
    label: str,
) -> list[TemplateFamily]:
    out = []
    for f in families:
        if f.language_bucket != language_bucket:
            continue
        if language_subtype and f.language_subtype != language_subtype:
            continue
        if f.label != label:
            continue
        out.append(f)
    return out


def _jitter_message(
    rng: np.random.Generator, text: str, language_subtype: str, seq: int
) -> str:
    """Surface variation without changing scenario label."""
    t = text
    prefixes = {
        "english": ["Note:", "FYI", "Camp update:", "Quick info:", "Sharing:"],
        "latin_hinglish": ["bhai FYI", "update:", "camp info", "sun lo", "pls note"],
        "devanagari_hindi": ["ध्यान दें:", "सूचना:", "अपडेट:", "कृपया ध्यान दें:", "साझा:"],
    }
    if rng.random() < 0.35:
        p = rng.choice(prefixes.get(language_subtype, prefixes["english"]))
        t = f"{p} {t}"
    if rng.random() < 0.25:
        t = t.replace(".", "…", 1) if "." in t else t + "."
    if rng.random() < 0.2:
        t += f" ({rng.integers(1, 99)})"
    if rng.random() < 0.15 and language_subtype == "latin_hinglish":
        t = t.replace("hai", "h", 1) if "hai" in t else t + " yaar"
    if rng.random() < 0.12:
        parts = t.split()
        if len(parts) > 5:
            i = int(rng.integers(1, len(parts) - 1))
            parts.insert(i, str(rng.choice(["please", "urgent", "ok", "thanks", "now"])))
            t = " ".join(parts)
    if rng.random() < 0.08:
        t += " 🙏" if language_subtype != "english" or rng.random() < 0.4 else ""
    if seq % 17 == 0:
        t = t.replace("  ", " ")
    return t.strip()


def generate_dataset(cfg: dict) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    seed = int(cfg["random_seed"])
    rng = np.random.default_rng(seed)
    families = resolve_families(cfg)

    label_counts = cfg["label_counts"]
    if sum(label_counts.values()) != int(cfg["total_messages"]):
        raise ValueError("label_counts must sum to total_messages")

    english_target = int(cfg["english_count"])
    hindi_target = int(cfg["hindi_hinglish_count"])
    dev_share = float(cfg["devanagari_share"])
    # Per-label EN/HI split 50-50
    en_quota: dict[str, int] = {}
    hi_quota: dict[str, int] = {}
    for label, total in label_counts.items():
        en_q = total // 2
        hi_q = total - en_q
        en_quota[label] = en_q
        hi_quota[label] = hi_q

    if sum(en_quota.values()) != english_target:
        # Adjust largest label
        diff = english_target - sum(en_quota.values())
        k = max(en_quota, key=en_quota.get)
        en_quota[k] += diff
        hi_quota[k] -= diff

    records: list[dict[str, Any]] = []
    seen_text: set[str] = set()
    max_attempts = int(cfg.get("max_generation_attempts_per_slot", 40))
    min_chars = int(cfg.get("min_text_chars", 8))

    stats = {
        "families_available": len(families),
        "generation_failures": 0,
        "jitter_collisions_avoided": 0,
    }

    def emit_from_pool(
        pool: list[TemplateFamily],
        label: str,
        language_bucket: str,
        language_subtype: str,
        count: int,
    ) -> None:
        nonlocal records
        if not pool:
            raise RuntimeError(
                f"No template families for label={label} bucket={language_bucket} subtype={language_subtype}"
            )
        family_cycle = 0
        seq = 0
        produced = 0
        while produced < count:
            fam = pool[family_cycle % len(pool)]
            family_cycle += 1
            success = False
            for _ in range(max_attempts):
                seq += 1
                text = fam.render(rng)
                text = _jitter_message(rng, text, language_subtype, seq)
                if len(text) < min_chars:
                    continue
                key = norm_text(text)
                if key in seen_text:
                    stats["jitter_collisions_avoided"] += 1
                    continue
                seen_text.add(key)
                row = {
                    "sample_id": sample_id(text, fam.family_id, seq),
                    "message_text": text,
                    "label": label,
                    "language_bucket": language_bucket,
                    "language_subtype": language_subtype,
                    "template_family_id": fam.family_id,
                    "scenario_group": fam.scenario_group,
                    "disaster_tags": list(fam.disaster_tags),
                    "label_source": "generator_scenario",
                    "synthetic": True,
                    "random_seed": seed,
                }
                records.append(row)
                produced += 1
                success = True
                break
            if not success:
                stats["generation_failures"] += 1
                raise RuntimeError(f"Failed to generate unique text for {fam.family_id}")

    for label in LABELS:
        emit_from_pool(
            _families_for_bucket(families, "english", "english", label),
            label,
            "english",
            "english",
            en_quota[label],
        )

    dev_remaining = int(round(hindi_target * dev_share))
    lat_remaining = hindi_target - dev_remaining
    for label in LABELS:
        hi_total = hi_quota[label]
        dev_n = hi_total // 2
        lat_n = hi_total - dev_n
        # Steer global Devanagari / Hinglish totals toward 50/50 of Hindi half
        if dev_remaining < dev_n:
            dev_n = dev_remaining
            lat_n = hi_total - dev_n
        if lat_remaining < lat_n:
            lat_n = lat_remaining
            dev_n = hi_total - lat_n
        emit_from_pool(
            _families_for_bucket(families, "hindi_hinglish", "devanagari_hindi", label),
            label,
            "hindi_hinglish",
            "devanagari_hindi",
            dev_n,
        )
        dev_remaining -= dev_n
        emit_from_pool(
            _families_for_bucket(families, "hindi_hinglish", "latin_hinglish", label),
            label,
            "hindi_hinglish",
            "latin_hinglish",
            lat_n,
        )
        lat_remaining -= lat_n

    report = build_distribution_report(records, cfg)
    report["generation_stats"] = stats
    report["created_at_utc"] = datetime.now(timezone.utc).isoformat()
    return records, report


def build_distribution_report(records: list[dict], cfg: dict) -> dict:
    from collections import Counter

    n = len(records)
    texts = [norm_text(r["message_text"]) for r in records]
    dup = n - len(set(texts))

    lang_b = Counter(r["language_bucket"] for r in records)
    sub = Counter(r["language_subtype"] for r in records)
    labels = Counter(r["label"] for r in records)

    by_lang_label: dict[str, dict[str, int]] = {}
    for r in records:
        by_lang_label.setdefault(r["language_subtype"], {})
        by_lang_label[r["language_subtype"]][r["label"]] = (
            by_lang_label[r["language_subtype"]].get(r["label"], 0) + 1
        )

    groups = Counter(r.get("scenario_group", "legacy_v1") for r in records)
    families_used = Counter(r["template_family_id"] for r in records)

    return {
        "total_count": n,
        "unique_text_count": len(set(texts)),
        "duplicate_text_count": dup,
        "scenario_group_counts": dict(sorted(groups.items())),
        "unique_template_families_used": len(families_used),
        "english_count": lang_b.get("english", 0),
        "hindi_hinglish_count": lang_b.get("hindi_hinglish", 0),
        "english_percent": round(100 * lang_b.get("english", 0) / n, 2) if n else 0,
        "devanagari_hindi_count": sub.get("devanagari_hindi", 0),
        "latin_hinglish_count": sub.get("latin_hinglish", 0),
        "label_counts": dict(sorted(labels.items())),
        "label_counts_by_language_subtype": by_lang_label,
        "config_snapshot": {
            "random_seed": cfg["random_seed"],
            "total_messages": cfg["total_messages"],
            "label_counts": cfg["label_counts"],
        },
    }


def write_jsonl(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for row in records:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_config(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def main(config_path: Path | None = None) -> int:
    cfg_path = config_path or ROOT / "configs/synthetic_dataset.yaml"
    cfg = load_config(cfg_path)
    out_dir = ROOT / cfg["output_dir"]
    records, report = generate_dataset(cfg)
    dataset_path = out_dir / cfg["dataset_filename"]
    write_jsonl(dataset_path, records)
    report_path = out_dir / cfg["report_filename"]
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Wrote {dataset_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
