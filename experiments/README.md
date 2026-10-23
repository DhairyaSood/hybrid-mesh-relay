# Neyra ML / censor (`feature/censor` only)

Offline **disaster-type** and **misuse** classifiers for local demo. **Android integration is deferred** — push work on `feature/censor` under `experiments/`; keep `.joblib` models local (gitignored).

## Team workflow

1. Branch from `main`: **`feature/censor`**
2. Commit new files here (not under `android/`) to avoid merge conflicts
3. Copy trained weights into `models/` paths below; integrate into the app later

## Models

| ID | Role | Artifact (local, gitignored) |
|----|------|------------------------------|
| **MH-v1** | Disaster type — **17** hazard labels | `disaster_classifier/models/multi_hazard_v1/multi_hazard_tfidf_logreg_v1.joblib` |
| **MU-v1** | Misuse advisory — **7** categories | `disaster_misuse_classifier/models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib` |

MU-v1 uses **civic/traffic refinement rules** in `neyra_local_tester/neyra_tester/misuse_refinement.py` (tester only).

**Limits:** Advisory only; synthetic training; not proof of crime or verified disasters; no message blocking.

## Layout

| Directory | Purpose |
|-----------|---------|
| `disaster_classifier/` | MH-v1 train/eval — see `docs/MULTI_HAZARD_MODEL.md` |
| `disaster_misuse_classifier/` | MU-v1 + optional rules engine |
| `neyra_local_tester/` | Gradio UI |

## Run local tester

```bash
cd experiments/neyra_local_tester
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export GRADIO_ANALYTICS_ENABLED=False
python app.py
```

Open **http://127.0.0.1:7860/**

```bash
pytest tests/ -q
```

## Train MH-v1 (if joblib missing)

```bash
cd experiments/disaster_classifier
python3 scripts/generate_multi_hazard_dataset.py
python3 scripts/split_multi_hazard_dataset.py
python3 scripts/train_multi_hazard.py
```
