"""
system_test.py
==============
End-to-end accuracy test of the whole CRM AI stack, over HTTP.

Exercises the real services on their real ports, in the order a deal actually
moves through them:

    :8001  Lead Scoring LLM        lead profile      -> lead score
    :8002  Deal Intelligence LLM   state + meeting   -> updated state
    :8000  XGBoost deal scorer     state             -> deal score

Every other test in these projects calls the model in-process. This one goes
through the HTTP layer instead, which is where a different class of failure
lives: a port collision, a schema the service rejects, a field name that
survived refactoring in one project but not the other. The chain can be correct
in every component and still be broken end to end.

Run (all three services must be up):
    python scripts/system_test.py
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deal_state_format as fmt  # noqa: E402

LEAD_URL = "http://localhost:8001/v1/chat/completions"
DEAL_URL = "http://localhost:8002/v1/deal-state"
SCORE_URL = "http://localhost:8000/score"


def post(url: str, payload: dict, timeout: int = 300):
    request = urllib.request.Request(
        url, data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read())


# ============================================================
# 1. LEAD SCORING  (:8001)
# ============================================================
# Deterministic cases: notes use the exact phrases the fine-tune was trained on,
# so the expected score is computable from the tier tables rather than guessed.

LEAD_SYSTEM = (
    "You are a CRM lead-scoring assistant. Score the lead from 0-100 based on fit and intent "
    "signals. Respond with ONLY strict JSON, no prose, no markdown fences: "
    '{"score": <0-100>, "label": "Hot"|"Warm"|"Cold", "reason": "<one sentence>", '
    '"qualificationStatus": "QUALIFIED"|"UNQUALIFIED", "qualificationProbability": <0-100>, '
    '"qualificationReasoning": "<1-2 sentences>"}'
)

LEAD_CASES = [
    (8000, 900, "30000000", "Immediately",
     "Budget already approved and ready to move forward.", 100, "Hot"),
    (6000, 400, "15000000", "Within 15 Days",
     "Looking specifically for AI-based lead scoring.", 85, "Hot"),
    (500, 60, "3000000", "Within 2 Months",
     "Looking for a CRM to manage customer records.", 50, "Warm"),
    (150, 30, "500000", "Within 3 Months",
     "Comparing a few vendors before deciding.", 25, "Cold"),
    (20, 2, "50000", "More than 3 Months",
     "Just making a general enquiry.", 0, "Cold"),
]


def test_lead_scoring() -> tuple:
    print("1. LEAD SCORING  (:8001)")
    print("-" * 62)
    hits = 0
    for employees, quantity, value, timeline, notes, expected, label in LEAD_CASES:
        user = (f"Contact: Test Lead\nCompany: Acme Test Pvt Ltd\nIndustry: Retail\n"
                f"Company size: {employees} employees\nProduct interest: CRM Suite\n"
                f"Product quantity: {quantity}\nEstimated deal value: {value}\n"
                f"Purchase timeline: {timeline}\nSource channel: Website\n"
                f"Notes from sales executive: {notes}")
        body = post(LEAD_URL, {
            "model": "crm-llama-3.1-8b-lora",
            "messages": [{"role": "system", "content": LEAD_SYSTEM},
                         {"role": "user", "content": user}],
        })
        content = body["choices"][0]["message"]["content"]
        parsed = json.loads(content[content.find("{"):content.rfind("}") + 1])
        ok = parsed["score"] == expected and parsed["label"] == label
        hits += ok
        print(f"   {employees:>5} emp  expect {expected:3d}/{label:<5} "
              f"got {parsed['score']:3d}/{parsed['label']:<5} {'OK' if ok else 'DIFF'}")
    print(f"   -> {hits}/{len(LEAD_CASES)} exact\n")
    return hits, len(LEAD_CASES)


# ============================================================
# 2 + 3. DEAL INTELLIGENCE -> XGBOOST  (:8002 -> :8000)
# ============================================================
# A multi-meeting journey: state carried forward meeting to meeting, exactly as
# production would. Tests the thing unit tests cannot — that the JSON one
# service emits is the JSON the next one accepts.

JOURNEY = [
    ("Introductory call. The operations lead walked us through their current process "
     "and was polite but non-committal. No budget conversation. They asked for a "
     "follow-up demo.", "opening call"),
    ("Demo went very well — the team was visibly enthusiastic and said this is clearly "
     "what they have been missing. Their CFO joined for the second half and asked "
     "direct questions about rollout. Still no budget allocated.", "demo, CFO joins"),
    ("CFO confirmed Finance has approved the full amount for this financial year. They "
     "need to be live before the festive peak, which puts a firm date on delivery. "
     "They asked us to send a formal commercial proposal.", "budget approved"),
    ("Procurement raised the contract length and exit terms as a concern. A competing "
     "vendor was named as also being in the evaluation. Tone was measured.", "objections"),
    ("Legal agreed revised terms and that objection is closed. They confirmed the "
     "alternative has been ruled out. Verbal commitment to proceed, subject to "
     "paperwork.", "verbal agreement"),
]


def test_deal_chain() -> tuple:
    print("2. DEAL INTELLIGENCE -> XGBOOST  (:8002 -> :8000)")
    print("-" * 62)
    state = None
    scores = []
    accepted = 0
    repairs_total = 0

    for index, (notes, label) in enumerate(JOURNEY, 1):
        payload = {"meeting_notes": notes}
        if state is not None:
            payload["previous_state"] = state
        started = time.time()
        result = post(DEAL_URL, payload)
        state = result["state"]
        repairs_total += len(result["repairs"])

        # The state goes to the scorer verbatim — no massaging. If the two
        # services disagree about the contract, this is where it surfaces.
        try:
            scored = post(SCORE_URL, state, timeout=60)
            deal_score = scored["deal_score"]
            band = scored.get("band", "?")
            accepted += 1
        except urllib.error.HTTPError as exc:
            print(f"   meeting {index}: SCORER REJECTED — {exc.read().decode()[:120]}")
            continue

        scores.append(deal_score)
        print(f"   meeting {index} ({label:<18}) "
              f"deal_score {deal_score:5.1f} {band:<6} "
              f"changed={len(result['changed_fields']):2d} "
              f"repairs={len(result['repairs'])} "
              f"{time.time() - started:4.1f}s")

    print(f"\n   scorer accepted    {accepted}/{len(JOURNEY)}")
    print(f"   total repairs      {repairs_total}")
    if len(scores) >= 2:
        rising = sum(1 for a, b in zip(scores, scores[1:]) if b >= a)
        print(f"   score trajectory   {' -> '.join(f'{s:.0f}' for s in scores)}")
        # A journey that improves throughout should not score worse at the end
        # than it started. This catches a model that reads meetings backwards.
        print(f"   net movement       {scores[-1] - scores[0]:+.1f} "
              f"({'improves' if scores[-1] > scores[0] else 'DOES NOT IMPROVE'})")
        print(f"   monotonic steps    {rising}/{len(scores) - 1}")
    print()
    return accepted, len(JOURNEY), scores


def main() -> int:
    print("=" * 62)
    print("CRM AI SYSTEM TEST")
    print("=" * 62 + "\n")

    for name, url in [("lead scoring", "http://localhost:8001/health"),
                      ("deal intelligence", "http://localhost:8002/health"),
                      ("xgboost scorer", "http://localhost:8000/health")]:
        try:
            with urllib.request.urlopen(url, timeout=10) as response:
                status = json.loads(response.read()).get("status", "?")
            print(f"   {name:<20} {status}")
        except Exception as exc:  # noqa: BLE001
            print(f"   {name:<20} UNREACHABLE — {exc}")
            return 1
    print()

    lead_hits, lead_total = test_lead_scoring()
    accepted, journey_total, scores = test_deal_chain()

    print("=" * 62)
    print(f"lead scoring exact       {lead_hits}/{lead_total}   {lead_hits / lead_total:6.1%}")
    print(f"deal chain accepted      {accepted}/{journey_total}   {accepted / journey_total:6.1%}")
    print("=" * 62)
    return 0 if lead_hits == lead_total and accepted == journey_total else 1


if __name__ == "__main__":
    sys.exit(main())
