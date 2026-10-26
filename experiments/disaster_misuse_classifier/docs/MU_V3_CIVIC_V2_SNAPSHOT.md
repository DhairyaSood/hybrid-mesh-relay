# MU-v3 on civic challenge v2 (73 cases) — snapshot

**Run:** `robustness_eval_20261026T063920Z`  
**Suite:** `eval_suites/civic_emergency_challenge_v2.jsonl` (not used for v3 training/tuning)

| Model | Accuracy | Macro-F1 | Failures |
|-------|----------|----------|----------|
| MU-v1 (canonical) | 0.315 | 0.347 | 50 |
| MU-v2 (experimental) | 0.712 | 0.690 | 21 |
| MU-v3 (experimental) | **0.740** | **0.681** | **19** |

MU-v3 improves accuracy slightly vs MU-v2 but **macro-F1 is lower** than MU-v2 (0.681 vs 0.690). **Retain MU-v1 canonical;** keep v2/v3 as experimental. Further work needs new scenario families (see `MU_V2_ERROR_ANALYSIS.md`) and a **new** independent challenge before promotion.
