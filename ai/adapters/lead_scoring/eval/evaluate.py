"""
evaluate.py
===========
Non-interactive consistency check for the fine-tuned model: runs every
held-out eval example through the model (using the exact same prompt
server/main.py serves) and measures how often the generated output matches
the required format. Run it after each training run.

Checks per generated output (same rules as validate_dataset.py):
  - Lead Score present, 0-100, with "/100" suffix
  - Qualification present and one of Hot/Warm/Cold
  - Priority present and one of High/Medium/Low, consistent with Qualification
  - Exactly 5 "• " Reason bullets
  - Recommended Action present
  - No legacy labels (Qualified/Critical) or stray "Overall Explanation"

Also reports accuracy against the ground-truth label for each row
(format-valid is not the same thing as correct -- a reply can be perfectly
well-formed and still predict the wrong Qualification):
  - Customer Requirement tier accuracy -- the one judgement the model makes;
    reconcile_output() recomputes the other four factors from the input
  - Qualification exact-match rate
  - Priority exact-match rate
  - Lead Score exact-match rate, and within +/-5 / +/-10 points

Two test sets:
  heldout    the eval split of data/train.jsonl (synthetic, unseen phrasings)
  realistic  eval/realistic.jsonl -- hand-written sales-rep notes in the shape
             the CRM bridge sends (no Company Location), labelled by hand with
             the tier rules in data/generate_dataset.py

Results go to eval/results_<set>.json.

Run (after training has finished and freed the GPU):
    python adapters/lead_scoring/eval/evaluate.py
    python adapters/lead_scoring/eval/evaluate.py --set realistic
"""

import argparse
import json
import re
import sys
from pathlib import Path

import torch
from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
from peft import PeftModel

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR))
from prompt_format import (  # noqa: E402
    QUALIFICATION_BY_SCORE, SYSTEM_PROMPT, TIMELINE_POINTS, _parse_input_fields,
    deal_value_points, employees_points, quantity_points,
    build_llama3_prompt, build_user_turn, reconcile_output,
)

AI_ROOT = ADAPTER_DIR.parent.parent
MODEL_PATH = AI_ROOT / "base-model" / "Llama-3.1-8B-Instruct"
ADAPTER_PATH = ADAPTER_DIR / "weights"
DATA_PATH = ADAPTER_DIR / "data" / "train.jsonl"
REALISTIC_PATH = ADAPTER_DIR / "eval" / "realistic.jsonl"
REQUIREMENT_RE = re.compile(r"^• Customer Requirement.*contributing (\d+) points", re.MULTILINE)

PRIORITY_BY_QUALIFICATION = {"Hot": "High", "Warm": "Medium", "Cold": "Low"}


def load_eval_rows(n: int, seed: int = 42):
    """The slice train.py held out: the generator's `split` column when the
    data has one, otherwise train.py's old train_test_split(seed=42)."""
    from datasets import load_dataset

    dataset = load_dataset("json", data_files=str(DATA_PATH), split="train")
    if "split" in dataset.column_names:
        eval_dataset = dataset.filter(lambda r: r["split"] == "eval")
    else:
        eval_dataset = dataset.train_test_split(test_size=0.10, seed=seed, shuffle=True)["test"]
    return list(eval_dataset.select(range(min(n, len(eval_dataset)))))


def expected_output(lead_input: str, requirement_points: int) -> str:
    """What a perfect reply scores to: the four lookup factors from the input
    plus the hand-labelled requirement tier. Only the header values and the
    requirement bullet are compared, so the rest is left out."""
    f = _parse_input_fields(lead_input)
    points = [
        employees_points(int(f["Employees Count"])),
        quantity_points(int(f["Product Quantity"])),
        deal_value_points(int(re.sub(r"[^0-9]", "", f["Deal Value"]))),
        TIMELINE_POINTS[f["Purchase Timeline"]],
        requirement_points,
    ]
    total = sum(points)
    qual, prio = next((q, p) for cutoff, q, p in QUALIFICATION_BY_SCORE if total <= cutoff)
    return f"Lead Score: {total}/100\n\nQualification:\n{qual}\n\nPriority:\n{prio}\n"


def load_realistic_rows(n: int):
    rows = [json.loads(line) for line in REALISTIC_PATH.open(encoding="utf-8") if line.strip()]
    for row in rows:
        row["output"] = expected_output(row["input"], row["requirement_points"])
    return rows[:n]


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
    parser.add_argument("--n", type=int, default=1000, help="Evaluate at most this many rows")
    parser.add_argument("--set", dest="test_set", choices=["heldout", "realistic"], default="heldout")
    parser.add_argument("--adapter", type=Path, default=ADAPTER_PATH)
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
    model = PeftModel.from_pretrained(base_model, str(args.adapter))
    model.eval()

    rows = load_eval_rows(args.n) if args.test_set == "heldout" else load_realistic_rows(args.n)
    print(f"Evaluating on {len(rows)} {args.test_set} examples...\n")

    pass_count = 0
    failure_tally = {}
    qualification_correct = 0
    priority_correct = 0
    score_exact = 0
    score_within_5 = 0
    score_within_10 = 0
    reconciled_count = 0
    requirement_correct = requirement_total = 0
    misses = []

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

        reconciled = reconcile_output(response, row["input"])
        if reconciled != response:
            reconciled_count += 1
        response = reconciled

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

        truth_req = row.get("requirement_points")
        if truth_req is not None:
            requirement_total += 1
            req_match = REQUIREMENT_RE.search(response)
            got_req = int(req_match.group(1)) if req_match else None
            if got_req == truth_req:
                requirement_correct += 1
            else:
                misses.append({"id": row.get("id"), "requirement_points": truth_req,
                               "predicted": got_req, "qualification": truth["qualification"],
                               "predicted_qualification": predicted["qualification"]})

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
    if requirement_total:
        print(f"Requirement tier acc.:   {requirement_correct}/{requirement_total} "
              f"({100 * requirement_correct / requirement_total:.1f}%)")
    print(f"Format-consistent:       {pass_count}/{n} ({100 * pass_count / n:.1f}%)")
    print(f"Qualification accuracy:  {qualification_correct}/{n} ({100 * qualification_correct / n:.1f}%)")
    print(f"Priority accuracy:       {priority_correct}/{n} ({100 * priority_correct / n:.1f}%)")
    print(f"Lead Score exact match:  {score_exact}/{n} ({100 * score_exact / n:.1f}%)")
    print(f"Lead Score within +/-5:  {score_within_5}/{n} ({100 * score_within_5 / n:.1f}%)")
    print(f"Lead Score within +/-10: {score_within_10}/{n} ({100 * score_within_10 / n:.1f}%)")
    print(f"Self-inconsistent (header vs bullet-sum, corrected): {reconciled_count}/{n} ({100 * reconciled_count / n:.1f}%)")
    if failure_tally:
        print("\nFormat failure breakdown:")
        for k, v in sorted(failure_tally.items(), key=lambda x: -x[1]):
            print(f"  {k}: {v}")
    print("=" * 50)

    def rate(a, b):
        return round(a / b, 3) if b else None

    results = {
        "set": args.test_set,
        "adapter": str(args.adapter),
        "n": n,
        "requirement_tier_accuracy": rate(requirement_correct, requirement_total),
        "qualification_accuracy": rate(qualification_correct, n),
        "priority_accuracy": rate(priority_correct, n),
        "score_exact": rate(score_exact, n),
        "score_within_5": rate(score_within_5, n),
        "score_within_10": rate(score_within_10, n),
        "format_consistent": rate(pass_count, n),
        "self_inconsistent_corrected": rate(reconciled_count, n),
        "format_failures": failure_tally,
        "requirement_misses": misses,
    }
    out = ADAPTER_DIR / "eval" / f"results_{args.test_set}.json"
    out.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"Saved to {out}")


if __name__ == "__main__":
    main()
