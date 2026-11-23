"""
rules.py
========
Port of the Java rules engine (marketing/leads/rules) for the dataset
generator: LeadFacts band parsing, RuleEngine.evaluate and combine.

The generator needs the exact PASS / FAIL / UNKNOWN the backend will put in
the prompt, or the adapter learns from rule results it will never be shown.
Java is the authority at runtime; tests/test_marketing_format.py pins the
cases that matter (bands, UNKNOWN on missing data, ALL/ANY combination).
"""

from __future__ import annotations

import re
from decimal import Decimal

LEVELS = ["Low", "Medium", "High"]

# RuleField: name -> (label, kind)
FIELDS = {
    "COMPANY_SIZE": ("Company size (employees)", "NUMBER"),
    "INDUSTRY": ("Industry", "TEXT"),
    "LOCATION": ("Location", "TEXT"),
    "CUSTOMER_TYPE": ("Customer type", "TEXT"),
    "LEAD_SOURCE": ("Lead source", "TEXT"),
    "PRODUCT_INTEREST": ("Product of interest", "TEXT"),
    "INTEREST_LEVEL": ("Product interest level", "LEVEL"),
    "ESTIMATED_DEAL_VALUE": ("Estimated deal value (₹)", "NUMBER"),
    "PURCHASE_TIMELINE_DAYS": ("Purchase timeline (days)", "NUMBER"),
    "LEAD_SCORE": ("CRM lead score", "NUMBER"),
    "PRODUCT_QUANTITY": ("Product quantity", "NUMBER"),
    "QUALIFICATION_STATUS": ("Qualification status", "TEXT"),
}

OPERATORS = {
    "GT": ">", "GTE": ">=", "LT": "<", "LTE": "<=", "EQ": "=", "NEQ": "!=",
    "IN": "is one of", "NOT_IN": "is not one of", "CONTAINS": "contains",
}

TIMELINE_DAYS = {
    "Immediately": (0, 0),
    "Within 15 Days": (0, 15),
    "Within 1 Month": (0, 30),
    "Within 2 Months": (0, 60),
    "Within 3 Months": (0, 90),
    "More than 3 Months": (91, None),
}

_NUMBER = re.compile(r"\d[\d,]*")


def parse_size(raw):
    """'51-200' -> (51, 200); '500+' -> (500, None); '140' -> (140, 140)."""
    if not raw or not str(raw).strip():
        return None
    nums = [int(n.replace(",", "")) for n in _NUMBER.findall(str(raw))[:2]]
    if not nums:
        return None
    if len(nums) == 2:
        return (min(nums), max(nums))
    first, lower = nums[0], str(raw).lower()
    if "+" in raw or "more" in lower or "above" in lower or "over" in lower:
        return (first, None)
    if "less" in lower or "under" in lower or "below" in lower or "<" in raw:
        return (0, first)
    return (first, first)


def describe(field: str, op: str, value: str) -> str:
    return f"{FIELDS[field][0]} {OPERATORS[op]} {value.strip()}"


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip().lower())


def _level_rank(level):
    if level is None:
        return -1
    for i, name in enumerate(LEVELS):
        if name.lower() == str(level).strip().lower():
            return i
    return -1


def _cmp(a, b) -> int:
    return (a > b) - (a < b)


def _range(op, x, lo, hi):
    if op == "GT":
        return "PASS" if lo > x else ("FAIL" if hi is not None and hi <= x else "UNKNOWN")
    if op == "GTE":
        return "PASS" if lo >= x else ("FAIL" if hi is not None and hi < x else "UNKNOWN")
    if op == "LT":
        return "PASS" if hi is not None and hi < x else ("FAIL" if lo >= x else "UNKNOWN")
    if op == "LTE":
        return "PASS" if hi is not None and hi <= x else ("FAIL" if lo > x else "UNKNOWN")
    if op == "EQ":
        if hi is not None and lo == x and hi == x:
            return "PASS"
        return "FAIL" if x < lo or (hi is not None and x > hi) else "UNKNOWN"
    if op == "NEQ":
        if hi is not None and lo == x and hi == x:
            return "FAIL"
        return "PASS" if x < lo or (hi is not None and x > hi) else "UNKNOWN"
    return "UNKNOWN"


def _exact(op, value, expected) -> str:
    c = _cmp(Decimal(str(value)), Decimal(str(expected)))
    ok = {"GT": c > 0, "GTE": c >= 0, "LT": c < 0, "LTE": c <= 0, "EQ": c == 0, "NEQ": c != 0}.get(op, False)
    return "PASS" if ok else "FAIL"


def _text(op, actual, expected) -> str:
    have = _norm(actual)
    options = {_norm(o) for o in expected.split(",") if _norm(o)}
    ok = {
        "EQ": have == _norm(expected),
        "NEQ": have != _norm(expected),
        "IN": have in options,
        "NOT_IN": have not in options,
        "CONTAINS": _norm(expected) in have,
    }.get(op, False)
    return "PASS" if ok else "FAIL"


def evaluate_one(field: str, op: str, expected: str, facts: dict) -> dict:
    """One rule against a lead. `facts` holds the raw lead attributes (see LeadFacts)."""
    label = describe(field, op, expected)
    expected = expected.strip()
    actual, outcome = None, "UNKNOWN"

    if field in ("COMPANY_SIZE", "PURCHASE_TIMELINE_DAYS"):
        if field == "COMPANY_SIZE":
            actual = facts.get("company_size")
            band = parse_size(actual)
        else:
            actual = facts.get("purchase_timeline")
            band = TIMELINE_DAYS.get((actual or "").strip())
        if band is not None:
            outcome = _range(op, float(expected), band[0], band[1])
    elif field in ("ESTIMATED_DEAL_VALUE", "LEAD_SCORE", "PRODUCT_QUANTITY"):
        key = {"ESTIMATED_DEAL_VALUE": "estimated_deal_value", "LEAD_SCORE": "crm_lead_score",
               "PRODUCT_QUANTITY": "product_quantity"}[field]
        value = facts.get(key)
        if value is not None:
            actual = str(value)
            outcome = _exact(op, value, expected)
    elif field == "INTEREST_LEVEL":
        actual = facts.get("interest_level")
        have = _level_rank(actual)
        if have >= 0:
            outcome = _exact(op, have, _level_rank(expected))
    else:
        key = {"INDUSTRY": "industry", "LOCATION": "location", "CUSTOMER_TYPE": "customer_type",
               "LEAD_SOURCE": "lead_source", "PRODUCT_INTEREST": "product_interest",
               "QUALIFICATION_STATUS": "qualification_status"}[field]
        actual = facts.get(key)
        if actual is not None:
            outcome = _text(op, actual, expected)

    return {"field": field, "operator": op, "expected": expected, "actual": actual,
            "outcome": outcome, "label": label}


def combine(results: list, mode: str) -> str:
    if not results:
        return "PASS"
    outcomes = {r["outcome"] for r in results}
    if mode == "ANY":
        if "PASS" in outcomes:
            return "PASS"
        return "UNKNOWN" if "UNKNOWN" in outcomes else "FAIL"
    if "FAIL" in outcomes:
        return "FAIL"
    return "UNKNOWN" if "UNKNOWN" in outcomes else "PASS"


def evaluate(criteria: list, match: str, facts: dict) -> tuple[list, str, str]:
    """criteria: [(field, op, value)] -> (results, combined, mode)."""
    mode = "ANY" if (match or "").upper() == "ANY" else "ALL"
    results = [evaluate_one(f, o, v, facts) for f, o, v in criteria]
    return results, combine(results, mode), mode


def prompt_lead(ref: str, facts: dict) -> dict:
    """LeadFacts.toPromptMap: only populated fields, in the Java order."""
    order = [
        ("industry", "industry"),
        ("company_size_employees", "company_size"),
        ("location", "location"),
        ("customer_type", "customer_type"),
        ("lead_source", "lead_source"),
        ("product_interest", "product_interest"),
        ("interest_level", "interest_level"),
        ("crm_lead_score", "crm_lead_score"),
        ("estimated_deal_value_inr", "estimated_deal_value"),
        ("purchase_timeline", "purchase_timeline"),
        ("product_quantity", "product_quantity"),
        ("qualification_status", "qualification_status"),
        ("contact_status", "contact_status"),
    ]
    out = {"lead_id": ref}
    for key, src in order:
        value = facts.get(src)
        if value is None:
            continue
        out[key] = str(value) if src == "estimated_deal_value" else value
    return out
