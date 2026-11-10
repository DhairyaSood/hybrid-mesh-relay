# Civic challenge v3 (independent, n=20)

**Suite:** `eval_suites/civic_emergency_challenge_v3.jsonl` — **not** used for training or v2 tuning.

**Run:** `outputs/civic_challenge_v3_eval_* /results.json` (2026-10-26)

| Model | Macro-F1 | Benign FP (of 7 benign cases) |
|-------|----------|-------------------------------|
| MU-v1 (canonical) | 0.28 | 5 |
| MU-v2 (experimental) | 0.44 | 1 |
| MU-v3 (experimental) | **0.66** | **0** |

Small suite — illustrative only, not proof of real-world accuracy. **MU-v1 remains canonical.** MU-v3 shows promise on v3 but must be validated on larger independent sets before any promotion discussion.

Reproduce:

```bash
cd experiments/disaster_misuse_classifier
python3 scripts/author_civic_challenge_v3.py
python3 scripts/eval_civic_challenge_v3.py
```
