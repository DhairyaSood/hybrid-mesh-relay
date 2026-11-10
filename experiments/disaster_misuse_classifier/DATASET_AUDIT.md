# Dataset audit — disaster-related misuse classification

**Date:** 2026-10-23  
**Phase:** Audit only (no model training)  
**Scope:** Message **content** in disaster contexts; not sender identity or criminal adjudication.

---

## 1. Executive summary

| Question | Finding |
|---|---|
| Is there a labelled misuse dataset available locally? | **No.** |
| Do existing Help / disaster-type labels map to misuse? | **No** — humanitarian support categories only. |
| Can keyword search substitute for labels? | **No** — sparse, ambiguous, mixes reports/warnings/benign text. |
| Train TF-IDF + LR now? | **Not recommended** without new human labels. |
| Next step | Build a **large, human-annotated** research corpus (see `docs/DATA_COLLECTION_PLAN.md`). |

---

## 2. Sources audited

### 2.1 Local: India–Nepal Hierarchical Disaster Tweet Corpus

**Path:** `/Users/purusharth/Desktop/Hierarchical Disaster Tweet Classification Corpus/dataset`  
**Short name:** `INDIA-NEPAL-DISASTER-TWEETS`

| File | Rows | Annotated fields | Misuse-relevant? |
|---|---:|---|---|
| `TweetMasterData.csv` | 33,369 | disaster vs not | No misuse labels |
| `INDIANTWEETMASTER.csv` | 17,000 | disaster vs not | No |
| `TWEETDATASET( CLASS 0,1 AND 2).csv` | 15,435 | non-disaster / flood / earthquake | No |
| `INDIANTWEETDATASET.csv` | 13,600 | same | No |
| `NEPALEARTHQUAKE.csv` | 5,778 | earthquake + **Help** (5 classes) | Help ≠ misuse |
| `INDIANEARTHQUAKE.csv` | 9,200 | + State/City/Seismic_zone | Help ≠ misuse |
| `INDIANFLOOD.csv` | 7,200 | flood + Help | Help ≠ misuse |

**Task 3 Help classes (humanitarian only):** Other useful information; Donations and volunteering; Sympathy and support; Food and Medicine; Infrastructure and utilities.

**Explicit misuse categories requested (threats, looting, impersonation, coercion, rescue interference):** **not present** as columns or label sets.

### 2.2 Repository (Hybrid Mesh Relay)

- `experiments/disaster_classifier/` — humanitarian Help / disaster-type baselines only. **Must not** repurpose `Food and Medicine` or other Help labels as misuse or criminality proxies.
- Android / mesh codebase — no labelled misuse corpus; no NLP training data for this task.

### 2.3 External datasets (literature scan, not downloaded)

Quick Zenodo search found **rumour/misinformation** and general **disaster tweet** corpora (often CC BY 4.0), e.g. disaster tweet collections and rumour-detection sets. None were verified as providing **multi-class disaster misuse** labels (looting, impersonation of relief workers, coercion, rescue obstruction) with **discourse type** (verified incident vs report vs denial).

| Family | Typical labels | Gap vs Neyra misuse need |
|---|---|---|
| CrisisLex / CrisisNLP situational awareness | informative, not-informative, etc. | No misuse taxonomy |
| Rumour true/false/unconfirmed | veracity of claims | Not the same as harmful conduct classes |
| COVID misinformation co-spread | fact-check pairs | Different domain and label space |
| India–Nepal corpus (local) | disaster / type / Help | No misuse |

**Conclusion:** External off-the-shelf data might supply **candidates for human re-annotation**, not ready-made misuse labels.

---

## 3. Keyword prevalence audit (not labels)

Automated keyword buckets were applied to screen for **possible** misuse-related **wording** (see `configs/audit.yaml`). This is **not** ground truth and **does not** establish guilt, intent, or verified incidents.

**Corpus-wide (unique tweet texts, lowercased, matching threat OR loot OR impersonation keywords): ~441** out of ~90k+ rows across files (order-of-magnitude; deduplicated across files).

| Bucket | Illustrative scale (all files, row-level, not deduped) |
|---|---|
| explicit_threat_violence | ~400+ row hits |
| looting_theft | ~50+ |
| impersonation_fraud | ~10+ |
| coercion_exploitation | ~200+ (often non-misuse context) |
| rescue_interference | ~10+ |
| denial_rumor_warning | ~40+ |

**Overlap with disaster labels:** Many threat-keyword rows are `Target=1` disaster tweets, but a substantial fraction are `Target=0` (non-disaster), showing keywords alone are poor misuse detectors.

**Task 3 Help mapping (Nepal earthquake file, threat-keyword rows):** hits appear across **Food and Medicine**, **Other useful information**, **Donations**, etc. — confirming Help labels describe **aid themes**, not misconduct.

**Discourse heuristics (threat/loot rows):** Majority lack clear “reported/according to” markers in a simple regex audit; many are short social posts. Manual annotation is required to separate:

- verified or official incident reports  
- unverified citizen reports  
- quoted third-party statements  
- warnings / “do not spread rumor”  
- denials / debunking  
- benign discussion (sympathy, news sharing without accusation)  

**Do not** treat keyword matches as positive training labels.

---

## 4. Licence and permitted use

| Source | Licence evidence in local package | Notes |
|---|---|---|
| India–Nepal corpus `README.md` | **No** embedded `LICENSE` or CC statement in the checked files | Document tasks/schema only |
| Stated published licence (project direction) | **CC BY 4.0** attributed in humanitarian experiment README | Not verified inside zip; keep DOI/citation when obtained |
| CrisisNLP / CrisisLex upstream | Referenced in corpus README | May impose **additional** terms on tweet redistribution |
| Future misuse annotations | N/A | New annotations are derivative; need rights to underlying text **and** annotator agreement |

**Permitted use for this audit:** Local research inspection.  
**Not verified:** Redistribution of models trained on tweets, publishing annotated subsets, or commercial use.

---

## 5. Language and suitability

- **Language:** English/lowercased noisy Twitter-style text (India + Nepal disasters). Limited code-mixing may appear; not systematically labelled.
- **Domain:** Historical earthquake/flood events (Nepal 2015-era CrisisLex lineage + synthetic/matched India collections per README).
- **Suitability for misuse classifier:** **Low** without new labels — wrong label space, weak keyword signal, no veracity/discourse tags.
- **Suitability as unlabeled pool for human annotation:** **Medium** — large volume, disaster-focused, but bias toward humanitarian and informational content; misuse positives will be **rare** (requires active sampling).

---

## 6. Separation from humanitarian classifier

| | `disaster_classifier` | `disaster_misuse_classifier` (this track) |
|---|---|---|
| Goal | Humanitarian Help / disaster type | Content-level misuse **concerns** for review |
| Labels | Help, flood/earthquake, etc. | **To be created** — misuse schema |
| Food and Medicine | Humanitarian class | **Must not** be used as proxy for criminality or urgency enforcement |
| Product hook | Future optional categorization | Future optional **review hints** only; never block delivery |

---

## 7. Evaluation plan (when labels exist)

Use the same discipline as the humanitarian experiment, adapted for misuse:

1. **Metrics:** per-class precision, recall, F1; macro-F1 and weighted-F1; confusion matrices.  
2. **Primary generalisation:** leave-one-**event** or leave-one-**source** hold-out (not random accuracy alone).  
3. **Secondary:** stratified random split for in-distribution monitoring only.  
4. **Calibration:** report precision at fixed review-queue thresholds (human review, not auto-block).  
5. **Error analysis:** redacted examples with discourse type — never present predictions as guilt.  
6. **Baseline (only after labels):** `compact_word`-style **word TF-IDF (1–2), 8k features + balanced Logistic Regression** — same family as size comparison winner; no char/hash until data proves need.

---

## 8. Recommendation

### Do not train a misuse model yet.

### Do next: build a fairly large human-labelled research dataset

See:

- `docs/ANNOTATION_SCHEMA.md` — label definitions, discourse metadata, negative classes  
- `docs/DATA_COLLECTION_PLAN.md` — target size (**12,000–20,000** messages), sampling, adjudication, licence  

Suggested workflow:

1. Obtain explicit **CC BY 4.0** (or stricter) citation/DOI for the India–Nepal corpus and confirm annotator rights.  
2. **Active sampling** from local corpus + optional rumour/veracity corpora for **candidate** messages (keyword + stratified random), not auto-labels.  
3. **Two-pass annotation:** primary label + discourse type; adjudication on disagreements and rare classes.  
4. Hold out **entire events/sources** for evaluation.  
5. Only then run a lightweight baseline under this directory (separate from `disaster_classifier`).

---

## 9. Reproduce this audit

```bash
cd experiments/disaster_misuse_classifier
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python scripts/audit_local_corpus.py --config configs/audit.yaml
```
