"""
evaluate.py
===========
Scores the Marketing Automation adapter on the held-out split, through the
running model server, the same way the Java backend will judge its replies.

    python adapters/marketing/eval/evaluate.py                 # adapter (server loaded it)
    python adapters/marketing/eval/evaluate.py --label base    # after restarting the server
                                                               # with CRM_MARKETING_ADAPTER_PATH=none

Going through the server rather than loading weights here means the numbers
include the real routing, prompt rendering and decoding settings: what gets
measured is what gets served. To compare with the base model, restart the
server with the adapter path pointing nowhere; marketing requests then fall
back to the base model, which is exactly what production did before this adapter.

Lead evaluation (vs the teacher's target):
  valid          reply accepted by LeadEvaluationValidator (first attempt, no retry)
  eligibility    same eligible / not_eligible decision as the target
  rule_guard     model said eligible while a required rule FAILED (the Java guard would fire)
  score_mae      mean |score - target score|
Content generation:
  valid          every requested channel passes ContentOutputValidator on the first attempt
  within_margin  valid, and every field under prompt_format.LENGTH_MARGIN of its limit
  channel_valid  share of individual channels that pass
Results go to adapters/marketing/eval/results_<label>.json.

Hand-written test set (eval/build_realistic.py):
    python adapters/marketing/eval/evaluate.py --heldout adapters/marketing/eval/realistic.jsonl --label realistic
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR.parent.parent))
from adapters.marketing import prompt_format as fmt  # noqa: E402

HELDOUT = ADAPTER_DIR / "weights" / "heldout.jsonl"


def call(server: str, messages: list, temperature: float, max_tokens: int, timeout: int) -> str:
    body = json.dumps({"model": "crm-llama-3.1-8b-lora", "messages": messages[:2],
                       "temperature": temperature, "max_tokens": max_tokens}).encode("utf-8")
    req = urllib.request.Request(server.rstrip("/") + "/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json", "X-CRM-Task": "marketing"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))["choices"][0]["message"]["content"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default="http://localhost:8001")
    parser.add_argument("--label", default="adapter")
    parser.add_argument("--limit", type=int, default=0, help="score only the first N rows of each task")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--heldout", type=Path, default=HELDOUT,
                        help="rows to score (tools/retrain.py passes data/benchmark.jsonl)")
    parser.add_argument("--output", type=Path, default=None,
                        help="results file (default eval/results_<label>.json)")
    args = parser.parse_args()
    heldout = args.heldout

    if not heldout.exists():
        sys.exit(f"{heldout} not found. weights/heldout.jsonl is written by train.py.")
    rows = [json.loads(line) for line in heldout.open(encoding="utf-8") if line.strip()]
    if args.limit:
        by_task = {}
        for r in rows:
            by_task.setdefault(r["task"], []).append(r)
        rows = [r for part in by_task.values() for r in part[:args.limit]]

    lead = {"n": 0, "valid": 0, "eligibility": 0, "rule_guard": 0, "abs_err": 0.0}
    # Rows built from real Marketing Head decisions (data/collect_feedback.py),
    # scored separately: they are what retraining is meant to improve.
    real = {"n": 0, "eligibility": 0}
    content = {"n": 0, "valid": 0, "within_margin": 0, "channels": 0, "channels_valid": 0, "errors": {}}
    started = time.time()

    for i, row in enumerate(rows, 1):
        messages = row["messages"]
        user = messages[1]["content"]
        if row["task"] == "lead_evaluation":
            target = json.loads(messages[2]["content"])
            meta = json.loads(row["meta"])
            reply = call(args.server, messages, 0.0, 450, args.timeout)
            lead["n"] += 1
            from_feedback = meta.get("source") == "feedback"
            real["n"] += int(from_feedback)
            try:
                got = fmt.parse_lead_evaluation(reply, target["lead_id"])
            except ValueError as e:
                print(f"[{i}/{len(rows)}] lead INVALID: {e}")
                continue
            lead["valid"] += 1
            want_eligible = target["eligibility"] == "eligible"
            lead["eligibility"] += int(got["eligible"] == want_eligible)
            real["eligibility"] += int(from_feedback and got["eligible"] == want_eligible)
            lead["rule_guard"] += int(got["eligible"] and meta["combined"] == "FAIL")
            lead["abs_err"] += abs(got["score"] - target["score"])
            print(f"[{i}/{len(rows)}] lead score {got['score']} vs {target['score']}, "
                  f"{'eligible' if got['eligible'] else 'not_eligible'} vs {target['eligibility']}")
        else:
            doc = json.loads(user.split("\n\nYour previous reply")[0])
            channels = list(doc["channels"])
            grounding = fmt.grounding_text(doc["brief"], doc["style"])
            reply = call(args.server, messages, 0.4, 1800, args.timeout)
            accepted, rejected = fmt.check_content_reply(reply, channels, grounding)
            content["n"] += 1
            content["channels"] += len(channels)
            content["channels_valid"] += len(accepted)
            content["valid"] += int(not rejected)
            # Valid with room to spare: what LENGTH_MARGIN trains toward.
            content["within_margin"] += int(not rejected and not fmt.over_margin(accepted))
            for errs in rejected.values():
                for e in errs:
                    kind = e.split(": ", 1)[-1].split("'")[0].strip()
                    content["errors"][kind] = content["errors"].get(kind, 0) + 1
            print(f"[{i}/{len(rows)}] content {len(accepted)}/{len(channels)} channels valid"
                  + (f" — {next(iter(rejected.values()))[0]}" if rejected else ""))

    def rate(a, b):
        return round(a / b, 3) if b else None

    results = {
        "label": args.label,
        "heldout": str(heldout),
        "heldout_rows": len(rows),
        "minutes": round((time.time() - started) / 60, 1),
        "lead_evaluation": {
            "n": lead["n"],
            "valid": rate(lead["valid"], lead["n"]),
            "eligibility_accuracy": rate(lead["eligibility"], lead["valid"]),
            "rule_guard_would_fire": rate(lead["rule_guard"], lead["valid"]),
            "score_mae": rate(lead["abs_err"], lead["valid"]),
        },
        # An invalid reply counts as wrong here, unlike eligibility_accuracy above.
        "lead_evaluation_feedback": {
            "n": real["n"],
            "eligibility_accuracy": rate(real["eligibility"], real["n"]),
        },
        "content_generation": {
            "n": content["n"],
            "valid_first_attempt": rate(content["valid"], content["n"]),
            "valid_within_margin": rate(content["within_margin"], content["n"]),
            "channel_valid": rate(content["channels_valid"], content["channels"]),
            "rejection_reasons": dict(sorted(content["errors"].items(), key=lambda kv: -kv[1])),
        },
    }
    out = args.output or ADAPTER_DIR / "eval" / f"results_{args.label}.json"
    out.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(json.dumps(results, indent=2))
    print(f"Saved to {out}")


if __name__ == "__main__":
    main()
