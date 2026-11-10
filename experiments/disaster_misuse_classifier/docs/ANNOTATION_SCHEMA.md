# Annotation schema — disaster-related misuse (research)

Human review labels for **message content**, not senders. Annotations support **possible concern** flags for triage, not criminal findings.

## Design principles

1. **Multi-label** primary concerns allowed; one **primary** label for metrics if needed.  
2. Separate **content concern** from **discourse type** (how the message presents information).  
3. **None / benign disaster talk** is a first-class label — expect high prevalence.  
4. Annotators must **not** infer guilt, identity, or verified truth from text alone.  
5. When uncertain, use `unclear_needs_context` rather than forcing a misuse class.

---

## A. Primary content concern (multi-label)

| ID | Label | Definition (annotate if message **describes, promotes, or instructs**) |
|---|---|---|
| `M0` | **none_benign** | Normal disaster discussion: sympathy, donations, logistics, news sharing without misconduct focus. |
| `M1` | **violence_threat** | Threats of violence, calls to attack, celebration of harm **in disaster context**. |
| `M2` | **looting_theft_exploitation** | Looting, theft, extortion, or exploitation of victims **as conduct** (not merely mentioning the word in news). |
| `M3` | **impersonation_fraud** | Fake NGOs, fake relief workers, scam donation links, impersonation of officials/aid groups. |
| `M4` | **coercion_exploitation** | Forcing people, blackmail, demanding payment for rescue/aid access. |
| `M5` | **rescue_interference** | Obstructing roads/rescue, instructing others to block aid convoys or emergency access. |
| `M6` | **harmful_misinformation** | False disaster instructions that could cause harm (fake shelters, dangerous “cures”, wrong evacuation). Distinct from political rumour. |
| `M7` | **unclear_needs_context** | Cannot code without external context; exclude from primary metrics or route to adjudication. |

**Not in scope:** general politics, unrelated crime, terrorism ideology without disaster link, sender demographics.

---

## B. Discourse type (single label — metadata)

How the message frames the content (orthogonal to whether the underlying claim is true):

| ID | Label | When to use |
|---|---|---|
| `D0` | **personal_statement** | First-person claim or opinion without clear attribution. |
| `D1` | **reported_unverified** | “Reports say…”, citizen eyewitness, unverified spread. |
| `D2` | **quoted_third_party** | Quoted tweet, RT, “according to X”. |
| `D3` | **official_or_media** | Attributes claim to police, government, established media handle. |
| `D4` | **warning_or_debunk** | Warning not to believe rumour, hoax debunk, safety correction. |
| `D5` | **denial_refutation** | Explicit denial of an incident or accusation. |
| `D6` | **hypothetical_or_conditional** | “If looters come…”, hypothetical scenario. |

**Critical:** `D4`/`D5` often accompany **benign** or **protective** intent — do **not** auto-map to misuse.

---

## C. Optional weak metadata (not for automated enforcement)

| Field | Values | Use |
|---|---|---|
| `language` | `en`, `hi`, `mixed`, `other` | Stratify evaluation |
| `event_id` | e.g. `nepal_eq_2015`, `india_flood_generic` | Grouped hold-out |
| `source_collection` | file/dataset name | Leakage control |
| `annotator_confidence` | 1–3 | Filter training rows |
| `contains_url` | bool | Feature analysis only |

---

## D. Adjudication rules (summary)

- If message **only** repeats news headline about looting **without endorsement** → `M2` only if framing encourages looting; else `M0` + `D2` or `D1`.  
- “Pray for Nepal” + disaster hashtags → `M0`.  
- “Don’t believe looting rumours” → `M0` + `D4`.  
- Scam donation link → `M3` (+ `D0` or `D1`).  
- **Never** label `M1`–`M5` solely because a keyword appears.

---

## E. Inter-annotator agreement targets

- Cohen’s κ ≥ **0.55** on primary concern (macro-averaged) before training.  
- Mandatory adjudication on all `M1`–`M5` positives and a 10% sample of `M0`.
