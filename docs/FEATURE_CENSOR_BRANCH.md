# `feature/censor` — ML / advisory models (Android integration later)

Workflow (team):

1. Branch from **`main`**: `feature/censor`
2. Push ongoing work to **`feature/censor`** only (new paths under `experiments/`, not `android/`)
3. **Models stay separate** — `.joblib` files are gitignored; copy artifacts locally per paths below. Wire into the app on a later integration branch.

## What lives here (no mesh / BLE / delivery changes)

| Piece | Path | Role |
|-------|------|------|
| Disaster type **MH-v1** (17 classes) | `experiments/disaster_classifier/` | Train → `models/multi_hazard_v1/multi_hazard_tfidf_logreg_v1.joblib` |
| Misuse **MU-v1** | `experiments/disaster_misuse_classifier/` | `models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib` |
| Local demo | `experiments/neyra_local_tester/` | Gradio @ `http://127.0.0.1:7860` |

Handoff summary: `docs/PROJECT_HANDOFF.md`

## Local setup (after clone)

```bash
# Train or copy joblibs into gitignored model dirs (see each experiment README)

cd experiments/neyra_local_tester
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export GRADIO_ANALYTICS_ENABLED=False
python app.py
```

## Android

**Not in scope on this branch.** Classifiers are advisory-only; integration (UI hints, no blocking) is a follow-up task.
