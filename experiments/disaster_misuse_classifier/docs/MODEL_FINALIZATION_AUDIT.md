# Neyra model finalization audit (read-only inventory)

**Date:** 2026-10-23  
**Scope:** Canonical offline ML artifacts for disaster **humanitarian** classification and **synthetic misuse** advisory classification.  
**Not in scope:** Android integration, interactive tester, mesh delivery.

---

## Canonical versions (one per track)

| Track | Canonical artifact | SHA-256 |
|-------|-------------------|---------|
| Humanitarian Help (Task 3) | `experiments/disaster_classifier/models/compact_word/task3_help_compact_word.joblib` | `ed0ec42c713b2c464ab2160714671a0724b7ec413cf025ab5c32066e9aacf79c` |
| Disaster type (Task 2) | `experiments/disaster_classifier/models/compact_word/task2_type_compact_word.joblib` | `c08efd9fe25e5959aa322684526ac999ee8600bf4eee9ea73e759149a3c617c7` |
| Misuse (synthetic baseline v1) | `experiments/disaster_misuse_classifier/models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib` | `5078ddf412d3e34ac7f24f68ec6c16e971600dc088b209d415e11f84fbe4aa3d` |

**Mobile export (humanitarian Task 3 only):**  
`experiments/disaster_classifier/models/compact_word/task3_help_compact_mobile.json` (1.37 MB) — SHA-256 `f12bfc2c577f04ab11d0c47afb6c070be35deedcce819ae2b3eb15178fe2fbdd`

**Preserved (non-canonical, do not delete):**  
`models/task3_help_tfidf_logreg.joblib`, `models/task2_type_tfidf_logreg.joblib`, `models/size_comparison/*`, misuse reports under `outputs/synthetic_baseline_20261023T122827Z/`, `outputs/robustness_eval_20261023T123225Z/`, dataset `data/synthetic_misuse_10k/`.

**Misuse v2 candidate (not canonical):** `data/synthetic_misuse_v2_20k/`, `models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib` — see `docs/SYNTHETIC_V2_CIVIC_EVAL_REPORT.md`. Canonical misuse model remains **MU-v1** above until explicitly revised.

---

## Independent real-world evaluation

| Model | Real-world / Neyra mesh | Independent non-training eval |
|-------|-------------------------|-----------------------------|
| Disaster classifier | **No** — trained on external India–Nepal tweet corpus (local path), not live mesh traffic | **Partial** — random + event/source hold-out on same corpus; not a separate real-world benchmark |
| Misuse classifier | **No** — 100% synthetic generator labels | **Partial** — `eval_suites/independent_robustness_v1.jsonl` (80 hand-authored cases); **not** real-world |

---

## Licence

Upstream disaster corpus **CC BY 4.0 is not verified** from an authoritative LICENSE file in the local package (see `experiments/disaster_classifier/README.md`). CrisisLex/CrisisNLP upstream may impose additional restrictions.

Synthetic misuse data is locally generated fiction (`docs/SYNTHETIC_DATASET_CARD.md`).
