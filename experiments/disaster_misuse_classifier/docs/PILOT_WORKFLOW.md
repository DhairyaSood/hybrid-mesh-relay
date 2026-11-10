# Pilot workflow — dual annotation, disagreement, adjudication

Target: **1,000** messages, **two independent annotators**, adjudication on conflicts and all `M1`–`M5` positives.

## Roles

| Role | ID example | Responsibility |
|---|---|---|
| Annotator A | `annotator_a` | Independent labels |
| Annotator B | `annotator_b` | Independent labels |
| Adjudicator | `adjudicator_1` | Resolves disagreements; final labels in `adjudicated_*` fields |

Annotators must **not** discuss specific messages before both submit.

## Step-by-step

### Phase 0 — Permissions

- [ ] Read `docs/PROVENANCE_AND_LICENCE.md`  
- [ ] Confirm corpus licence / institutional approval  
- [ ] Train on `docs/ANNOTATION_GUIDE.md` + gold quiz

### Phase 1 — Pool generation (maintainer)

```bash
cd experiments/disaster_misuse_classifier
python scripts/sample_pilot_pool.py \
  --config configs/pilot_sampling.yaml \
  --i-accept-local-corpus-terms
python scripts/export_label_studio_import.py \
  --pool data/pilot/pool_1000.jsonl \
  --output data/pilot/label_studio_import.json
```

### Phase 2 — Parallel annotation

- Import pool into **two** Label Studio projects (or assign disjoint task sets).  
- Export `annotator_a.json` / `annotator_b.json`.  
- Convert:

```bash
python scripts/convert_label_studio_export.py \
  --export data/pilot/exports/annotator_a.json \
  --annotator-id annotator_a \
  --output data/pilot/annotations/annotator_a.jsonl

python scripts/convert_label_studio_export.py \
  --export data/pilot/exports/annotator_b.json \
  --annotator-id annotator_b \
  --output data/pilot/annotations/annotator_b.jsonl
```

### Phase 3 — Agreement & disagreement queue

```bash
python scripts/compute_agreement.py \
  --annotations data/pilot/annotations/annotator_a.jsonl \
  --annotations data/pilot/annotations/annotator_b.jsonl \
  --output data/pilot/reports/agreement_report.json
```

```bash
python scripts/build_adjudication_queue.py \
  --annotator-a data/pilot/annotations/annotator_a.jsonl \
  --annotator-b data/pilot/annotations/annotator_b.jsonl \
  --output data/pilot/adjudication_queue.csv
```

**Queue includes:**

- All rows where `primary_content_label` differs  
- All rows where either annotator used `M1`–`M5`  
- Random 10% of `M0`/`M0` agreements (QA)

### Phase 4 — Adjudication

Adjudicator fills `adjudicated_content_labels`, `adjudicated_discourse_type`, sets `adjudication_status=adjudicated`.

```bash
python scripts/merge_adjudicated.py \
  --pool data/pilot/pool_1000.jsonl \
  --annotator-a data/pilot/annotations/annotator_a.jsonl \
  --annotator-b data/pilot/annotations/annotator_b.jsonl \
  --adjudication data/pilot/adjudication/adjudicated.jsonl \
  --output data/pilot/pilot_gold_1000.jsonl
```

### Phase 5 — Validation

```bash
python scripts/validate_annotations.py \
  --pool data/pilot/pool_1000.jsonl \
  --gold data/pilot/pilot_gold_1000.jsonl \
  --splits-config configs/pilot_sampling.yaml
```

Pilot success criteria (guidance):

- Cohen’s κ on `primary_content_label` ≥ **0.55** (macro-averaged)  
- Adjudication queue cleared for `M1`–`M5`  
- Validation script exits 0

## Inter-annotator agreement details

`compute_agreement.py` reports:

- **Percent agreement** on `primary_content_label`  
- **Cohen’s κ** (single-label)  
- **Multi-label Jaccard** mean on `content_labels` sets  
- Confusion table on primary labels  

Low κ → revise guide, add gold examples, re-train annotators before scaling to 12k–20k.

## File layout (all gitignored under `data/`)

```text
data/pilot/
  pool_1000.jsonl
  pool_1000_manifest.json
  label_studio_import.json
  annotations/annotator_a.jsonl
  annotations/annotator_b.jsonl
  adjudication_queue.csv
  adjudication/adjudicated.jsonl
  pilot_gold_1000.jsonl
  reports/agreement_report.json
```
