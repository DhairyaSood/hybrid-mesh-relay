# Misuse ML training plan (when labels exist)

## Current state (2026-10-23)

- **Synthetic 10k dataset** available for pipeline development (`data/synthetic_misuse_10k/`, see `SYNTHETIC_DATASET_CARD.md`). Fiction only — not a substitute for human-adjudicated real-world labels.
- **No legitimate human misuse-labelled dataset** is available locally for production claims.
- India–Nepal corpus provides disaster / flood–earthquake / **Help** labels only — **must not** be used as misuse ground truth.
- HumAID archive (`HumAID_data_events_set1_47K.tar.gz` on Desktop) is **not extracted**; licence/terms were **not verified** in this repo — do not train until documented permission exists.
- Annotation pilot tooling exists under `experiments/disaster_misuse_classifier/` but **no gold labels** have been produced.

## Do not train until

1. ≥1,000 adjudicated messages with `M0`–`M7` / `D0`–`D6` labels (`docs/ANNOTATION_SCHEMA.md`).
2. Corpus **data rights** confirmed for storage and derivative annotations (`docs/PROVENANCE_AND_LICENCE.md`).
3. Frozen eval reserve (`pilot_eval_reserve`) documented.

## Recommended model (post-labels)

| Stage | Approach |
|-------|----------|
| Baseline | `compact_word` TF-IDF + logistic regression (same stack as humanitarian classifier) |
| Primary metric | Leave-one-`event_id` macro-F1 on adjudicated gold |
| Secondary | Random stratified split (report separately) |
| Rare classes | Oversample `M3`/`M4`/`M5` in training only; full adjudication on positives |

## Export

Use `experiments/disaster_classifier/src/export_mobile_bundle.py` pattern for on-device bundles after misuse labels exist.

## Until then

Use **rules engine** (`rules/misuse_warning_engine.py`) for optional UI hints only — no ML misuse claims.
