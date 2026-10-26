# Neyra offline ML advisories

**Branch:** `feature/censor`  
**Purpose:** On-device, **advisory-only** text classifiers for disaster context and misuse hints in the mesh messaging app. They **do not** block, delay, delete, or reroute messages.

## Idea (brief)

When a user opens message detail, the app can run **local** models on message text and show a dismissible **LOCAL ADVISORY (offline)** card with:

- **Disaster type (DC-T2)** — compact 3-class disaster relevance  
- **Keyword hints** — lightweight hazard heuristics (drills, negation, traffic routing)  
- **Humanitarian help topic (DC-T3)** — what kind of aid the text may imply  
- **Misuse (MU-v1)** — synthetic-trained misuse categories, with **civic refinement** so traffic/school/civic wording is less often mislabeled  

Training and evaluation live under `experiments/`. Shipped weights for teammates are in `model/` (joblib). The Android app loads **exported JSON** bundles (TF-IDF + logistic regression) and scores in Kotlin—no scikit-learn on device.

**Experimental (not canonical on Android):** **MH-v1** (17 hazard labels) and **MU-v2 / MU-v3** for research and the Gradio tester only.

## Canonical models

| ID | Task | Labels (summary) | Android asset | Repo artifact |
|----|------|-------------------|---------------|---------------|
| **DC-T2** | Disaster type | 3 classes | `assets/ml/dc_t2_disaster_type.json` | `experiments/disaster_classifier/models/compact_word/` (gitignored) |
| **DC-T3** | Humanitarian help | Help topics | `assets/ml/dc_t3_humanitarian_help.json` | same tree |
| **MU-v1** | Misuse advisory | 7 categories | `assets/ml/mu_v1_misuse.json` | `model/synthetic_misuse_tfidf_logreg.joblib` |
| **MH-v1** | Multi-hazard type | 17 labels | not shipped | `model/multi_hazard_tfidf_logreg_v1.joblib` |

Export to mobile JSON:

- `experiments/disaster_classifier/src/export_mobile_bundle.py`
- `experiments/disaster_misuse_classifier/scripts/export_mobile_bundle.py`

## Android integration (scope)

- **Package:** `com.hybridmesh.relay.ml` — preprocessing, sparse inference, keyword detector, civic misuse refinement  
- **UI:** `MessageDetailScreen` — advisory card only; classification runs in the detail view  
- **Out of scope for this ML layer:** `MeshEngine`, BLE, Wi‑Fi Direct, delivery queues, message transport  

**Note:** Message detail navigation may still need to be wired in the main app shell for the advisory UI to appear in everyday flows. See `docs/PROJECT_HANDOFF.md` for inventory and follow-ups.

## Evaluation (high level)

Synthetic and civic challenge metrics are documented in `docs/PROJECT_HANDOFF.md` and `experiments/disaster_misuse_classifier/docs/`. Treat all reported F1 scores as **benchmarks on curated suites**, not proof of performance on real mesh traffic.

## Tests executed (this branch)

| Suite | Command | Result |
|-------|---------|--------|
| Android unit tests (incl. ML keyword + civic refinement) | `cd android && ./gradlew :app:testDebugUnitTest --no-daemon` | **28 passed**, 0 failed |
| Neyra local tester (inference, batch, regression, hybrid, misuse refinement) | `cd experiments/neyra_local_tester && pytest tests/ -q` | **10 passed** |

**Not run for this README:** device/emulator smoke (asset load, advisory UI on hardware), instrumentation tests, full retrain pipelines.

## Try it locally

**Gradio tester (all layers, side-by-side):**

```bash
cd experiments/neyra_local_tester
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -q
python app.py
```

Open http://127.0.0.1:7860/

## Repository map

| Path | Contents |
|------|----------|
| `experiments/disaster_classifier/` | DC-T2/T3, hazard keywords, MH-v1 training, mobile export |
| `experiments/disaster_misuse_classifier/` | MU-v1/v2/v3, civic challenges, rules, export |
| `experiments/neyra_local_tester/` | Gradio demo + regression tests |
| `model/` | Canonical **MU-v1** and **MH-v1** joblibs for Python |
| `android/app/src/main/assets/ml/` | On-device JSON models (~3 MB total) |

## Limits and ethics

- **Advisory only** — not legal proof, not verified disaster intelligence, not automated moderation.  
- Models trained largely on **synthetic** text; civic suites are small and evolving.  
- No change to message **delivery** behavior based on model output.

## Further reading

- `docs/PROJECT_HANDOFF.md` — full inventory, eval tables, Android notes  
- `docs/MOBILEBERT_ASSESSMENT.md` — why on-device BERT was not adopted  
- `experiments/README.md` — short entry point for the `experiments/` tree  
