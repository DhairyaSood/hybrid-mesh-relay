# Neyra ML — final local models

**Updated:** 2026-10-23

| Model | Role | Path |
|-------|------|------|
| **MH-v1** | Disaster **type** (17 classes) | `experiments/disaster_classifier/models/multi_hazard_v1/multi_hazard_tfidf_logreg_v1.joblib` |
| **MU-v1** | Misuse advisory (7 classes) | `experiments/disaster_misuse_classifier/models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib` |

**Tester:** `experiments/neyra_local_tester/` → `python app.py` → http://127.0.0.1:7860/

**Train MH-v1:** `experiments/disaster_classifier/docs/MULTI_HAZARD_MODEL.md`

Advisory only; synthetic training; not integrated with Android/mesh delivery.
