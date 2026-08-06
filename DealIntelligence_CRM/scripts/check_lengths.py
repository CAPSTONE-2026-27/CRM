"""
check_lengths.py
================
Reports the token-length distribution of the training set against train.py's
max_length, and refuses to pass if anything would truncate.

Worth a dedicated script because truncation is invisible. A clipped target does
not raise; it teaches the model to stop mid-object, and that surfaces much later
as "the model emits invalid JSON" — which sends you looking at the prompt, the
decoding parameters and the adapter, in roughly that order, before you think to
check whether the data ever fit.

Run after changing the state schema, the system prompt, or the note templates:
    python scripts/check_lengths.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from transformers import AutoTokenizer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from train import DATA_PATH, MODEL_PATH, formatting_func  # noqa: E402

MAX_LENGTH = 2048  # keep in step with build_training_config()


def main() -> int:
    if not DATA_PATH.exists():
        print(f"No dataset at {DATA_PATH}. Run scripts/generate_dataset.py first.")
        return 1

    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True
    )

    with DATA_PATH.open(encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle]

    lengths = sorted(len(tokenizer(formatting_func(row)).input_ids) for row in rows)
    if not lengths:
        print("Dataset is empty.")
        return 1

    def percentile(p: float) -> int:
        return lengths[min(len(lengths) - 1, int(len(lengths) * p))]

    over = [n for n in lengths if n > MAX_LENGTH]
    print(f"rows       : {len(lengths)}")
    print(f"max_length : {MAX_LENGTH}")
    print(f"tokens     : min={lengths[0]}  p50={percentile(0.50)}  "
          f"p95={percentile(0.95)}  p99={percentile(0.99)}  max={lengths[-1]}")
    print(f"headroom   : {MAX_LENGTH - lengths[-1]} tokens below the cap")

    if over:
        print(f"\nFAIL: {len(over)} row(s) exceed max_length and would be truncated.")
        print("Raise max_length in train.py, or shorten the prompt/notes.")
        return 1

    print("\nOK: nothing truncates.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
