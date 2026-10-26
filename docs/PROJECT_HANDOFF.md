# Neyra ML + Android advisory — handoff (2026-10-26)

**Branch:** `feature/censor` (uncommitted local changes possible)  
**Git:** No commit/push in latest session without your approval.

## Model inventory (preserved)

| ID | Role | Artifact |
|----|------|----------|
| DC-T2 | Canonical disaster type (3) | `experiments/disaster_classifier/models/compact_word/task2_type_compact_word.joblib` |
| DC-T3 | Canonical humanitarian Help | `task3_help_compact_word.joblib` |
| MU-v1 | Canonical misuse | `model/synthetic_misuse_tfidf_logreg.joblib` |
| MU-v2 / MU-v3 | Experimental misuse | `experiments/disaster_misuse_classifier/models/synthetic_baseline_v2|v3/` |
| MH-v1 | Experimental 17-class disaster | `model/multi_hazard_tfidf_logreg_v1.joblib` |
| Keywords | Heuristic hybrid layer | `experiments/disaster_classifier/hazard_keywords/` |

## Evaluation highlights

| Suite | MU-v1 | MU-v2 | MU-v3 |
|-------|-------|-------|-------|
| Civic v2 (73, historical) | F1 0.35 | F1 0.69 | F1 0.68 |
| Civic v3 (20, independent) | F1 0.28 | F1 0.44 | F1 **0.66**, benign FP **0** |

DC-T2 random-test macro-F1 **0.962** (`outputs/20261023T121745Z_task2_type/metrics.json`).  
Synthetic metrics ≠ real mesh traffic.

## Local Python tester

```bash
cd experiments/neyra_local_tester && source .venv/bin/activate && pytest tests/ -q && python app.py
```

http://127.0.0.1:7860/ — DC-T2, keywords, hybrid, DC-T3, MU-v1 (+ refinement), MU-v2/v3 side-by-side.

## Android (advisory UI only)

- **Assets:** `android/app/src/main/assets/ml/` (~3 MB JSON exports of DC-T2, DC-T3, MU-v1)
- **Runtime:** `com.hybridmesh.relay.ml` — sparse TF-IDF+logreg in Kotlin (no sklearn on device)
- **UI:** `MessageDetailScreen` — dismissible **LOCAL ADVISORY** card; **does not** touch `MeshEngine`, BLE, or delivery
- **Export:** `experiments/disaster_classifier/src/export_mobile_bundle.py`, `disaster_misuse_classifier/scripts/export_mobile_bundle.py`

**Android unit tests:** `./gradlew :app:testDebugUnitTest` requires `android/local.properties` / `ANDROID_HOME` (failed in CI-less agent run).

## Next Git step (when you approve)

```bash
git status
git add <paths you want>
git commit -m "Add offline Android advisory + civic v3 eval"
git push origin feature/censor
```

`git status` shows what changed since the last commit on your branch.

## Android integration next

- Instrumented test on device for asset load latency
- Optional MH-v1 export (larger) or keywords-only on device for extra hazards
- Compose thread list badge (still non-blocking)
