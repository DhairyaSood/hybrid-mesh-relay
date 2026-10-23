# Offline misuse-warning rules engine (prototype)

## Purpose

Surface **optional, non-blocking** UI hints when message **text** matches patterns associated with content-level concerns (threats, coercion, possible relief fraud). This is **not** criminal adjudication and **not** a machine-learning misuse classifier.

## Non-goals

- No blocking, deletion, delay, or modification of mesh delivery (`MeshEngine` path untouched).
- No sender identity, contact graph, or message-history features.
- No cloud inference.

## Architecture

```text
message text
    → normalize (lowercase, collapse whitespace, strip URLs)
    → discourse heuristics (report / quote / warning-debunk / negation window)
    → category rule matchers (threat, coercion, impersonation)
    → MisuseWarningResult (may_be_concern + human-readable uncertainty text)
```

## Discourse handling (heuristic)

| Signal | Effect |
|--------|--------|
| Leading negation (`don't`, `do not`, `never`, `not a`) near concern phrase | Suppress or downgrade category hit |
| Warning/debunk (`hoax`, `false rumor`, `don't share`, `debunk`) | Suppress — protective intent |
| Report framing (`reports say`, `allegedly`, `unconfirmed`, `RT @`) | Downgrade to `informational` unless endorsement verbs present |
| Quoted block (`"..."` or `‘...’`) | Evaluate inner quote; if outer frame is denial/refutation, suppress |

Rules use **ordered checks**: debunk/report frames run before positive threat/coercion/impersonation patterns.

## Output contract

```json
{
  "may_warrant_review": false,
  "categories": [],
  "user_message": "",
  "confidence": "none|low|medium"
}
```

`user_message` must use uncertain language (“may”, “possible”, “could”) and refer to **message content**, never the sender.

## Android integration (future)

See `docs/ANDROID_INTEGRATION.md`. Reference Kotlin port: `rules/reference/MisuseWarningEngine.kt` (not wired).

## Testing

Synthetic cases only in `test_misuse_warning_engine.py`. No accuracy claims for production.
