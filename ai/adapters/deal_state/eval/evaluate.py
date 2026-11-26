"""
evaluate.py
===========
Measures whether the adapter learned the task, which eval_loss cannot tell you.

Most of a target here is structural JSON — braces, field names, quoting — that
the model predicts almost perfectly from the first few steps. That inflates
token accuracy and depresses eval_loss regardless of whether the *business
reading* is right, so a run can look converged while getting every field wrong.

The metrics below score what actually matters:

  json_valid          did it emit a parseable object at all
  scoreable           does coerce_state() need zero repairs — i.e. would the
                      XGBoost scorer accept it untouched
  exact_state         all 17 fields correct
  field_accuracy      per-field correctness, so a systematically bad field is
                      visible instead of averaged away
  changed_recall      of the fields that SHOULD have moved, how many did
  unchanged_precision of the fields that should NOT have moved, how many stayed

By default the state is scored as server/main.py serves it: coerced, then the
four derived numerics recomputed by fmt.apply_derived_fields(). --raw scores
the adapter's own values for those fields instead, which is what to watch when
retraining.

The last two are the real test. A model that copies the previous state verbatim
scores ~90% field accuracy — most fields do not move in a given meeting — while
being useless. changed_recall catches exactly that: it would be 0.

Two test sets:
  heldout    the eval journeys in data/train.jsonl (unseen phrasings)
  realistic  eval/realistic.jsonl -- hand-written meeting notes in rep shorthand,
             each with a previous state and the state it should produce

Results go to eval/results_<set>.json.

Run (after training):
    python adapters/deal_state/eval/evaluate.py
    python adapters/deal_state/eval/evaluate.py --set realistic
    python adapters/deal_state/eval/evaluate.py --limit 40 --adapter adapters/deal_state/weights/checkpoint-150
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import prompt_format as fmt  # noqa: E402
from train import DATA_PATH, MODEL_PATH, OUTPUT_DIR, _is_valid, split_dataset  # noqa: E402

EVAL_DIR = Path(__file__).resolve().parent
REALISTIC_PATH = EVAL_DIR / "realistic.jsonl"


def load(adapter_path: Path):
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    fmt.apply_chat_template(tokenizer)

    base = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            bnb_4bit_use_double_quant=True,
        ),
        device_map="auto",
        trust_remote_code=True,
        local_files_only=True,
    )
    model = PeftModel.from_pretrained(base, str(adapter_path))
    model.eval()
    return tokenizer, model


def held_out_rows(limit: int | None):
    """The same held-out slice train.py evaluated on — same seed, same split.

    Evaluating on rows the adapter trained on would measure memorisation.
    """
    from datasets import load_dataset

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train").filter(_is_valid)
    rows = list(split_dataset(dataset)[1])
    return rows[:limit] if limit else rows


def realistic_rows(limit: int | None):
    """eval/realistic.jsonl, in the same `messages` shape as a training row."""
    rows = [json.loads(line) for line in REALISTIC_PATH.open(encoding="utf-8") if line.strip()]
    rows = [{"id": r["id"], "messages": fmt.build_messages(r["previous_state"], r["meeting_notes"],
                                                           fmt.render_state(r["expected_state"]))}
            for r in rows]
    return rows[:limit] if limit else rows


def same_value(field: str, a, b) -> bool:
    """Field equality as the scorer sees it. main_objections becomes one
    indicator column per token, so the order they are listed in is irrelevant."""
    if field == "main_objections":
        return set(str(a).split("; ")) == set(str(b).split("; "))
    return str(a) == str(b)


def served_state(messages, raw: dict, raw_numerics: bool = False) -> tuple:
    """(previous, state, repairs) exactly as /v1/deal-state would produce them."""
    previous, _ = fmt.coerce_state(fmt.extract_state(messages[1]["content"]))
    state, repairs = fmt.coerce_state(raw)
    if not raw_numerics:
        state, _ = fmt.apply_derived_fields(previous, state)
    return previous, state, repairs


def generate(tokenizer, model, messages) -> str:
    prompt = tokenizer.apply_chat_template(
        messages, tokenize=False, add_generation_prompt=True
    )
    inputs = tokenizer(prompt, return_tensors="pt")
    inputs = {k: v.to(model.device) for k, v in inputs.items()}
    prompt_len = inputs["input_ids"].shape[-1]
    with torch.no_grad():
        out = model.generate(
            **inputs,
            max_new_tokens=768,
            do_sample=False,
            repetition_penalty=1.02,
            eos_token_id=tokenizer.eos_token_id,
            pad_token_id=tokenizer.eos_token_id,
        )
    return tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adapter", type=Path, default=OUTPUT_DIR)
    parser.add_argument("--limit", type=int, default=None,
                        help="evaluate only the first N held-out rows")
    parser.add_argument("--raw", action="store_true",
                        help="score the adapter's own derived numerics instead of the "
                             "recomputed values the server returns")
    parser.add_argument("--set", dest="test_set", choices=["heldout", "realistic"], default="heldout")
    args = parser.parse_args()

    if not (args.adapter / "adapter_config.json").exists():
        print(f"No adapter at {args.adapter}. Train first, or pass --adapter.")
        return 1

    rows = held_out_rows(args.limit) if args.test_set == "heldout" else realistic_rows(args.limit)
    print(f"Evaluating {args.adapter.name} on {len(rows)} {args.test_set} rows...\n")

    tokenizer, model = load(args.adapter)

    totals = defaultdict(int)
    field_correct = defaultdict(int)
    changed_hit = changed_total = 0
    unchanged_hit = unchanged_total = 0
    failures = []
    misses = []

    for index, row in enumerate(rows, 1):
        messages = row["messages"]
        previous = fmt.extract_state(messages[1]["content"])
        expected = json.loads(messages[2]["content"])

        reply = generate(tokenizer, model, messages[:2])
        raw = fmt.extract_state(reply)

        if raw is None:
            failures.append((index, "no JSON", reply[:120]))
            print(f"  [{index:3d}/{len(rows)}] no JSON")
            continue
        totals["json_valid"] += 1

        _, actual, repairs = served_state(messages, raw, raw_numerics=args.raw)
        if not repairs:
            totals["scoreable"] += 1

        if all(same_value(f, actual[f], expected[f]) for f in fmt.FIELD_ORDER):
            totals["exact_state"] += 1

        for field in fmt.FIELD_ORDER:
            same = same_value(field, actual[field], expected[field])
            field_correct[field] += same
            should_change = not same_value(field, previous[field], expected[field])
            if should_change:
                changed_total += 1
                changed_hit += same
            else:
                unchanged_total += 1
                unchanged_hit += same

        wrong = {f: {"expected": expected[f], "actual": actual[f]}
                 for f in fmt.FIELD_ORDER if not same_value(f, actual[f], expected[f])}
        if wrong:
            misses.append({"id": row.get("id", index), "wrong": wrong})
        print(f"  [{index:3d}/{len(rows)}] "
              f"{'exact' if not wrong else 'partial: ' + ', '.join(wrong)}"
              f"{'' if not repairs else f'  ({len(repairs)} repairs)'}")

    n = len(rows)
    print("\n" + "=" * 62)
    print(f"{'json_valid':22s} {totals['json_valid']:4d}/{n}  {totals['json_valid']/n:6.1%}")
    print(f"{'scoreable (0 repairs)':22s} {totals['scoreable']:4d}/{n}  {totals['scoreable']/n:6.1%}")
    print(f"{'exact_state (17/17)':22s} {totals['exact_state']:4d}/{n}  {totals['exact_state']/n:6.1%}")
    if changed_total:
        print(f"{'changed_recall':22s} {changed_hit:4d}/{changed_total}  "
              f"{changed_hit/changed_total:6.1%}   <- fields that had to move")
    if unchanged_total:
        print(f"{'unchanged_precision':22s} {unchanged_hit:4d}/{unchanged_total}  "
              f"{unchanged_hit/unchanged_total:6.1%}   <- fields that had to stay")
    print("=" * 62)
    print("\nPer-field accuracy (worst first):")
    for field, correct in sorted(field_correct.items(), key=lambda kv: kv[1]):
        bar = "#" * round(20 * correct / n)
        print(f"  {field:28s} {correct/n:6.1%} {bar}")

    if failures:
        print(f"\n{len(failures)} row(s) produced no JSON:")
        for index, why, sample in failures[:5]:
            print(f"  row {index}: {why} — {sample}")

    def rate(a, b):
        return round(a / b, 3) if b else None

    results = {
        "set": args.test_set,
        "adapter": str(args.adapter),
        "scored_as": "raw adapter numerics" if args.raw else "served (derived fields recomputed)",
        "n": n,
        "json_valid": rate(totals["json_valid"], n),
        "scoreable_zero_repairs": rate(totals["scoreable"], n),
        "exact_state_all_17": rate(totals["exact_state"], n),
        "changed_recall": rate(changed_hit, changed_total),
        "unchanged_precision": rate(unchanged_hit, unchanged_total),
        "field_accuracy": {f: rate(c, n) for f, c in field_correct.items()},
        "no_json_rows": [i for i, _, _ in failures],
        "misses": misses,
    }
    suffix = "_raw" if args.raw else ""
    out = EVAL_DIR / f"results_{args.test_set}{suffix}.json"
    out.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nSaved to {out}")

    # changed_recall is the one that separates a model that learned the task
    # from one that learned to echo its input.
    if changed_total and changed_hit / changed_total < 0.5:
        print("\nWARNING: changed_recall below 50% — the model is largely copying "
              "the previous state rather than updating it.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
