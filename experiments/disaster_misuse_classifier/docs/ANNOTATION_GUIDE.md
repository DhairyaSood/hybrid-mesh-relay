# Annotator guide — disaster misuse pilot (1,000 messages)

**Purpose:** Flag **message content** that may warrant **human review** in disaster contexts.  
**Not a purpose:** Decide if someone is a criminal, infiltrator, or bad actor.

Messages in Neyra must **always be delivered**; labels are for research and optional future review hints only.

---

## 1. What you are labelling

| Layer | Codes | Rule |
|---|---|---|
| **Content concern** | `M0`–`M7` (multi-select allowed) | What the message is **about** regarding possible misuse themes |
| **Discourse type** | `D0`–`D6` (pick **one**) | **How** the message presents information |

Always pick **one** `primary_content_label` for agreement (usually the strongest concern, or `M0` / `M7`).

**Confidence (1–3):** 1 = unsure, 2 = reasonable, 3 = clear.

---

## 2. Content labels (`M0`–`M7`) — quick reference

| Code | Label | Apply when |
|---|---|---|
| **M0** | none_benign | Sympathy, donations, logistics, neutral disaster news **without** promoting misconduct |
| **M1** | violence_threat | Threats, calls to attack, celebrating harm **in a disaster setting** |
| **M2** | looting_theft_exploitation | **Promoting or describing conduct** as acceptable/desired looting/theft/extortion — not neutral news |
| **M3** | impersonation_fraud | Fake NGOs/relief workers, scam donation links, impersonation of officials |
| **M4** | coercion_exploitation | Forcing people, blackmail, pay-for-rescue demands |
| **M5** | rescue_interference | Blocking rescue/aid routes, instructing others to obstruct emergency access |
| **M6** | harmful_misinformation | Dangerous false disaster instructions (wrong shelters, harmful “cures”, false evacuation) |
| **M7** | unclear_needs_context | Cannot code without outside context |

**If only `M0` applies:** select `M0` alone.  
**If `M1`–`M6` apply:** you may combine (e.g. `M3` + `M6`), but pick one **primary**.

---

## 3. Discourse labels (`D0`–`D6`) — distinguishing reports from threats

| Code | When to use | Common mistake |
|---|---|---|
| **D0** personal_statement | “I saw…”, opinion, no clear source | Confusing with verified fact |
| **D1** reported_unverified | “Reports say…”, rumour spread, eyewitness without official source | **Not** the same as endorsing the report |
| **D2** quoted_third_party | RT, quote marks, “according to @…” | News headline relay |
| **D3** official_or_media | Attributes to police, government, major media | Still may be `M0` if benign update |
| **D4** warning_or_debunk | “Don’t share this hoax”, safety correction | Often **`M0`** — protective intent |
| **D5** denial_refutation | “Looting rumours are false” | Often **`M0`** — denies misconduct claim |
| **D6** hypothetical_or_conditional | “If looters come we should…” | May be `M0` or concern depending on endorsement |

### Worked distinctions (patterns only — judge each message on its own)

1. **Direct threat (possible `M1`):** Imperative or intent to harm tied to disaster context — *not* merely quoting someone else’s threat without endorsement (use `D2` + judge content).  
2. **Report of violence/looting (`D1`/`D2`):** Describes alleged conduct **without** encouraging it → usually **`M0`** unless endorsing (`M2`/`M1`).  
3. **Quotation (`D2`):** Retweets or cites another party; label content based on **whether this message promotes** the quoted act.  
4. **Warning (`D4`):** Tells others **not** to believe or spread something → typically **`M0`**.  
5. **Denial (`D5`):** Says an incident **did not** happen → typically **`M0`** (discourse captures denial).  
6. **Rumour (`D1`):** Unverified claim spreading; misuse class only if claim **is** harmful instruction (`M6`) or **incites** conduct (`M1`/`M2`).  
7. **Benign discussion (`M0`):** Prayers, donations, infrastructure updates, sympathy.

**Never label someone a criminal.** Label **what the text says**, not who sent it.

---

## 4. Keyword hints in the UI

Some rows include `keyword_bucket_hint` from **automated retrieval**.  
That hint is **not** a correct label. Many keyword hits are news, sympathy, or debunking.

---

## 5. What not to collect

- Sender usernames, phone numbers, contact graphs  
- Private account metadata  
- Your guess about real-world identity  

Use `annotator_notes` only for **linguistic** ambiguity (max ~500 chars).

---

## 6. Adjudication rules (summary)

See `docs/ANNOTATION_SCHEMA.md` section D. When two annotators disagree, an adjudicator sets `adjudicated_*` fields.

---

## 7. Quiz (self-check before live annotation)

Answer without looking up codes:

1. “Please don’t believe the viral message about collapsed bridge, officials say it’s false.” → Discourse? Content?  
2. “RT @news: army deployed to earthquake zone.” → Discourse? Content?  
3. “Share this fake Red Cross link to donate” (scam link) → Discourse? Content?  

*Expected patterns:* (1) `D4` or `D5`, `M0`; (2) `D2`, `M0`; (3) `D0`/`D1`, `M3` (and possibly `M6` if harmful instructions).

Ask the project lead for a gold-key review before production annotation.
