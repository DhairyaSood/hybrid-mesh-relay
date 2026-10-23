# Neyra local tester

Offline Gradio UI for **MH-v1** (17-class disaster type) and **MU-v1** (misuse).

```bash
cd experiments/neyra_local_tester
source .venv/bin/activate
pip install -r requirements.txt
export GRADIO_ANALYTICS_ENABLED=False
python app.py
```

Ensure joblibs exist (see `config.yaml`). If MH-v1 is missing, train per `experiments/disaster_classifier/docs/MULTI_HAZARD_MODEL.md`.

```bash
pytest tests/ -q
```
