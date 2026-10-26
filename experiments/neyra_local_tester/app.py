#!/usr/bin/env python3
"""Offline Gradio — disaster hybrid + humanitarian + misuse (canonical + experimental)."""

from __future__ import annotations

import os
from pathlib import Path

os.environ.setdefault("GRADIO_ANALYTICS_ENABLED", "False")
os.environ.setdefault("HF_HUB_OFFLINE", "1")

import gradio as gr

from neyra_tester.batch import run_batch
from neyra_tester.inference import MISUSE_LABELS, compare_expected, load_models, predict_all

DISCLAIMER = """
**Advisory only** — not verified events, official alerts, or proof of crime. Classifiers do **not** block delivery.
**DC-T2** = 3 disaster classes only. **Keywords** = heuristics. **MH-v1 / MU-v2 / MU-v3** = experimental where shown.
Scores are not calibrated probabilities.
"""


def _format_result(text: str, expected_misuse: str) -> str:
    if not text or not str(text).strip():
        return "Enter a non-empty message."
    try:
        bundle = load_models()
    except FileNotFoundError as e:
        return f"Model load error: {e}"
    out = predict_all(bundle, text)
    if "error" in out and len(out) == 1:
        return str(out["error"])

    lines = ["## Disaster — keyword baseline (heuristic)"]
    kw = out.get("disaster_hazard_keywords", {})
    lines.append(f"- **Primary hint:** `{kw.get('primary_hint')}`")
    for m in (kw.get("matches") or [])[:4]:
        lines.append(f"  - {m.get('label')}: `{m.get('evidence')}`")
    if kw.get("note"):
        lines.append(f"- {kw['note']}")

    lines.append("\n## Disaster type — DC-T2 (canonical)")
    dt = out.get("disaster_type_dc_t2", {})
    lines.append(f"- **Prediction:** `{dt.get('prediction')}`")
    lines.append(f"- **Supported:** {', '.join(dt.get('supported_labels') or [])}")
    adv = dt.get("advisory") or {}
    if adv.get("message"):
        lines.append(f"- ⚠️ {adv['message']}")
    for k, v in sorted((dt.get("model_scores") or {}).items(), key=lambda x: -x[1]):
        lines.append(f"  - {k}: {v:.4f}")

    mh = out.get("disaster_type_mh_v1_experimental", {})
    if not mh.get("error"):
        lines.append("\n## Disaster type — MH-v1 (experimental, synthetic)")
        lines.append(f"- **Prediction:** `{mh.get('prediction')}`")

    hy = out.get("disaster_hybrid_summary", {})
    if hy.get("messages"):
        lines.append("\n## Disaster hybrid summary")
        for msg in hy["messages"]:
            lines.append(f"- {msg}")

    lines.append("\n## Humanitarian topic — DC-T3 (canonical)")
    ht = out.get("humanitarian_topic", {})
    lines.append(f"- **Prediction:** `{ht.get('prediction')}`")
    lines.append(f"- {ht.get('topic_note', '')}")

    lines.append("\n## Misuse — MU-v1 (canonical + civic refinement)")
    mu = out.get("misuse_advisory", {})
    lines.append(f"- **Prediction:** `{mu.get('prediction')}`")
    if mu.get("raw_model_prediction") != mu.get("prediction"):
        lines.append(f"- **MU-v1 raw:** `{mu.get('raw_model_prediction')}` — {mu.get('refinement_note', '')}")

    for key, title in (
        ("misuse_advisory_v2_experimental", "MU-v2 (experimental)"),
        ("misuse_advisory_v3_experimental", "MU-v3 (experimental)"),
    ):
        block = out.get(key)
        if block:
            lines.append(f"\n## Misuse — {title}")
            lines.append(f"- **Prediction:** `{block.get('prediction')}`")

    cmp_ = compare_expected(mu.get("prediction"), expected_misuse)
    if cmp_:
        lines.append(f"\n**Manual check (MU-v1 display):** `{cmp_['expected']}` → {'match' if cmp_['match'] else 'mismatch'}")

    lines.append(f"\n---\n{DISCLAIMER}")
    return "\n".join(lines)


def build_ui() -> gr.Blocks:
    with gr.Blocks(title="Neyra local tester") as demo:
        gr.Markdown("# Neyra local model tester (offline)")
        gr.Markdown(DISCLAIMER)
        msg = gr.Textbox(label="Message", lines=5)
        expected = gr.Dropdown(choices=["(none)"] + MISUSE_LABELS, value="(none)", label="Expected misuse (optional)")
        out = gr.Markdown()
        gr.Button("Run classifiers", variant="primary").click(_format_result, [msg, expected], out)
        batch_file = gr.File(file_types=[".csv", ".jsonl"])
        batch_out = gr.Textbox(label="Batch result", interactive=False)
        gr.Button("Run batch").click(
            lambda f: run_batch(load_models(), Path(f.name)) if f else "Upload a file",
            batch_file,
            batch_out,
        )
    return demo


def main() -> None:
    build_ui().launch(server_name="127.0.0.1", share=False)


if __name__ == "__main__":
    main()
