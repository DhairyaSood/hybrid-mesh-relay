# Smallest safe path — Android integration (plan only)

## Constraints (unchanged)

- **Mesh delivery:** `MeshEngine` / `MessagingRepository` accept and forward messages without waiting on classifiers.
- **Misuse hints:** UI-only, uncertain copy, no sender profiling.
- **Humanitarian model:** optional **Help** tag suggestion — not urgency enforcement, not misuse.

## Current architecture (inspected)

| Component | Role |
|-----------|------|
| `MessagingManager` | Starts `MeshEngine`; BLE/Wi‑Fi transport |
| `MeshEngine` | Routing, ACKs, attachment transfer — **do not hook blocking logic here** |
| `MessagingRepository` | Persists `MessageRecordEntity` |
| `ConversationScreen` / `MessageDetailScreen` | Display thread — **safest first hook for hints** |

## Phase A — Rules-only misuse hint (recommended first)

1. Copy `rules/reference/MisuseWarningEngine.kt` → `android/app/src/main/java/.../safety/MisuseWarningEngine.kt` (or keep in module).
2. In `ConversationScreen` / message bubble composable, call `MisuseWarningEngine.analyze(body)` **after** message is stored and shown.
3. If `mayWarrantReview`, show a small `AssistChip` / `Text` with `userMessage` — dismissible, no send blocking.
4. Unit tests in `androidTest` mirroring `rules/test_misuse_warning_engine.py` synthetic strings.

**No** changes to `MeshEngine.onPacketReceived` ordering.

## Phase B — Humanitarian Help classifier (optional)

1. Train with `configs/compact_word.yaml`; export JSON via `export_mobile_bundle.py`.
2. Ship `assets/models/task3_help_compact.json` (gitignored until licence review; use CI secret or local build).
3. Add `HumanitarianHelpClassifier.kt`: tokenize + n-gram hash map lookup + score classes (no sklearn).
4. Show **non-blocking** chip: “Suggested topic: Food and Medicine” with disclaimer — **never** “urgent” or misuse wording.

## Phase C — Misuse ML (future)

Only after `MISUSE_ML_TRAINING_PLAN.md` gates pass. Same bundle format, separate asset and UI copy.

## What not to do

- Load `.joblib` on Android (unsupported).
- Run inference in mesh hot path or before `repository.insertIncoming`.
- Use Help labels as misuse proxies.
