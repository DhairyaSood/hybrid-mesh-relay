# Shipped model artifacts

Canonical **Python** weights for the Neyra local tester and training workflows. Overview: **[docs/NEYRA_ML_README.md](../docs/NEYRA_ML_README.md)**.

| File | ID | Role |
|------|-----|------|
| `synthetic_misuse_tfidf_logreg.joblib` | **MU-v1** | Misuse advisory (7 labels) |
| `multi_hazard_tfidf_logreg_v1.joblib` | **MH-v1** | Experimental disaster type (17 labels) |

**Android** uses exported JSON under `android/app/src/main/assets/ml/` (DC-T2, DC-T3, MU-v1)—not these joblibs directly.

**Tester config:** `experiments/neyra_local_tester/config.yaml`

Retrain and refresh: `experiments/disaster_classifier/scripts/train_multi_hazard.py`, `experiments/disaster_misuse_classifier/scripts/train_synthetic_baseline.py`, then mobile export scripts as needed.
