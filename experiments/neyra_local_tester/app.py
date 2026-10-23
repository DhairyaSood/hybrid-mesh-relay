#!/usr/bin/env python3
"""Offline Gradio — MH-v1 disaster type (17 classes) + MU-v1 misuse."""

from __future__ import annotations

import os
from pathlib import Path

os.environ.setdefault("GRADIO_ANALYTICS_ENABLED", "False")
os.environ.setdefault("HF_HUB_OFFLINE", "1")

import gradio as gr

from neyra_tester.batch import run_batch
from neyra_tester.inference import MISUSE_LABELS, compare_expected, load_models, predict_all

DISCLAIMER = """
**Advisory only** — not verified incidents, official alerts, or proof of crime.
**MH-v1** uses 17 hazard labels (synthetic training). **MU-v1** uses 7 misuse categories (synthetic training).
Scores are model outputs, not calibrated probabilities.
"""

SAMPLES = [
    ("Fire", "Fire in the building, smoke on the third floor."),
    ("Flood", "Flood water has entered our village near the embankment."),
    ("Earthquake", "A strong earthquake damaged several buildings."),
    ("Cyclone", "Cyclone approaching the coast."),
    ("Fire drill", "The fire drill is tomorrow at school."),
    ("Help in school", "Help needed in school after the drill."),
    ("Coercive demand", "Send money now or we will hurt your family."),
    ("Quoted threat", "A witness reported that someone threatened the shopkeeper."),
]

CUSTOM_CSS = ".gradio-container { max-width: 960px !important; }"


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

    lines = ["## Disaster type (MH-v1, 17 classes)"]
    dt = out.get("disaster_type", {})
    if dt.get("error"):
        lines.append(f"- {dt['error']}")
    else:
        lines.append(f"- **Model:** {dt.get('model_version')}")
        lines.append(f"- **Prediction:** `{dt.get('prediction')}`")
        lines.append(f"- {dt.get('prediction_note', '')}")
        labels = dt.get("supported_labels") or []
        if labels:
            lines.append(f"- **All labels:** {', '.join(labels)}")
        lines.append("- **Scores (top 8):**")
        for k, v in list((dt.get("model_scores") or {}).items())[:8]:
            lines.append(f"  - {k}: {v:.4f}")

    lines.append("\n## Misuse advisory (MU-v1)")
    mu = out.get("misuse_advisory", {})
    if mu.get("error"):
        lines.append(f"- {mu['error']}")
    else:
        lines.append(f"- **Model:** {mu.get('model_version')}")
        lines.append(f"- **Prediction:** `{mu.get('prediction')}`")
        if mu.get("raw_model_prediction") and mu.get("raw_model_prediction") != mu.get("prediction"):
            lines.append(f"- **MU-v1 raw (before rules):** `{mu.get('raw_model_prediction')}`")
            if mu.get("refinement_note"):
                lines.append(f"- *{mu['refinement_note']}*")
        for k, v in mu.get("model_scores", {}).items():
            lines.append(f"  - {k}: {v:.4f}")

    cmp_ = compare_expected(mu.get("prediction"), expected_misuse)
    if cmp_:
        lines.append(f"\n**Manual check:** expected `{cmp_['expected']}` → {'match' if cmp_['match'] else 'mismatch'}")

    lines.append(f"\n---\n{DISCLAIMER}")
    return "\n".join(lines)


def _run_batch_file(file_obj, progress=gr.Progress()) -> str:
    if file_obj is None:
        return "Upload CSV or JSONL with message_text column."
    try:
        bundle = load_models()
    except FileNotFoundError as e:
        return str(e)
    path = Path(file_obj.name if hasattr(file_obj, "name") else file_obj)
    progress(0, desc="Batch inference…")
    try:
        return f"Wrote {run_batch(bundle, path)} (local only)."
    except Exception as e:
        return f"Batch failed: {type(e).__name__}: {e}"


def build_ui() -> gr.Blocks:
    theme = gr.themes.Base(primary_hue="slate", neutral_hue="gray").set(
        body_background_fill="#1a1a1e",
        body_text_color="#e8e8ec",
        block_background_fill="#242428",
    )
    with gr.Blocks(title="Neyra — disaster & misuse", theme=theme, css=CUSTOM_CSS) as demo:
        gr.Markdown("# Neyra — disaster type (17-class) + misuse (offline)")
        gr.Markdown(DISCLAIMER)
        msg = gr.Textbox(label="Message", lines=5, placeholder="English, Devanagari, or Hinglish…")
        with gr.Row():
            run_btn = gr.Button("Run classifiers", variant="primary")
            clear_btn = gr.Button("Clear")
        expected = gr.Dropdown(
            choices=["(none)"] + MISUSE_LABELS,
            value="(none)",
            label="Expected misuse label (optional)",
        )
        out = gr.Markdown()
        run_btn.click(_format_result, inputs=[msg, expected], outputs=out)
        clear_btn.click(lambda: ("", "(none)", ""), outputs=[msg, expected, out])
        gr.Markdown("### Samples")
        for title, sample_text in SAMPLES:
            gr.Button(title).click(lambda t=sample_text: t, outputs=msg)
        batch_file = gr.File(file_types=[".csv", ".jsonl"])
        batch_btn = gr.Button("Run batch")
        batch_out = gr.Textbox(interactive=False)
        batch_btn.click(_run_batch_file, inputs=batch_file, outputs=batch_out)
    return demo


def main() -> None:
    build_ui().launch(server_name="127.0.0.1", share=False, show_error=True)


if __name__ == "__main__":
    main()
