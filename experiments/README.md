# Neyra ML experiments (censor / advisory)

Self-contained Python work for **offline** disaster-type and misuse classifiers.  
**Not** linked to Android build in this branch — see `docs/FEATURE_CENSOR_BRANCH.md`.

| Directory | Purpose |
|-----------|---------|
| `disaster_classifier/` | MH-v1 multi-hazard disaster type |
| `disaster_misuse_classifier/` | MU-v1 misuse advisory + rules |
| `neyra_local_tester/` | Local Gradio tester |

Trained weights live under each tree’s `models/` (gitignored). Reproduce with scripts in each project’s `scripts/` and docs.
