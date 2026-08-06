"""
smoke_test.py
=============

Fires the four CRM AI modules' real prompts at the local LLM server and checks
each reply the same way the Java code will.

This exists because the CRM fails *silently* when a model reply is unusable:
every client calls AiJson.extractObject(), gets null, and quietly runs its
fallback. Nothing appears in the UI, nothing appears in the backend log. So the
only reliable way to know the migration is working is to check the replies
directly — which is what this does.

Run (server must already be up):
    python scripts/smoke_test.py
    python scripts/smoke_test.py --url http://localhost:8001

Exit code is 0 only if all four modules pass.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------
# The exact system prompts the four Java clients send. Copied from:
#   lead/AiScoringClient.java        meeting/MeetingAnalysisClient.java
#   dealflow/DealAnalysisClient.java ai/DealCoachController.java
# Abridged only where the omitted text cannot affect routing or parsing.
# ---------------------------------------------------------------

LEAD_SYSTEM = (
    "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent "
    "signals (deal value, industry, company size, source channel, and buying signals in the "
    "notes), then decide whether the lead is worth a sales executive's time. "
    "Respond with ONLY strict JSON, no prose, no markdown fences: "
    '{"score": <0-100>, "label": "Hot"|"Warm"|"Cold", "reason": "<one sentence>", '
    '"qualificationStatus": "QUALIFIED"|"UNQUALIFIED", "qualificationProbability": <0-100>, '
    '"qualificationReasoning": "<1-2 sentences explaining the qualification decision>"}'
)

LEAD_USER = """Contact: Priya Menon
Company: Pioneer Hospitality K.K.
Industry: Hospitality
Company size: 474 employees
Product interest: CRM Suite
Estimated deal value: 15409421.00
Source channel: Website
Notes from sales executive: Ready to buy, only finalizing the vendor choice."""

MEETING_SYSTEM = (
    "You are a CRM assistant supporting a sales representative after a customer meeting. "
    "You will be given a lead's profile, its previous AI score, and the rep's raw meeting notes. "
    "Do two things: (1) write a concise 2-4 sentence summary of what happened in the meeting, "
    "(2) re-score the lead from 0-100 based on the buying signals in those notes. "
    "Respond with ONLY strict JSON, no prose, no markdown fences: "
    '{"summary": "<2-4 sentences>", "score": <0-100>, "label": "Hot"|"Warm"|"Cold", '
    '"reasons": ["<short phrase>", "..."]}. Give 2-5 reasons.'
)

MEETING_USER = """Contact: Priya Menon
Company: Pioneer Hospitality K.K.
Previous AI score: 62

Meeting held on 2026-08-04 at 14:00.
Rep's meeting notes:
CFO joined and confirmed budget of 1.5 crore is approved for this quarter. They want to go
live before the festive season, so the timeline is tight. Still evaluating one competitor
on price. Agreed to a technical deep-dive next Tuesday."""

DEAL_SYSTEM = (
    "You are a B2B sales analyst. You will be given a sales executive's structured write-up "
    "of a customer meeting. Extract the business signals listed below and return them as JSON.\n\n"
    "Fields and their allowed values:\n"
    "- customer_sentiment: one of [Positive, Neutral, Negative]\n"
    "- buying_intent: one of [High, Medium, Low]\n"
    "- budget_clarity: one of [Confirmed, Discussed, Unknown]\n"
    "- decision_maker_involved: one of [Yes, No]\n"
    "- competitor_pressure: one of [High, Medium, None]\n"
    "- relationship_strength: a number from 0 to 10\n\n"
    "Respond with ONLY strict JSON, no prose and no markdown fences, in exactly this shape:\n"
    '{"customer_sentiment": {"value": "Positive", "confidence": 0.9, "explanation": "..."}, ...}'
)

DEAL_USER = """Opportunity: Pioneer Hospitality — CRM Suite
Meeting 2 on 2026-08-04 at 14:00 (Discovery)
Participants: CFO, Head of Sales

Budget discussion: CFO confirmed 1.5 crore approved for this quarter.
Timeline: Go-live required before the festive season.
Objections raised: Still comparing one competitor on price.
Next steps: Technical deep-dive next Tuesday."""

COACH_SYSTEM = (
    "You are Deal coach, an assistant inside a CRM used by a sales team. "
    "Answer questions about leads, deals, accounts, cases and pipeline strategy. "
    "Be concise and practical — a few sentences or a short list, not an essay."
)

COACH_USER = "How do I revive a deal that has gone quiet for three weeks?"


def _post(url: str, payload: dict, stream: bool = False):
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    return urllib.request.urlopen(request, timeout=300)


def extract_object(content: str):
    """Mirror AiJson.extractObject — first '{' to last '}', or None."""
    start, end = content.find("{"), content.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(content[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def check(name: str, base: str, system: str, user: str, required: list) -> bool:
    print(f"\n--- {name} " + "-" * (58 - len(name)))
    started = time.time()
    try:
        with _post(
            f"{base}/v1/chat/completions",
            {
                "model": "crm-llama-3.1-8b-lora",
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
            },
        ) as response:
            content = json.loads(response.read())["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as exc:
        print(f"  FAIL  HTTP {exc.code} — {exc.read().decode('utf-8', 'replace')[:200]}")
        return False
    except Exception as exc:  # noqa: BLE001
        print(f"  FAIL  {type(exc).__name__}: {exc}")
        return False

    elapsed = time.time() - started
    parsed = extract_object(content)
    if parsed is None:
        print(f"  FAIL  reply held no JSON object after {elapsed:.1f}s.")
        print("        Java would return null here and run the fallback silently.")
        print(f"        Got: {content[:300]}")
        return False

    missing = [field for field in required if field not in parsed]
    if missing:
        print(f"  FAIL  JSON parsed but missing {missing} after {elapsed:.1f}s")
        print(f"        Got keys: {list(parsed)}")
        return False

    print(f"  PASS  {elapsed:5.1f}s   " + "  ".join(f"{f}={parsed[f]!r}"[:52] for f in required[:3]))
    return True


def check_stream(base: str) -> bool:
    print("\n--- 4. Deal coach (SSE stream) " + "-" * 29)
    started = time.time()
    chunks, saw_done = [], False
    try:
        with _post(
            f"{base}/v1/chat/completions",
            {
                "model": "crm-llama-3.1-8b-lora",
                "stream": True,
                "messages": [
                    {"role": "system", "content": COACH_SYSTEM},
                    {"role": "user", "content": COACH_USER},
                ],
            },
        ) as response:
            for raw in response:
                line = raw.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    saw_done = True
                    break
                delta = json.loads(payload)["choices"][0]["delta"]
                if delta.get("content"):
                    chunks.append(delta["content"])
    except Exception as exc:  # noqa: BLE001
        print(f"  FAIL  {type(exc).__name__}: {exc}")
        return False

    elapsed = time.time() - started
    if not chunks:
        print(f"  FAIL  stream produced no content after {elapsed:.1f}s")
        return False
    if not saw_done:
        # AiChatClient skips [DONE] explicitly; without it the reader waits on
        # the socket instead of finishing cleanly.
        print(f"  FAIL  stream never sent [DONE] ({len(chunks)} chunks)")
        return False

    print(f"  PASS  {elapsed:5.1f}s   {len(chunks)} chunks, [DONE] received")
    print(f"        {''.join(chunks)[:160].replace(chr(10), ' ')}...")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8001", help="server base URL")
    args = parser.parse_args()
    base = args.url.rstrip("/")

    print("=" * 62)
    print(f"CRM AI smoke test  ->  {base}")
    print("=" * 62)

    try:
        with urllib.request.urlopen(f"{base}/health", timeout=10) as response:
            health = json.loads(response.read())
    except Exception as exc:  # noqa: BLE001
        print(f"\nCannot reach the server: {exc}")
        print("Start it with:  python scripts/main.py")
        return 1

    print(f"health: {health['status']}  device={health['device']}  model={health['model']}")
    if health["status"] != "ok":
        print(f"Model is not loaded: {health.get('error')}")
        return 1

    results = [
        check(
            "1. Lead scoring (adapter ON)", base, LEAD_SYSTEM, LEAD_USER,
            ["score", "label", "qualificationStatus", "qualificationProbability",
             "qualificationReasoning", "reason"],
        ),
        check(
            "2. Meeting analysis (base)", base, MEETING_SYSTEM, MEETING_USER,
            ["summary", "score", "label", "reasons"],
        ),
        check(
            "3. Deal analysis (base)", base, DEAL_SYSTEM, DEAL_USER,
            ["customer_sentiment", "buying_intent", "budget_clarity"],
        ),
        check_stream(base),
    ]

    passed = sum(results)
    print("\n" + "=" * 62)
    print(f"{passed}/4 modules passed")
    print("=" * 62)
    return 0 if passed == 4 else 1


if __name__ == "__main__":
    sys.exit(main())
