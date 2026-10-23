from __future__ import annotations

import json
import sys
from pathlib import Path
from unittest.mock import patch

TESTER_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TESTER_ROOT))

from neyra_tester.batch import run_batch


@patch("neyra_tester.batch.predict_all")
def test_run_batch_jsonl(mock_predict, tmp_path):
    from neyra_tester.inference import ModelBundle

    mock_predict.return_value = {"disaster_type": {}, "misuse_advisory": {}}
    bundle = ModelBundle(None, None, {}, ())
    inp = tmp_path / "in.jsonl"
    inp.write_text(
        json.dumps({"message_text": "hello", "expected_label": "benign_emergency_information"}) + "\n",
        encoding="utf-8",
    )
    out = run_batch(bundle, inp, tmp_path / "out.jsonl")
    assert out.exists()
    row = json.loads(out.read_text(encoding="utf-8").strip())
    assert row["message_text"] == "hello"
    assert row["expected_label"] == "benign_emergency_information"
