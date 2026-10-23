# Data collection plan — fairly large human-labelled misuse corpus

Target: a **research-scale** dataset large enough for stable TF-IDF + LR baselines and event hold-out evaluation, while keeping rare classes statistically visible.

## 1. Target size

| Component | Count | Rationale |
|---|---:|---|
| **Total annotated messages** | **12,000 – 20,000** | “Fairly big” for linear models; supports 70/15/15 or LOO with rare classes |
| Unique events/sources held out | ≥ **3** | Match India–Nepal structure (Nepal EQ, India EQ, India flood) + optional CrisisLex file |
| Annotators per message | **2** (+ adjudicator on conflict) | Reliability |
| Adjudicated subset | **100%** of `M1`–`M5`, **10%** of `M0` | Quality on positives |

Expected prevalence (plan for imbalance):

| Label | Target share (after sampling) |
|---|---|
| `M0` none_benign | 70–85% |
| `M1`–`M5` misuse concerns | 15–30% combined |
| Each rare class (`M3`, `M5`) | ≥ **400** adjudicated positives if possible |

Rare classes will require **oversampling** from keyword-retrieved pools and manual search — not random draws alone.

## 2. Source pools (no auto-labels)

1. **India–Nepal corpus** (local, CC BY 4.0 stated — verify DOI): stratified sample by file/event.  
2. **Keyword-enriched queue:** threat/loot/impersonation/coercion/rescue patterns from `configs/audit.yaml` → human review only.  
3. **Optional external** (licence-checked): rumour/veracity disaster tweets for `D4`/`D6` and hard negatives — re-annotate into schema; do not import foreign labels blindly.  
4. **Synthetic templates (optional, small cap ≤5%):** clearly marked `synthetic=true` paraphrases for class balance — **not** copied real tweets; exclude from event hold-out claims if used.

Do **not** fabricate real-looking tweet IDs or copy private messages from Neyra production (none should be used without separate consent).

## 3. Sampling strategy

```text
Pool (all corpus rows)
  ├─ Random stratified by event/source (40% of final set)
  ├─ Keyword queue oversample (40%)
  └─ Active learning round 2 after pilot (20%)
```

**Pilot:** 1,000 messages → tune guidelines → full scale.

## 4. Annotation workflow

1. Train annotators on `ANNOTATION_SCHEMA.md` (2–3 hour briefing + gold quiz).  
2. Tooling: Label Studio, Doccano, or spreadsheet with locked schema — **local hosting**, no cloud text upload to third parties without DPA.  
3. Export: `message_id`, `text`, `primary_concerns[]`, `discourse_type`, `event_id`, `annotator_ids`, `adjudicated` bool.  
4. Store under `experiments/disaster_misuse_classifier/data/` (gitignored).  
5. Publish only aggregated statistics + redacted examples in reports.

## 5. Splits (leakage prevention)

| Split | Method |
|---|---|
| **Train / val / test (random)** | 70/15/15 stratified on primary label — secondary metric only |
| **Primary generalisation** | Leave-one-`event_id` out (and leave-one-`source_collection` for multi-file pools) |
| **Dedup** | Drop exact duplicate text before splitting |

## 6. Licence and ethics checklist

- [ ] Confirm corpus CC BY 4.0 + citation/DOI  
- [ ] CrisisLex redistribution constraints documented  
- [ ] IRB / ethics review if required by institution  
- [ ] Annotator NDA for raw tweet text  
- [ ] No sender IDs stored unless publicly present in corpus  
- [ ] Product policy: scores → **review queue only**, never block mesh delivery  

## 7. Timeline sketch (research)

| Week | Activity |
|---|---|
| 1–2 | Finalise schema, pilot 1k, adjudication rules |
| 3–6 | Full annotation 12k–20k |
| 7 | QA, splits, audit JSON |
| 8+ | Train `compact_word` baseline under `disaster_misuse_classifier/` only |

## 8. Baseline (deferred)

When ≥ **8,000** adjudicated rows exist with ≥ **300** positives per targeted rare class:

- Word TF-IDF (1–2), `max_features=8000`, balanced Logistic Regression  
- Primary report: **mean LOO macro-F1** + per-class recall on concern classes  
- Compare to **majority-`M0`** and **keyword-rule** baseline (transparent rules from audit config)

Until then: **no training**.
