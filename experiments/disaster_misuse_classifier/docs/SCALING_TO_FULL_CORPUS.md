# Scaling from 1,000 pilot → 12,000–20,000 adjudicated messages

Preserve a **fixed evaluation reserve** from the pilot and add new data via **active sampling**, not keyword auto-labels.

## 1. Freeze evaluation sets early

From the pilot pool:

- `split_role=pilot_eval_reserve` (~15%, ~150 messages) → **never** used for training or guideline iteration after gold labels exist.  
- Hold out **entire `event_id` groups** for primary metrics (leave-one-event-out), matching `DATA_COLLECTION_PLAN.md`.

Document frozen IDs in `data/manifests/eval_frozen_ids.txt` (gitignored).

## 2. Pilot → full size tranches

| Tranche | Size (cumulative) | Purpose |
|---|---:|---|
| Pilot | 1,000 | Guidelines, κ, adjudication workflow |
| Tranche A | +4,000 | Stratified random + keyword **candidates** |
| Tranche B | +4,000–8,000 | Active sampling (see below) |
| Tranche C | +3,000–7,000 | Rare-class oversample (`M3`, `M5`, `M4`) |

**Adjudication policy (unchanged):** 100% of `M1`–`M5`; 10% of `M0`.

## 3. Active sampling (post-pilot)

After a lightweight baseline exists **only on adjudicated gold** (future phase):

1. Score unlabelled pool messages with model **uncertainty** (max prob < threshold) or **disagreement** between two baseline variants.  
2. Prioritise high-uncertainty + keyword-retrieved rare buckets.  
3. **Never** auto-apply model labels to production or to gold without human review.

Until a model exists, use **keyword candidate queues + stratified random** as in the pilot.

## 4. Representativeness checks

Each tranche, report:

- `event_id` distribution vs full corpus  
- `sampling_strategy` mix (random vs keyword vs discourse)  
- Primary label prevalence (expect `M0` 70–85%)  
- Compare keyword-hint rate vs random stratum (selection bias audit)

Reuse `scripts/sample_pilot_pool.py` with `--pilot-size` and new `--output-suffix` for tranches; append to master manifest.

## 5. Leakage controls at scale

- Deduplicate on normalised `message_text` before any split.  
- `validate_annotations.py` fails if same `sample_id` or duplicate text appears in both `train` and `holdout_event` manifests.  
- Do not copy pilot eval reserve into training tranches.

## 6. When to train (future, not now)

Train only when:

- ≥ **8,000** adjudicated rows  
- ≥ **300** adjudicated positives per rare class targeted (`M3`, `M5`), or revised targets documented  
- κ and adjudication QA pass on pilot + first tranche

Use **compact_word** TF-IDF baseline; report **LOO event macro-F1** as primary metric.
