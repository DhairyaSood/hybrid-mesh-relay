# Disaster-related misuse classifier — dataset audit (no training)

Isolated research track for **content-level misuse concerns** in disaster messaging (Neyra).  
**Not** connected to `experiments/disaster_classifier/` (humanitarian Help labels).

## Status

**Annotation pilot tooling** (no real pool / no manual labels in repo).  
**Rules-based misuse-warning prototype** — `rules/misuse_warning_engine.py` + synthetic tests.  
**Synthetic 10k training set** — generator + validation (`docs/SYNTHETIC_DATASET_CARD.md`).  
**Synthetic baseline classifier** — `scripts/train_synthetic_baseline.py` (`docs/MISUSE_ML_TRAINING_PLAN.md` for human gold).

## Scope and safety

- Classify **message text** for possible disaster-related misuse themes to support **human review**, not automated blocking.
- A label or score is **not** proof of wrongdoing, intent, or identity of the sender.
- Do **not** profile senders, contact graphs, or account metadata.
- Messages must **always** be delivered normally in any future product use.
- No cloud inference or external upload of message bodies in this experiment.

## What this folder contains

| Path | Purpose |
|---|---|
| `DATASET_AUDIT.md` | Full audit findings and recommendation |
| `docs/ANNOTATION_SCHEMA.md` | Proposed labels if building a research corpus |
| `docs/DATA_COLLECTION_PLAN.md` | Plan for a fairly large human-labelled dataset |
| `configs/audit.yaml` | Paths and keyword buckets for the audit script |
| `scripts/audit_local_corpus.py` | Re-runnable read-only audit (no training) |
| `rules/misuse_warning_engine.py` | Offline optional content hints (non-blocking) |
| `rules/DESIGN.md` | Rules engine design |
| `docs/MISUSE_ML_TRAINING_PLAN.md` | When misuse ML may be trained |
| `docs/ANDROID_INTEGRATION.md` | Plan to wire hints + classifiers in UI only |

## Run the local corpus audit

```bash
cd experiments/disaster_misuse_classifier
python3 scripts/audit_local_corpus.py --config configs/audit.yaml
```

Writes `outputs/<timestamp>/audit_report.json` (gitignored).

## 1,000-message annotation pilot

1. Read `docs/PROVENANCE_AND_LICENCE.md` and confirm permissions.  
2. Read `docs/ANNOTATION_GUIDE.md` and `docs/PILOT_WORKFLOW.md`.  
3. Generate pool (gitignored `data/pilot/`):

```bash
pip install -r requirements.txt
python scripts/sample_pilot_pool.py \
  --config configs/pilot_sampling.yaml \
  --i-accept-local-corpus-terms
python scripts/validate_annotations.py --pool data/pilot/pool_1000.jsonl
python scripts/export_label_studio_import.py \
  --pool data/pilot/pool_1000.jsonl \
  --output data/pilot/label_studio_import.json
```

Templates (empty, tracked): `templates/annotation_template.csv`, `templates/annotation_record.schema.json`  
Label Studio: `label_studio/label_config.xml`  
Scale-up plan: `docs/SCALING_TO_FULL_CORPUS.md`

## Synthetic misuse training data (10k — frozen v1)

```bash
cd experiments/disaster_misuse_classifier
python scripts/generate_synthetic_dataset.py
python scripts/validate_synthetic_dataset.py
python scripts/split_synthetic_dataset.py
```

Output (gitignored): `data/synthetic_misuse_10k/`. Labels are defined in `docs/SYNTHETIC_DATASET_LABELS.md`.

```bash
python scripts/train_synthetic_baseline.py --config configs/synthetic_train.yaml
```

Artifacts: `models/synthetic_baseline/` (canonical **MU-v1**), `outputs/synthetic_baseline_<run_id>/`.

## Synthetic v2 civic expansion (20k — candidate MU-v2)

Adds traffic/transport, official-style advisories, impersonation, benign coordination, reports/quotes, and routine public messages (`docs/SYNTHETIC_SCENARIO_FAMILIES_V2.md`).

```bash
python scripts/generate_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python scripts/validate_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python scripts/audit_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python scripts/split_synthetic_dataset.py --config configs/synthetic_dataset_v2.yaml
python scripts/train_synthetic_baseline.py --config configs/synthetic_train_v2.yaml
```

Output (gitignored): `data/synthetic_misuse_v2_20k/`. Model candidate: `models/synthetic_baseline_v2/`.  
Evaluation summary: `docs/SYNTHETIC_V2_CIVIC_EVAL_REPORT.md`.

**Independent eval** (do not tune on these suites):

```bash
python scripts/eval_robustness_suite.py \
  --suite eval_suites/independent_robustness_v1.jsonl \
  --model models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib

python scripts/eval_robustness_suite.py \
  --suite eval_suites/civic_emergency_challenge_v1.jsonl \
  --model models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib

python scripts/summarize_challenge_eval.py outputs/robustness_eval_<run_id>/results.json
```

## Dataset location (outside git)

Default path in config points to the India–Nepal hierarchical corpus on Desktop.  
Do not commit raw tweets or annotations without licence review.

## Licence note

The local corpus README does not embed a licence file. The separate humanitarian experiment documents **CC BY 4.0** as the stated published licence for that corpus; upstream **CrisisNLP/CrisisLex** provenance may impose additional restrictions. This misuse track does **not** reuse Help labels as misuse labels.
