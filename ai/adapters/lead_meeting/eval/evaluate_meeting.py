"""
evaluate_meeting.py
===================
Measures whether the extraction adapter actually reads meeting notes.

eval_loss cannot answer that. Most of each target is JSON scaffolding -- braces,
field names, quoting -- that the model reproduces perfectly within a few dozen
steps, so loss falls and token accuracy climbs past 99% while the five values
that matter could still be wrong. Worse, a model that ignored the notes entirely
and always predicted the most common value per field would score around 40% on
raw field accuracy and look like it had learned something.

So this measures per-field accuracy against the held-out split, and compares it
to that majority-class floor. Anything not clearly above the floor means the
model learned the label distribution rather than the language.

It also reports the downstream consequence -- how far the resulting meeting
score and priority band land from the truth -- because a wrong signal that
shifts the score by 5 points matters far less than one that moves a lead across
a band boundary.

Run (after training):
    python adapters/lead_meeting/eval/evaluate_meeting.py
    python adapters/lead_meeting/eval/evaluate_meeting.py --limit 30 --adapter adapters/lead_meeting/weights/checkpoint-150
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import prompt_format as fmt  # noqa: E402
from train import (  # noqa: E402
    DATA_PATH, EVAL_FRACTION, MODEL_PATH, OUTPUT_DIR, SEED, _is_valid, apply_chat_template,
)


def load(adapter: Path):
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    # The same template training installed. Inferring through the stock template
    # would infer on a different prompt than the adapter ever saw.
    apply_chat_template(tokenizer)

    base = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True),
        device_map="auto", trust_remote_code=True, local_files_only=True)
    model = PeftModel.from_pretrained(base, str(adapter))
    model.eval()
    return tokenizer, model


def held_out_rows(limit):
    """The same held-out slice train_meeting.py used -- same seed, same shuffle.

    Evaluating on rows the adapter trained on would measure memorisation.
    """
    from datasets import load_dataset

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train").filter(_is_valid)
    split = dataset.train_test_split(test_size=EVAL_FRACTION, seed=SEED, shuffle=True)
    rows = list(split["test"])
    return rows[:limit] if limit else rows


def generate(tokenizer, model, notes: str) -> str:
    prompt = tokenizer.apply_chat_template(
        fmt.build_messages(notes), tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt")
    inputs = {k: v.to(model.device) for k, v in inputs.items()}
    prompt_len = inputs["input_ids"].shape[-1]
    with torch.no_grad():
        out = model.generate(
            **inputs, max_new_tokens=200, do_sample=False,
            repetition_penalty=1.02,
            eos_token_id=tokenizer.eos_token_id, pad_token_id=tokenizer.eos_token_id)
    return tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--limit", type=int, default=None)
    args = parser.parse_args()

    if not (args.adapter / "adapter_config.json").exists():
        print(f"No adapter at {args.adapter}. Train first, or pass --adapter.")
        return 1

    rows = held_out_rows(args.limit)
    print(f"Evaluating {args.adapter.name} on {len(rows)} held-out rows...\n")
    tokenizer, model = load(args.adapter)

    # The floor a model that ignores the notes would reach: always answer the
    # most common value for each field. Computed from the eval split itself, so
    # it is the real floor for this data rather than a theoretical one.
    majority = {
        field: collections.Counter(r["signals"][field] for r in rows).most_common(1)[0]
        for field in fmt.FIELD_ORDER
    }

    correct = collections.Counter()
    confusion = collections.defaultdict(collections.Counter)
    exact = json_valid = repaired = 0
    score_errors, band_matches = [], 0

    for index, row in enumerate(rows, 1):
        expected = row["signals"]
        reply = generate(tokenizer, model, row["meeting_notes"])
        raw = fmt.extract(reply)
        if raw is None:
            print(f"  [{index:3d}/{len(rows)}] no JSON")
            continue
        json_valid += 1

        actual, repairs = fmt.coerce(raw)
        if repairs:
            repaired += 1

        hits = 0
        for field in fmt.FIELD_ORDER:
            if actual[field] == expected[field]:
                correct[field] += 1
                hits += 1
            else:
                confusion[field][f"{expected[field]}->{actual[field]}"] += 1
        if hits == len(fmt.FIELD_ORDER):
            exact += 1

        expected_score = fmt.meeting_score(expected)
        actual_score = fmt.meeting_score(actual)
        score_errors.append(abs(actual_score - expected_score))
        band_matches += fmt.priority_for(expected_score) == fmt.priority_for(actual_score)

        print(f"  [{index:3d}/{len(rows)}] {hits}/5 fields  "
              f"score {actual_score:3d} vs {expected_score:3d}")

    n = len(rows)
    print("\n" + "=" * 66)
    print(f"{'json_valid':24s} {json_valid:4d}/{n}  {json_valid / n:6.1%}")
    print(f"{'needed repair':24s} {repaired:4d}/{n}  {repaired / n:6.1%}")
    print(f"{'all 5 fields correct':24s} {exact:4d}/{n}  {exact / n:6.1%}")
    if score_errors:
        mae = sum(score_errors) / len(score_errors)
        print(f"{'meeting score MAE':24s} {mae:6.2f} points out of 100")
        print(f"{'exact score match':24s} "
              f"{sum(1 for e in score_errors if e == 0):4d}/{len(score_errors)}")
        print(f"{'priority band match':24s} {band_matches:4d}/{len(score_errors)}  "
              f"{band_matches / len(score_errors):6.1%}")
    print("=" * 66)

    print("\nPer-field accuracy (vs majority-class floor):")
    below_floor = []
    for field in fmt.FIELD_ORDER:
        accuracy = correct[field] / n
        floor_value, floor_count = majority[field]
        floor = floor_count / n
        flag = ""
        if accuracy <= floor + 0.05:
            flag = "  <- AT OR BELOW FLOOR"
            below_floor.append(field)
        print(f"  {field:28s} {accuracy:6.1%}   floor {floor:5.1%} ({floor_value}){flag}")

    for field in fmt.FIELD_ORDER:
        if confusion[field]:
            worst = confusion[field].most_common(3)
            print(f"\n  {field} mistakes: " + ", ".join(f"{k} x{v}" for k, v in worst))

    if below_floor:
        # The failure this whole script exists to catch: a model that learned
        # the label distribution rather than how to read a meeting.
        print(f"\nWARNING: {below_floor} no better than always guessing the "
              f"most common value. The model is not reading the notes for these.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
