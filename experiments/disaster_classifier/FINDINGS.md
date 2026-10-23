# Experiment findings (local runs)

Generated artifacts live under gitignored `outputs/` and `models/`. This note summarises comparable runs with `random_seed=42`, stratified 70/15/15, TF-IDF word(1–2)+char(3–5), Logistic Regression (`class_weight=balanced`). **No tuning against held-out test.**

## Licence / attribution

- Corpus title: *Hierarchical Disaster Tweet Classification Corpus (India + Nepal)* (`INDIA-NEPAL-DISASTER-TWEETS`).
- Treated as **CC BY 4.0** for this experiment (see experiment `README.md`).
- Local package did **not** embed a `LICENSE` file; CrisisNLP/CrisisLex provenance may add third-party restrictions.

## Task 3 — Help classes (prior + diagnostic)

| Protocol | Macro-F1 | Notes |
|---|---:|---|
| Random held-out test | **0.878** | Mixes all three event groups in train and test |
| Hold out `india_earthquake` | 0.737 | Still well above majority (~0.12) |
| Hold out `india_flood` | 0.639 | Domain / hazard shift |
| Hold out `nepal_earthquake` | **0.419** | Largest drop |

### Why random ≫ event hold-out (evidence)

1. **Not primarily duplicate leakage.** Exact cleaned-text overlap across events = 0. Approximate train↔test near-duplicate rate ≈ **1%** after exact-dedupe. Within/across-event near-dup pair rates are negligible in samples.
2. **Random splits mix events.** Train/test each contain ~42% India EQ / ~33% India flood / ~25% Nepal EQ, so event-specific vocabulary is shared across sides of the random split.
3. **Heterogeneous difficulty inside the random test** (same model, sliced by test-row event):
   - India flood subset: macro-F1 ≈ **0.999**
   - India earthquake subset: macro-F1 ≈ **0.951**
   - Nepal earthquake subset: macro-F1 ≈ **0.585**
4. **Event-specific vocabulary** appears in distinctive-term analysis (e.g. `nepal`/`kathmandu`/`everest` vs flood terms like `monsoon`/`inundation`/`cwc`). Holding out Nepal removes those cues from training.
5. **Class distributions** are similar across events (Other useful information dominant everywhere); the gap is better explained by **domain/style shift** than by label-prior mismatch alone.

**Conclusion:** The 0.878 random-split score is a valid in-distribution estimate for a **pooled multi-event** corpus. It is **not** a reliable estimate of performance on a **new, unseen disaster event**. With only three coarse event groups, leave-one-event-out is informative but statistically limited.

## Task 2 — non-disaster / flood / earthquake

Run: `outputs/20261023T115519Z_task2_type/`

| Class | Precision | Recall | F1 | Support |
|---|---:|---:|---:|---:|
| non_disaster | 0.948 | 0.937 | 0.943 | 1334 |
| flood | 0.984 | 0.974 | 0.979 | 1056 |
| earthquake | 0.957 | 0.973 | 0.965 | 1588 |

- Macro-F1 **0.962** · Weighted-F1 **0.961** · Majority baseline macro-F1 **0.190**
- Confusion matrix (rows=true, cols=pred; order non_disaster, flood, earthquake):

```text
[[1250,   17,   67],
 [  25, 1029,    2],
 [  43,    0, 1545]]
```

- Source hold-out (CrisisLex-type file ↔ India file): macro-F1 ≈ **0.65–0.66** — same pattern as Task 3: random in-distribution scores overstate cross-collection generalisation.
- Filtering removed **2,518** exact duplicate texts before split (more duplication than Task 3).

## Model sizes (local)

- Task 3: ~2.73 MB joblib  
- Task 2: ~2.96 MB joblib  

## Recommended next experiment

**Event-robust evaluation + lighter features for Neyra**, still offline and not wired to mesh:

1. Treat **leave-one-event-out** (Task 3) and **leave-one-source-out** (Task 2) as primary reported metrics; keep random split as secondary.
2. Probe why India flood Help labels are nearly separable in-distribution (template / lexical artifacts) without changing labels.
3. Try a **smaller** vocabulary / hashed n-grams baseline aimed at on-device size, evaluated with the same event-holdout protocol.
4. Optionally add Task 1 binary as a cheap cascade gate — still no Android integration.

Do **not** interpret Food and Medicine as validated urgency; do **not** claim criminality/terrorism detection.

## Size vs generalisation (run `20261023T120058Z_size_comparison`)

**Baselines:** `baseline_full` = word(1–2)+char(3–5) TF-IDF + LR (~3.3–3.5 MB joblib).  
**Primary metric:** mean leave-one-group-out macro-F1 (Task 3 events / Task 2 sources). Random-split F1 shown only as secondary.

| Variant | Task 3 LOO mean | Task 3 size | Task 2 LOO mean | Task 2 size |
|---|---:|---:|---:|---:|
| baseline_full | 0.599 | 3.35 MB | 0.655 | 3.46 MB |
| word_only | 0.609 | 1.39 MB | 0.669 | 1.79 MB |
| compact_word (8k) | **0.606** | **0.62 MB** | **0.670** | **0.48 MB** |
| reduced_vocab (8k+4k) | 0.591 | 0.90 MB | 0.661 | 0.70 MB |
| hash_word_8k | 0.537 | 0.31 MB | 0.616 | 0.19 MB |
| hash_word_char | 0.551 | 0.47 MB | 0.659 | 0.28 MB |

- Dropping char n-grams **did not hurt** hold-out generalisation; it slightly **improved** mean LOO F1 on both tasks while cutting size ~5× and latency ~8× vs baseline.
- `HashingVectorizer` models are smallest but **fail** the ≥95% of baseline LOO-mean rule on Task 3 (and barely on Task 2 for 8k).
- Peak Python alloc during predict (tracemalloc): ~2.5 MB baseline vs ~0.4 MB `compact_word` (relative only).

**Recommendation (disaster classification only):** use **`compact_word`** — 8k word TF-IDF (1–2) + LR. Smallest variant meeting ≥95% of `baseline_full` **mean** LOO macro-F1 on Task 3 (~0.606 vs 0.599 baseline), ~**635 KB** on disk, ~**0.03 ms/text** on this Mac. For Task 2 alone, `hash_word_char` is smaller (~290 KB) with similar LOO mean; for one shared recipe across tasks, prefer `compact_word`.

Full tables: `outputs/20261023T120058Z_size_comparison/size_comparison_report.md`.
