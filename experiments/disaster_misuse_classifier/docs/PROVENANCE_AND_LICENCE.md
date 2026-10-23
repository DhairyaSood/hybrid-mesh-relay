# Provenance and licence — before sampling or storing message text

Read this **before** running `scripts/sample_pilot_pool.py` or distributing pilot files to annotators.

## Proposed source for the pilot (only source enabled in tooling)

| Source | Location | Intended use in pilot |
|---|---|---|
| India–Nepal Hierarchical Disaster Tweet Corpus | Local path in `configs/pilot_sampling.yaml` | 1,000-message research pilot pool |

**Not enabled by default:** external downloads, Neyra production messages, web scraping.

## Licence status (verified in this checkout)

| Item | Finding |
|---|---|
| `dataset/README.md` | Describes schema/tasks; **no embedded LICENSE file** |
| `dataset.zip` | Contains CSVs + README only |
| Stated published licence (project record) | **CC BY 4.0** — attribution required for redistribution of derivatives |
| CrisisNLP / CrisisLex upstream | Referenced in README; **additional terms may apply** to tweet redistribution |

## What CC BY 4.0 generally allows (not legal advice)

Typical CC BY 4.0 permits **sharing and adapting** with **attribution**. It does **not** automatically guarantee:

- Right to **re-publish raw tweet text** in public repos or papers without checking upstream Twitter/platform terms at collection time  
- Freedom from **third-party** CrisisLex/CrisisNLP restrictions bundled with the corpus  
- Use for **automated enforcement** against people (this project explicitly forbids that regardless)

## Storage policy for this experiment

| Location | Contents | Git |
|---|---|---|
| `templates/` | Empty schemas, Label Studio config | **Tracked** |
| `data/pilot/` | Pool with real message text, manifests, annotator exports | **Gitignored** |
| Public commits | No raw tweets, no annotator-filled exports | **Never** |

## Permission you should confirm before pilot annotation

1. **Corpus licence:** Obtain citation/DOI or licence file from the corpus publisher confirming **CC BY 4.0** (or document alternative terms).  
2. **Derivative annotations:** Confirm your institution allows storing **human labels + source text** locally for research under those terms.  
3. **Annotators:** NDA / data-handling agreement; **local-only** tooling unless a DPA exists with the vendor.  
4. **Publication:** If you publish examples, use **redacted** snippets only unless counsel confirms otherwise.

## Sampling script gate

`sample_pilot_pool.py` requires:

```bash
--i-accept-local-corpus-terms
```

This records **your** acknowledgement that you verified permissions for **local research annotation** — not an automated legal clearance.

## If permissions are insufficient

**Stop.** Do not sample, annotate, or share pools. Options:

- Contact corpus authors for written clarification + DOI  
- Annotate only **synthetic** or **consent-backed** Neyra test strings (separate track, not implemented here)  
- Use publicly licensed **metadata-only** exports if text redistribution is blocked
