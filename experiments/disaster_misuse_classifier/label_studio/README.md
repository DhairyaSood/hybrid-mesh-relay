# Label Studio (local pilot)

Use **local** Label Studio only. Do not upload pilot text to third-party clouds without a data-processing agreement.

## 1. Create project

1. Install Label Studio locally: https://labelstud.io/guide/install.html  
2. Create project → **Custom template** → paste `label_config.xml`.  
3. Set **annotator login** accounts `annotator_a` and `annotator_b` (separate projects or duplicate tasks per policy).

## 2. Import pool

After generating the pool (gitignored):

```bash
# JSONL import format (one task per line)
python scripts/export_label_studio_import.py \
  --pool data/pilot/pool_1000.jsonl \
  --output data/pilot/label_studio_import.json
```

Import `data/pilot/label_studio_import.json` via **Import** in Label Studio.

## 3. Export annotations

Export as JSON per annotator → `data/pilot/exports/annotator_a.json`  
Convert to canonical JSONL:

```bash
python scripts/convert_label_studio_export.py \
  --export data/pilot/exports/annotator_a.json \
  --annotator-id annotator_a \
  --output data/pilot/annotations/annotator_a.jsonl
```

## 4. Merge and adjudicate

See `docs/PILOT_WORKFLOW.md`.
