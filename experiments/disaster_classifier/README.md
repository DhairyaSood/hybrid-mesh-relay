# Neyra offline disaster message classifier (research prototype)

Isolated scikit-learn baseline for **disaster-management content classification**.
It is **not** wired into Android, BLE, Wi‑Fi Direct, mesh routing, or firmware.

## Scope and safety

- Classifies **message text only** (not sender identity, contacts, or history).
- A prediction is **not** proof that a message is true or that anyone committed wrongdoing.
- **Do not** silently block, delete, delay, or suppress mesh delivery based on scores.
- Inference is intended to stay **local**. This experiment does not call cloud APIs.
- Labels support **humanitarian disaster classification** only. Do **not** claim detection of terrorism, criminality, or impersonation.
- The Help class **Food and Medicine** is a corpus label (medical/food/casualty-related content). It is **not** a validated “urgency” score.

## Dataset (kept outside git)

Expected local path (configurable in `configs/default.yaml`):

```text
/Users/purusharth/Desktop/Hierarchical Disaster Tweet Classification Corpus/dataset
```

| Task | Labels | Files used |
|---|---|---|
| `task1_binary` | `0` non-disaster, `1` disaster | `TweetMasterData.csv`, `INDIANTWEETMASTER.csv` |
| `task2_type` | `0` non-disaster, `1` flood, `2` earthquake | `TWEETDATASET( CLASS 0,1 AND 2).csv`, `INDIANTWEETDATASET.csv` |
| `task3_help` (default) | five `Help` classes | `NEPALEARTHQUAKE.csv`, `INDIANEARTHQUAKE.csv`, `INDIANFLOOD.csv` |

### Attribution and licence

**Work used:** *Hierarchical Disaster Tweet Classification Corpus (India + Nepal)*  
**Short name:** `INDIA-NEPAL-DISASTER-TWEETS`  
**Local documentation:** corpus `dataset/README.md` (title, schema, task cascade).

**Licence (as published for this corpus, per project direction):**
[Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

Suggested attribution text:

> Hierarchical Disaster Tweet Classification Corpus (India + Nepal) (`INDIA-NEPAL-DISASTER-TWEETS`), used under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

**Verification note (this checkout):** The local `dataset/README.md` and `dataset.zip` **do not embed** a `LICENSE` file or an explicit CC BY statement. Online lookup from this environment did not return a matching Zenodo/Hugging Face record with a DOI for this exact title. Treat CC BY 4.0 as the **stated published licence for the packaged corpus**, and keep a copy of the upstream licence/DOI with your research notes when available.

**Third-party / upstream data:** The corpus README states that it pairs original **CrisisNLP / CrisisLex** Nepal–flood collections with a matched India collection. CC BY 4.0 on the packaged corpus **does not automatically resolve** every possible restriction on third-party tweet collections, platform terms, or personal data. Do not redistribute raw tweet text from this experiment folder.

Dataset files, `models/`, and `outputs/` stay **outside git commits**.

## Model

- Features: TF-IDF word n-grams (1–2) + character n-grams (`char_wb` 3–5), fitted on **train only**.
- Classifier: multinomial **Logistic Regression** with `class_weight=balanced` when configured.
- Split: stratified **70% / 15% / 15%** train / validation / test, `random_seed=42`.
- Exact duplicate texts removed before splitting.
- Secondary diagnostic: leave-one-**event/source**-out — **not** used for hyperparameter tuning.

## Setup

```bash
cd experiments/disaster_classifier
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Train and evaluate

```bash
cd experiments/disaster_classifier
source .venv/bin/activate

# Task 3 humanitarian Help classes (default)
python src/train_eval.py --config configs/default.yaml

# Task 2 disaster type (non-disaster / flood / earthquake)
python src/train_eval.py --config configs/default.yaml --task task2_type

# Diagnose random-split vs event-holdout gap (Task 3)
python src/analyze_generalization.py --config configs/default.yaml

# Compare compact models (primary metric = leave-one-group-out macro-F1)
python src/compare_model_size.py --config configs/size_comparison.yaml

# Compact word-only export path (recommended prototype)
python src/train_eval.py --config configs/compact_word.yaml --task task3_help
python src/export_mobile_bundle.py \
  --joblib models/compact_word/task3_help_compact_word.joblib \
  --output models/compact_word/task3_help_compact_mobile.json
```

Outputs (gitignored):

- `outputs/<run_id>/metrics.json`
- `outputs/<run_id>/report.md`
- `outputs/<run_id>/confusion_matrix_test.png`
- `models/<task>_tfidf_logreg.joblib`

## Android / mobile note

scikit-learn + joblib cannot be loaded on Android as-is. Export weights or convert later; do **not** integrate into the live messaging pipeline in this experiment.

## Repository policy

- Dataset files: **outside** this repo.
- `models/`, `outputs/`, `.venv/`: gitignored.
- No changes to `android/`, mesh, or firmware code.
