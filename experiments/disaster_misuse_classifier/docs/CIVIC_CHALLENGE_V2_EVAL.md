# Civic emergency challenge v2 evaluation

**Run:** `civic_challenge_v2_eval_20261023T130316Z`

Synthetic/advisory evaluation only. **Not** proof of real-world safety or sender authenticity. **MU-v1 remains canonical.**

Suite: `civic_emergency_challenge_v2.jsonl` (73 cases)
Language counts: {'english': 30, 'devanagari_hindi': 21, 'latin_hinglish': 22}

## Independence check

- Exact duplicates in corpus: **0**
- Near duplicates (Jaccard ≥ threshold): **0**

## MU-v1 (canonical)

- Accuracy: **0.3151** | Macro-F1: **0.3469**
- Benign FP: **34/37** (91.89%)
- Harmful FN: **4/15** (26.67%)
- Harmful over-prediction on non-harmful expected: `{'coercion_or_extortion': 9, 'direct_threat_or_intimidation': 11, 'possible_relief_worker_impersonation_or_fraud': 5}`

### MU-v1 per language (small n)

- **english** (n=30): acc=0.433, macro-F1=0.430
- **devanagari_hindi** (n=21): acc=0.143, macro-F1=0.119
- **latin_hinglish** (n=22): acc=0.318, macro-F1=0.369

## MU-v2 (candidate, not promoted)

- Accuracy: **0.7123** | Macro-F1: **0.6900**
- Benign FP: **12/37** (32.43%)
- Harmful FN: **1/15** (6.67%)
- Harmful over-prediction on non-harmful expected: `{'coercion_or_extortion': 7, 'possible_relief_worker_impersonation_or_fraud': 3, 'direct_threat_or_intimidation': 1}`

### MU-v2 per language

- **english** (n=30): acc=0.700, macro-F1=0.744
- **devanagari_hindi** (n=21): acc=0.714, macro-F1=0.644
- **latin_hinglish** (n=22): acc=0.727, macro-F1=0.749

## Over-prediction analysis

Counts show how often each model assigns a **harmful** label when the expected label is not in {coercion, direct_threat, impersonation_fraud}. High counts for `report_or_warning` or `coercion` on benign civic text indicate keyword-driven false alarms.

## Misclassifications (MU-v1)

- **CH2-EN-02** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-03** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-04** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-05** expected `benign_emergency_information` → `ambiguous_needs_review` (english)
- **CH2-EN-06** expected `benign_emergency_information` → `direct_threat_or_intimidation` (english)
- **CH2-EN-07** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-08** expected `benign_emergency_information` → `ambiguous_needs_review` (english)
- **CH2-EN-09** expected `benign_emergency_information` → `possible_relief_worker_impersonation_or_fraud` (english)
- **CH2-EN-11** expected `benign_emergency_information` → `report_or_warning_about_misconduct` (english)
- **CH2-EN-12** expected `benign_emergency_information` → `direct_threat_or_intimidation` (english)
- **CH2-EN-13** expected `benign_emergency_information` → `direct_threat_or_intimidation` (english)
- **CH2-EN-15** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-17** expected `quoted_or_historical_threat` → `coercion_or_extortion` (english)
- **CH2-EN-20** expected `quoted_or_historical_threat` → `direct_threat_or_intimidation` (english)
- **CH2-EN-27** expected `ambiguous_needs_review` → `report_or_warning_about_misconduct` (english)
- **CH2-HI-01** expected `benign_emergency_information` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-HI-02** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HI-03** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HI-04** expected `benign_emergency_information` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-HI-05** expected `benign_emergency_information` → `ambiguous_needs_review` (devanagari_hindi)
- **CH2-HI-06** expected `benign_emergency_information` → `coercion_or_extortion` (devanagari_hindi)
- **CH2-HI-07** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HI-08** expected `ambiguous_needs_review` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-HI-09** expected `report_or_warning_about_misconduct` → `ambiguous_needs_review` (devanagari_hindi)
- **CH2-HI-11** expected `report_or_warning_about_misconduct` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-HI-13** expected `coercion_or_extortion` → `ambiguous_needs_review` (devanagari_hindi)
- **CH2-HI-14** expected `possible_relief_worker_impersonation_or_fraud` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-HI-15** expected `direct_threat_or_intimidation` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-HI-16** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HI-17** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HG-01** expected `benign_emergency_information` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-02** expected `benign_emergency_information` → `direct_threat_or_intimidation` (latin_hinglish)
- **CH2-HG-03** expected `benign_emergency_information` → `quoted_or_historical_threat` (latin_hinglish)
- **CH2-HG-04** expected `benign_emergency_information` → `report_or_warning_about_misconduct` (latin_hinglish)
- **CH2-HG-05** expected `benign_emergency_information` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-06** expected `benign_emergency_information` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-07** expected `benign_emergency_information` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-08** expected `ambiguous_needs_review` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-09** expected `report_or_warning_about_misconduct` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-10** expected `quoted_or_historical_threat` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-13** expected `coercion_or_extortion` → `direct_threat_or_intimidation` (latin_hinglish)
- **CH2-HG-16** expected `benign_emergency_information` → `coercion_or_extortion` (latin_hinglish)
- **CH2-HG-17** expected `benign_emergency_information` → `direct_threat_or_intimidation` (latin_hinglish)
- **CH2-HG-19** expected `benign_emergency_information` → `coercion_or_extortion` (latin_hinglish)
- **CH2-HG-20** expected `ambiguous_needs_review` → `benign_emergency_information` (latin_hinglish)
- **CH2-EN-28** expected `benign_emergency_information` → `ambiguous_needs_review` (english)
- **CH2-HI-19** expected `benign_emergency_information` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-HI-20** expected `benign_emergency_information` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-HI-21** expected `coercion_or_extortion` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-EN-30** expected `possible_relief_worker_impersonation_or_fraud` → `coercion_or_extortion` (english)

## Misclassifications (MU-v2)

- **CH2-EN-04** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-06** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-07** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-09** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-10** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-15** expected `benign_emergency_information` → `coercion_or_extortion` (english)
- **CH2-EN-17** expected `quoted_or_historical_threat` → `coercion_or_extortion` (english)
- **CH2-EN-18** expected `report_or_warning_about_misconduct` → `benign_emergency_information` (english)
- **CH2-HI-01** expected `benign_emergency_information` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-HI-08** expected `ambiguous_needs_review` → `report_or_warning_about_misconduct` (devanagari_hindi)
- **CH2-HI-11** expected `report_or_warning_about_misconduct` → `possible_relief_worker_impersonation_or_fraud` (devanagari_hindi)
- **CH2-HI-13** expected `coercion_or_extortion` → `ambiguous_needs_review` (devanagari_hindi)
- **CH2-HI-17** expected `benign_emergency_information` → `direct_threat_or_intimidation` (devanagari_hindi)
- **CH2-HG-01** expected `benign_emergency_information` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-05** expected `benign_emergency_information` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-10** expected `quoted_or_historical_threat` → `possible_relief_worker_impersonation_or_fraud` (latin_hinglish)
- **CH2-HG-11** expected `report_or_warning_about_misconduct` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-17** expected `benign_emergency_information` → `ambiguous_needs_review` (latin_hinglish)
- **CH2-HG-20** expected `ambiguous_needs_review` → `benign_emergency_information` (latin_hinglish)
- **CH2-HI-20** expected `benign_emergency_information` → `quoted_or_historical_threat` (devanagari_hindi)
- **CH2-EN-30** expected `possible_relief_worker_impersonation_or_fraud` → `coercion_or_extortion` (english)

## Recommendation

- **Do not promote MU-v2** on this evidence alone: v2 improves over v1 on this harder suite but still misclassifies **12/37** benign civic cases and **1/15** harmful cases.
- **MU-v1** shows severe harmful-class over-prediction on benign text (keywords: help, money, police, warning, pay). Keep MU-v1 as canonical for finalized audits but treat scores as unreliable on civic/emergency phrasing.
- **Further model work is justified** (data + calibration + quote/report disambiguation), not Android integration.
- **Local tester experimentation** with MU-v2 is reasonable **only** with strong disclaimers and side-by-side comparison—never for blocking delivery.

## Reproduce

```bash
python3 scripts/author_civic_challenge_v2.py
python3 scripts/validate_challenge_independence.py eval_suites/civic_emergency_challenge_v2.jsonl
python3 scripts/eval_civic_challenge_v2_full.py
python3 scripts/eval_robustness_suite.py --suite eval_suites/civic_emergency_challenge_v2.jsonl --model models/synthetic_baseline/synthetic_misuse_tfidf_logreg.joblib
python3 scripts/eval_robustness_suite.py --suite eval_suites/civic_emergency_challenge_v2.jsonl --model models/synthetic_baseline_v2/synthetic_misuse_tfidf_logreg_v2.joblib
```
