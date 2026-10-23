# Synthetic v2 civic expansion — evaluation report

**Date:** 2026-10-23  
**Disclaimer:** All metrics are on **synthetic** or **hand-authored challenge** text. They do **not** prove real-world misuse detection accuracy, sender authenticity, or criminality.

## Dataset v2 audit (`data/synthetic_misuse_v2_20k/`)

| Check | Result |
|-------|--------|
| Rows | 20 000 (0 exact duplicate texts) |
| English / Devanagari / Hinglish | 50% / 25% / 25% |
| Scenario groups | legacy_v1 10 583; civic groups 9 417 (see `audit_report.json`) |
| Template families | 1 481 unique; **family leakage across splits: none** (`split_report.json`) |
| Near-duplicate pairs (sampled, same-label Jaccard ≥ 0.92) | 114 (style overlap; not exact duplicates) |

## Independent civic challenge (`eval_suites/civic_emergency_challenge_v1.jsonl`)

48 examples emphasizing traffic, official-style notices, **help needed in school**, impersonation, and debunking.

### Frozen MU-v1 (unchanged canonical model)

| Metric | Value |
|--------|-------|
| Accuracy | **0.604** |
| Macro-F1 | **0.570** |
| Failures | 19 |
| Benign false positives (expected benign → predicted non-benign) | **10** (e.g. CIV-O01 school closure, CIV-B06 forwarded advisory, CIV-B08 police corridor thanks) |
| Harmful false negatives (expected fraud/coercion/threat → predicted benign/other) | **4** (e.g. CIV-F05, CIV-F06, CIV-F07, CIV-P05) |

Run: `outputs/robustness_eval_20261023T125621Z/`

### Candidate MU-v2 (trained on v2 splits only; challenge **not** used for tuning)

| Metric | Value |
|--------|-------|
| Synthetic held-out **test** split | Accuracy **1.0**, macro-F1 **1.0** (same caveats as v1 synthetic test) |
| Civic challenge | Accuracy **1.0**, macro-F1 **1.0**, 0 failures |
| Benign FP / harmful FN on challenge | **0 / 0** |

Run: `outputs/synthetic_baseline_20261023T125630Z/` (train), `outputs/robustness_eval_20261023T125634Z/` (challenge)

**Interpretation:** MU-v2 fits the expanded **generator distribution** and matches this **small** challenge set. That is **not** evidence of real-world performance. Challenge wording is aligned with training families (same project, different authorship). Keep MU-v1 as finalized canonical until product review; treat MU-v2 as a **versioned candidate**.

## Reproduce

```bash
cd experiments/disaster_misuse_classifier

python3 scripts/generate_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python3 scripts/validate_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python3 scripts/audit_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python3 scripts/split_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml

python3 scripts/eval_robustness_suite.py \
  --suite eval_suites/civic_emergency_challenge_v1.jsonl \
  --model models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib

python3 scripts/train_synthetic_baseline.py --config configs/synthetic_train_v2.yaml

python3 scripts/eval_robustness_suite.py \
  --suite eval_suites/civic_emergency_challenge_v1.jsonl \
  --model models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib

python3 scripts/summarize_challenge_eval.py outputs/robustness_eval_<run_id>/results.json
```
