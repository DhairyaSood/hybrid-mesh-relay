# MH-v1 — 17-class disaster type (local default)

**Artifact:** `models/multi_hazard_v1/multi_hazard_tfidf_logreg_v1.joblib`

Replaces DC-T2 in the local tester. Labels: see `multi_hazard/labels.py`.

**Training:** synthetic scenario data (`data/synthetic_multi_hazard_v1/`). Family-held-out splits. Not validated on real-world events.

```bash
cd experiments/disaster_classifier
python3 scripts/generate_multi_hazard_dataset.py
python3 scripts/split_multi_hazard_dataset.py
python3 scripts/train_multi_hazard.py
```

Legacy DC-T2/T3 joblibs under `models/compact_word/` are kept for history but are not used by the tester.
