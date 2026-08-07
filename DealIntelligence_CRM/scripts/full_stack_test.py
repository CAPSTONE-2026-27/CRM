"""
full_stack_test.py
==================
Tests the CRM the way a sales rep uses it: through the backend REST API.

system_test.py drives the three AI services directly. That proves the models
work and that they speak to each other, but it bypasses everything the CRM
actually does — auth, persistence, the async scoring thread, the DTO mapping,
the JPA column constraints. A lead can score perfectly at :8001 and still
arrive in the database unscored, and nothing in the AI-level tests would notice,
because the CRM is built to degrade silently when a model is unreachable.

So this signs up, creates leads over HTTP, and waits for the score to appear in
the database via GET. If it comes back null, the chain is broken somewhere
between Spring and the model regardless of what the model does in isolation.

Requires: :8080 backend, :8001 lead scoring LLM.

Run:
    python scripts/full_stack_test.py
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BACKEND = "http://localhost:8080"

# The same deterministic cases the AI-level test uses. Reusing them means a
# difference between the two tests isolates the backend as the cause, rather
# than leaving it ambiguous whether the model or the plumbing changed.
LEAD_CASES = [
    ("Priya Menon", "Everest Retail Group", "8000", 900, 30000000, "Immediately",
     "Budget already approved and ready to move forward.", 100, "Hot"),
    ("Ananya Rao", "Bluepeak Services", "500", 60, 3000000, "Within 2 Months",
     "Looking for a CRM to manage customer records.", 50, "Warm"),
    ("Sneha Kulkarni", "Tinytown Crafts", "20", 2, 50000, "More than 3 Months",
     "Just making a general enquiry.", 0, "Cold"),
]

SCORING_TIMEOUT_S = 180  # scoring is @Async; a local 8B takes ~15s per lead


def request(method: str, path: str, payload=None, token=None, timeout=60):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BACKEND + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        body = response.read()
        return json.loads(body) if body else {}


def main() -> int:
    print("=" * 64)
    print("FULL STACK TEST  —  frontend API -> backend -> LLM -> database")
    print("=" * 64 + "\n")

    # --- health ---------------------------------------------------------
    try:
        health = request("GET", "/actuator/health")
        print(f"   backend      {health['status']}  db={health['components']['db']['status']}")
    except Exception as exc:  # noqa: BLE001
        print(f"   backend UNREACHABLE — {exc}")
        return 1

    # --- auth -----------------------------------------------------------
    # A fresh org per run: leads are org-scoped, and reusing one would let a
    # previous run's rows satisfy the duplicate check and skip scoring.
    suffix = uuid.uuid4().hex[:8]
    signup = {
        "organizationName": f"SystemTest {suffix}",
        "fullName": "System Test",
        "email": f"systest.{suffix}@example.test",
        "password": "SystemTest123!",
    }
    try:
        auth = request("POST", "/api/auth/signup", signup)
    except urllib.error.HTTPError as exc:
        print(f"   signup FAILED {exc.code} — {exc.read().decode()[:200]}")
        return 1

    token = auth.get("accessToken") or auth.get("token")
    if not token:
        print(f"   signup returned no access token: {list(auth)}")
        return 1
    print(f"   auth         signed up as {signup['email']}\n")

    # --- create leads ---------------------------------------------------
    print("CREATING LEADS")
    print("-" * 64)
    created = []
    for name, company, employees, quantity, value, timeline, notes, expected, label in LEAD_CASES:
        payload = {
            "fullName": name,
            "company": company,
            "email": f"{name.split()[0].lower()}.{suffix}@example.test",
            "phone": "+91 98200 00000",
            "product": "Enterprise Plan",
            "employeeCount": employees,
            "productQuantity": quantity,
            "estimatedDealValue": value,
            "purchaseTimeline": timeline,
            "sourceChannel": "Web form",
            "captureMethod": "WEB_FORM",
            "notes": notes,
        }
        try:
            lead = request("POST", "/api/leads", payload, token)
        except urllib.error.HTTPError as exc:
            print(f"   {name:<18} CREATE FAILED {exc.code} — {exc.read().decode()[:160]}")
            continue
        created.append((lead["id"], name, expected, label))
        print(f"   {name:<18} created id={lead['id']}  score={lead.get('aiScore')} (async)")

    if not created:
        print("\n   No leads created.")
        return 1

    # --- wait for async scoring ----------------------------------------
    print(f"\nWAITING FOR AI SCORING (@Async, up to {SCORING_TIMEOUT_S}s)")
    print("-" * 64)
    deadline = time.time() + SCORING_TIMEOUT_S
    results = {}
    while time.time() < deadline and len(results) < len(created):
        for lead_id, name, expected, label in created:
            if lead_id in results:
                continue
            lead = request("GET", f"/api/leads/{lead_id}", token=token)
            if lead.get("aiScore") is not None:
                results[lead_id] = lead
                print(f"   {name:<18} scored {lead['aiScore']:3d}/{lead.get('aiScoreLabel'):<5} "
                      f"{lead.get('qualificationStatus')}")
        if len(results) < len(created):
            time.sleep(5)

    # --- verify ---------------------------------------------------------
    print("\nVERIFYING AGAINST EXPECTED")
    print("-" * 64)
    exact = 0
    unscored = 0
    for lead_id, name, expected, label in created:
        lead = results.get(lead_id)
        if lead is None:
            unscored += 1
            print(f"   {name:<18} NEVER SCORED — model unreachable, or async thread failed")
            continue
        ok = lead["aiScore"] == expected and lead.get("aiScoreLabel") == label
        exact += ok
        # Round-trip check on the V17 columns: a value that does not survive
        # the DTO/JPA layer would leave scoring correct but the record wrong.
        fields_ok = lead.get("purchaseTimeline") is not None and lead.get("productQuantity") is not None
        print(f"   {name:<18} expect {expected:3d}/{label:<5} got {lead['aiScore']:3d}/"
              f"{lead.get('aiScoreLabel'):<5} {'OK' if ok else 'DIFF'}"
              f"{'' if fields_ok else '   [V17 fields missing on read-back]'}")

    total = len(created)
    print("\n" + "=" * 64)
    print(f"   leads created        {total}/{len(LEAD_CASES)}")
    print(f"   scored by AI         {len(results)}/{total}")
    print(f"   exact score + label  {exact}/{total}")
    if unscored:
        print(f"   NEVER SCORED         {unscored}  <- silent failure path")
    print("=" * 64)
    return 0 if exact == total else 1


if __name__ == "__main__":
    sys.exit(main())
