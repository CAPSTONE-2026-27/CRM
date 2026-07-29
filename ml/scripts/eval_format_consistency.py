"""
eval_format_consistency.py
===========================
Non-interactive consistency check for the fine-tuned model: runs every
held-out eval example through the model (using the exact same prompt
inference.py uses) and measures how often the generated output matches the
required format — the thing this whole reformatting effort was for.

This is deliberately separate from inference.py (which is an interactive,
one-lead-at-a-time REPL for manual use) — this script is for automated,
repeatable measurement after each training run.

Checks per generated output (same rules as validate_dataset.py):
  - Lead Score present, 0-100, with "/100" suffix
  - Qualification present and one of Hot/Warm/Cold
  - Priority present and one of High/Medium/Low, consistent with Qualification
  - Exactly 5 "• " Reason bullets
  - Recommended Action present
  - No legacy labels (Qualified/Critical) or stray "Overall Explanation"

Also reports accuracy against the dataset's ground-truth label for each
held-out row (format-valid is not the same thing as correct -- a reply can
be perfectly well-formed and still predict the wrong Qualification):
  - Qualification exact-match rate
  - Priority exact-match rate
  - Lead Score exact-match rate, and within +/-5 / +/-10 points

Run (after training has finished and freed the GPU):
    python scripts/eval_format_consistency.py [--n 50]
"""

import argparse
import json
import re
import sys
from pathlib import Path

import torch
from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
from peft import PeftModel

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prompt_format import SYSTEM_PROMPT, build_llama3_prompt, build_user_turn  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = PROJECT_ROOT / "models" / "Llama-3.1-8B-Instruct"
ADAPTER_PATH = PROJECT_ROOT / "outputs" / "lead_management_llama3_lora"
DATA_PATH = PROJECT_ROOT / "data" / "train.jsonl"

PRIORITY_BY_QUALIFICATION = {"Hot": "High", "Warm": "Medium", "Cold": "Low"}


def load_eval_rows(n: int, seed: int = 42):
    """Mirror train.py's train_test_split(seed=42) so this evaluates on the
    same 10% held-out slice the model never trained on."""
    from datasets import load_dataset

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train")
    split = dataset.train_test_split(test_size=0.10, seed=seed, shuffle=True)
    eval_dataset = split["test"]
    return eval_dataset.select(range(min(n, len(eval_dataset))))


def parse_labels(output: str) -> dict:
    """Extract the three predicted/labeled fields we score accuracy on."""
    score_match = re.search(r"Lead Score:\s*(\d+)/100", output)
    qual_match = re.search(r"Qualification:\s*(.+)", output)
    prio_match = re.search(r"Priority:\s*(.+)", output)
    return {
        "score": int(score_match.group(1)) if score_match else None,
        "qualification": qual_match.group(1).strip() if qual_match else None,
        "priority": prio_match.group(1).strip() if prio_match else None,
    }


def check_format(output: str) -> dict:
    result = {"pass": True, "failures": []}

    score_match = re.search(r"Lead Score:\s*(\d+)/100", output)
    if not score_match:
        result["failures"].append("missing_or_malformed_score")
    else:
        score = int(score_match.group(1))
        if not (0 <= score <= 100):
            result["failures"].append("score_out_of_range")

    qual_match = re.search(r"Qualification:\s*(.+)", output)
    qualification = qual_match.group(1).strip() if qual_match else None
    if qualification not in {"Hot", "Warm", "Cold"}:
        result["failures"].append(f"invalid_qualification:{qualification}")

    prio_match = re.search(r"Priority:\s*(.+)", output)
    priority = prio_match.group(1).strip() if prio_match else None
    if priority not in {"High", "Medium", "Low"}:
        result["failures"].append(f"invalid_priority:{priority}")
    elif qualification in PRIORITY_BY_QUALIFICATION and priority != PRIORITY_BY_QUALIFICATION[qualification]:
        result["failures"].append(f"priority_qualification_mismatch:{qualification}/{priority}")

    bullets = re.findall(r"^• (.+)$", output, re.MULTILINE)
    if len(bullets) != 5:
        result["failures"].append(f"bullet_count:{len(bullets)}")

    if "Recommended Action:" not in output:
        result["failures"].append("missing_recommended_action")

    if "Qualified" in output or "Critical" in output:
        result["failures"].append("legacy_label_present")

    if "Overall Explanation" in output:
        result["failures"].append("stray_overall_explanation")

    if result["failures"]:
        result["pass"] = False

    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--n", type=int, default=50, help="Number of held-out eval rows to test")
    parser.add_argument("--max-new-tokens", type=int, default=280)
    args = parser.parse_args()

    print("Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(str(MODEL_PATH), trust_remote_code=True, local_files_only=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    print("Loading base model (4-bit, matching train.py's QLoRA config)...")
    # Loaded in 4-bit rather than full fp16: an fp16 8B model needs ~16GB for
    # weights alone, which doesn't reliably fit on a 16GB RTX A4000. Loading
    # in fp16 previously caused accelerate to silently offload layers to CPU,
    # making generation extremely and invisibly slow.
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    base_model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=bnb_config,
        device_map="auto",
        trust_remote_code=True,
        local_files_only=True,
    )

    print("Loading LoRA adapter (not merged, to stay in 4-bit)...")
    model = PeftModel.from_pretrained(base_model, str(ADAPTER_PATH))
    model.eval()

    rows = load_eval_rows(args.n)
    print(f"Evaluating on {len(rows)} held-out examples...\n")

    pass_count = 0
    failure_tally = {}
    qualification_correct = 0
    priority_correct = 0
    score_exact = 0
    score_within_5 = 0
    score_within_10 = 0

    for i, row in enumerate(rows, start=1):
        prompt = build_llama3_prompt(SYSTEM_PROMPT, build_user_turn(row["input"]))
        inputs = tokenizer(prompt, return_tensors="pt")
        inputs = {k: v.to(model.device) for k, v in inputs.items()}

        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_new_tokens=args.max_new_tokens,
                do_sample=False,
                repetition_penalty=1.05,
                eos_token_id=tokenizer.eos_token_id,
                pad_token_id=tokenizer.eos_token_id,
            )

        full_response = tokenizer.decode(outputs[0], skip_special_tokens=False, clean_up_tokenization_spaces=False)
        assistant_start = "<|start_header_id|>assistant<|end_header_id|>"
        response = full_response.split(assistant_start)[-1] if assistant_start in full_response else full_response
        response = response.replace("<|eot_id|>", "").strip()
        if "<|start_header_id|>" in response:
            response = response.split("<|start_header_id|>")[0].strip()

        result = check_format(response)
        status = "PASS" if result["pass"] else "FAIL"

        predicted = parse_labels(response)
        truth = parse_labels(row["output"])

        qual_match = predicted["qualification"] == truth["qualification"]
        prio_match = predicted["priority"] == truth["priority"]
        score_diff = (
            abs(predicted["score"] - truth["score"])
            if predicted["score"] is not None and truth["score"] is not None
            else None
        )

        if qual_match:
            qualification_correct += 1
        if prio_match:
            priority_correct += 1
        if score_diff is not None:
            if score_diff == 0:
                score_exact += 1
            if score_diff <= 5:
                score_within_5 += 1
            if score_diff <= 10:
                score_within_10 += 1

        print(
            f"[{i}/{len(rows)}] {status} | truth={truth['qualification']}/{truth['priority']}/{truth['score']} "
            f"pred={predicted['qualification']}/{predicted['priority']}/{predicted['score']}"
            + (f" -- {result['failures']}" if result["failures"] else "")
        )

        if result["pass"]:
            pass_count += 1
        else:
            for f in result["failures"]:
                key = f.split(":")[0]
                failure_tally[key] = failure_tally.get(key, 0) + 1

    n = len(rows)
    print("\n" + "=" * 50)
    print(f"Format-consistent:       {pass_count}/{n} ({100 * pass_count / n:.1f}%)")
    print(f"Qualification accuracy:  {qualification_correct}/{n} ({100 * qualification_correct / n:.1f}%)")
    print(f"Priority accuracy:       {priority_correct}/{n} ({100 * priority_correct / n:.1f}%)")
    print(f"Lead Score exact match:  {score_exact}/{n} ({100 * score_exact / n:.1f}%)")
    print(f"Lead Score within +/-5:  {score_within_5}/{n} ({100 * score_within_5 / n:.1f}%)")
    print(f"Lead Score within +/-10: {score_within_10}/{n} ({100 * score_within_10 / n:.1f}%)")
    if failure_tally:
        print("\nFormat failure breakdown:")
        for k, v in sorted(failure_tally.items(), key=lambda x: -x[1]):
            print(f"  {k}: {v}")
    print("=" * 50)


if __name__ == "__main__":
    main()
