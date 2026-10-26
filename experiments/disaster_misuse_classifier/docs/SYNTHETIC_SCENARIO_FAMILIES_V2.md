# Synthetic scenario families v2 (civic expansion)

**Scope:** `scenario_group` buckets in `synthetic/scenarios_civic.py` plus unchanged `legacy_v1` from `synthetic/scenarios.py`.  
**Not authenticated official messages** — synthetic text for classifier testing only. Labels reflect **authored scenario intent**, not sender identity verification.

## Scenario sets (config `scenario_sets`)

| Set key | Module | Description |
|---------|--------|-------------|
| `legacy_v1` | `scenarios.py` | Original camp/flood/relief templates (10k-era) |
| `civic_v2` | `scenarios_civic.py` | Traffic, official-style advisories, impersonation, coordination, reports, routine |

Dataset v2 config: `configs/synthetic_dataset_v2.yaml` — **20 000** rows, both sets, output `data/synthetic_misuse_v2_20k/`.

## Civic groups → typical labels

| Group ID | Theme | Primary labels used |
|----------|--------|---------------------|
| `civic_A_traffic_transport` | Closures, diversions, rail/bus, ambulance corridors, traffic scams | `benign_emergency_information`, `report_or_warning_about_misconduct`, `possible_relief_worker_impersonation_or_fraud`, `coercion_or_extortion`, `ambiguous_needs_review` |
| `civic_B_official_advisories` | Evacuation, schools, utilities, relief registration (synthetic official tone) | `benign_emergency_information` |
| `civic_C_impersonation_fraud` | Fake schemes, OTP/bank demands, forged notices, coercive “police” payment | `possible_relief_worker_impersonation_or_fraud`, `coercion_or_extortion` |
| `civic_D_benign_coordination` | School pickup help, welfare checks, blood donors, unverified forwards | `benign_emergency_information` |
| `civic_E_reports_quotations` | Reporting others’ threats, scam alerts, quoted witness text, uncertain rumours | `report_or_warning_about_misconduct`, `quoted_or_historical_threat`, `ambiguous_needs_review` |
| `civic_F_routine_public` | Timetables, OPD queues, weather chat, community meetings vs direct threats | `benign_emergency_information`, `direct_threat_or_intimidation` |

Seven-label schema unchanged — see `docs/SYNTHETIC_DATASET_LABELS.md`.

## Language

Per-row `language_subtype`: **50%** `english`, **25%** `devanagari_hindi`, **25%** `latin_hinglish` (enforced by generator quotas).

## Independent challenge set (not generated rows)

`eval_suites/civic_emergency_challenge_v1.jsonl` — **48** hand-authored cases (traffic, official-style, school help, impersonation, quotes).  
Used for **frozen-model** evaluation; **not** used for hyperparameter tuning.

## Preserved artifacts

- **v1 frozen:** `data/synthetic_misuse_10k/`, `models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib` (MU-v1)
- **v2 candidate:** `data/synthetic_misuse_v2_20k/`, `models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib` (MU-v2)
