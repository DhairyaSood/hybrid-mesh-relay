# Neyra ML experiments (`feature/censor`)

Offline disaster and misuse classifiers for **local advisory** use. See the branch overview:

**[docs/NEYRA_ML_README.md](../docs/NEYRA_ML_README.md)** — goals, models, tests, Android scope, limits.

Deep handoff: **[docs/PROJECT_HANDOFF.md](../docs/PROJECT_HANDOFF.md)**.

## Quick start (local tester)

```bash
cd experiments/neyra_local_tester
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -q
python app.py
```

http://127.0.0.1:7860/

## Layout

| Directory | Purpose |
|-----------|---------|
| `disaster_classifier/` | DC-T2/T3, hazard keywords, MH-v1, mobile export |
| `disaster_misuse_classifier/` | MU-v1 (+ v2/v3 experiments), civic eval, export |
| `neyra_local_tester/` | Gradio UI and regression tests |

Canonical joblibs: **`model/`** at repo root. Large copies under `experiments/**/models/` are gitignored.
