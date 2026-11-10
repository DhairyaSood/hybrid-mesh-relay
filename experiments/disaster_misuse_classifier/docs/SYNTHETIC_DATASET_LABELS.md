# Synthetic misuse-training labels (programmatic ground truth)

Labels are assigned from the **generator scenario type**, not from keyword classifiers on the final text.

| Label | Definition | Generator intent |
|-------|------------|------------------|
| `benign_emergency_information` | Factual or instructional disaster guidance (shelters, evacuation routes, helplines, supply points, missing-person coordination) **without** promoting misconduct. | Citizen or volunteer coordination, official-style reminders (fictional). |
| `direct_threat_or_intimidation` | First-person or direct second-person **intent to harm, intimidate, or exclude** others in a disaster setting (vague wording only; no tactical harm instructions). | Simulated hostile messages in camps/queues/aid lines. |
| `coercion_or_extortion` | Pressure to pay, surrender supplies, or comply **to obtain aid, rescue, or safety** (disaster-linked exploitation). | Short coercion scenarios at distribution points. |
| `possible_relief_worker_impersonation_or_fraud` | Claims of being relief/NGO/government aid **combined with suspicious payment or credential cues** (no real account numbers or step-by-step scams). | Fake volunteer/NGO outreach patterns. |
| `report_or_warning_about_misconduct` | **Reporting or warning about** theft, scams, threats, or chaos **without endorsing** the conduct (includes debunks and safety warnings). | Eyewitness reports, “do not trust” warnings, rumour corrections. |
| `quoted_or_historical_threat` | Threatening or violent wording **clearly attributed** to another party (quote, RT-style, “officials said”, past incident). | News-style quotes, relayed speech—not speaker’s own threat. |
| `ambiguous_needs_review` | Mixed signals, heavy code-switch fragmentation, or unclear intent where neither benign coordination nor a specific concern is definitive. | Partial messages, contradictory clauses, unclear pronouns. |

**Not claimed:** These strings are fiction for offline ML experiments; performance on real mesh traffic is unknown until validated separately.
