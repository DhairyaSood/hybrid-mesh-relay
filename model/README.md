# Shipped model artifacts (`feature/censor`)

Canonical copies for teammates and the local tester. **Advisory only** — not wired into Android yet.

| File | ID | Role |
|------|-----|------|
| `multi_hazard_tfidf_logreg_v1.joblib` | **MH-v1** | Disaster type (17 labels) |
| `synthetic_misuse_tfidf_logreg.joblib` | **MU-v1** | Misuse advisory (7 labels) |

**Tester paths:** `experiments/neyra_local_tester/config.yaml` points here.

Retrain copies: see `experiments/disaster_classifier/scripts/train_multi_hazard.py` and `experiments/disaster_misuse_classifier/scripts/train_synthetic_baseline.py`, then refresh these files.
