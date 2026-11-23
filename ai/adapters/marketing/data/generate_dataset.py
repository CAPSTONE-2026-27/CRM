"""
generate_dataset.py
===================
Builds the Marketing Automation training set: two tasks, one file.

    python adapters/marketing/data/generate_dataset.py leads   --rows 700
    python adapters/marketing/data/generate_dataset.py content --rows 320   # needs server on :8001
    python adapters/marketing/data/generate_dataset.py build

`leads`   -> data/leads.jsonl
`content` -> data/content.jsonl  (appends; safe to stop and re-run)
`build`   -> data/train.jsonl    (both, shuffled; what train.py reads)

Lead evaluation: a deterministic teacher
----------------------------------------
Campaigns, rules and leads are synthesised, the rules are evaluated by a port
of the Java RuleEngine (rules.py), and the target verdict comes from a fixed
fit table below. The generator is the ground truth, so every target:
  * never overturns a rule FAIL (the Java rule guard never has to fire),
  * cites only attributes the lead actually has,
  * lowers confidence for every missing attribute and UNKNOWN rule,
  * echoes lead_id exactly.
About 5% of rows carry LeadEvaluationAi's retry suffix, so a corrective retry
looks familiar rather than out of distribution.

Content generation: validated distillation
------------------------------------------
There is no ground-truth copy for a brief, and templated copy would teach the
adapter to write worse than the base model already does. So targets are the
base model's own replies (through the running server, adapter off), kept only
when every channel passes a port of ContentOutputValidator. Up to three samples
per brief; briefs that never pass are dropped. The adapter therefore learns the
base model's voice minus its failures: grounded figures, no invented claims,
lengths inside the limits, bare JSON on the first attempt.

Everything is synthetic. Validate on real campaigns before trusting the
adapter's numbers on real leads.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ADAPTER_DIR = Path(__file__).resolve().parent.parent
# Package imports: every adapter has a prompt_format.py, so a bare import can
# pick up another adapter's module when several are loaded in one process.
sys.path.insert(0, str(ADAPTER_DIR.parent.parent))
from adapters.marketing import prompt_format as fmt  # noqa: E402
from adapters.marketing import rules  # noqa: E402

DATA_DIR = ADAPTER_DIR / "data"
LEADS_PATH = DATA_DIR / "leads.jsonl"
CONTENT_PATH = DATA_DIR / "content.jsonl"
TRAIN_PATH = DATA_DIR / "train.jsonl"

# ============================================================
# VOCABULARY
# ============================================================
INDUSTRIES = ["IT Services", "Manufacturing", "Healthcare", "Retail", "Education", "Real Estate",
              "Financial Services", "Logistics", "Hospitality", "Telecom", "Pharmaceuticals", "Automotive"]
LOCATIONS = ["Mumbai", "Pune", "Bengaluru", "Delhi", "Hyderabad", "Chennai", "Ahmedabad", "Kolkata",
             "Jaipur", "Singapore", "Dubai", "London"]
CUSTOMER_TYPES = ["SMB", "Mid-Market", "Enterprise", "Startup", "Government"]
LEAD_SOURCES = ["Website", "Referral", "LinkedIn", "Trade Show", "Email Campaign", "Partner", "Cold Call"]
SIZE_BANDS = ["1-10", "11-50", "51-200", "201-500", "501-1000", "1000+"]
TIMELINES = list(rules.TIMELINE_DAYS)
QUALIFICATION = ["PENDING", "QUALIFIED", "UNQUALIFIED"]
CONTACT = ["New", "Contacted", "Follow Up", "Meeting Scheduled", "Not Reachable"]
CAMPAIGN_TYPES = ["product launch", "product promotion", "lead nurturing", "re engagement", "seasonal",
                  "event promotion", "customer retention", "cross sell", "upsell"]

PRODUCTS = [
    {"name": "TechCRM Sales Cloud", "desc": "A sales CRM that tracks leads, deals and pipeline stages in one place.",
     "benefits": "Faster follow-ups, clear pipeline visibility, fewer missed deals",
     "points": "Mobile app for field sales; offline record updates; WhatsApp and email logging"},
    {"name": "TechCRM Marketing Hub", "desc": "Campaign management with audience rules, content approval and analytics.",
     "benefits": "Targeted campaigns, approved content only, measurable results",
     "points": "Rule-based audiences; A/B testing; approval workflow"},
    {"name": "TechCRM Service Desk", "desc": "Ticketing and customer support with SLA tracking.",
     "benefits": "Quicker resolutions, happier customers, visible SLAs",
     "points": "Omnichannel inbox; SLA timers; knowledge base"},
    {"name": "TechCRM Analytics Pro", "desc": "Dashboards and forecasting on top of CRM data.",
     "benefits": "Reliable forecasts, one source of truth for sales numbers",
     "points": "Pipeline forecasting; custom dashboards; scheduled reports"},
    {"name": "TechCRM Field Force", "desc": "Field sales app with visit planning and GPS check-ins.",
     "benefits": "Better territory coverage, verified visits, less paperwork",
     "points": "Route planning; offline mode; photo capture at visits"},
    {"name": "TechCRM CPQ", "desc": "Configure, price and quote tool connected to the CRM.",
     "benefits": "Accurate quotes, faster approvals, consistent pricing",
     "points": "Discount approval rules; PDF quotes; product bundles"},
    {"name": "TechCRM Partner Portal", "desc": "A portal for channel partners to register deals and share leads.",
     "benefits": "Fewer channel conflicts, faster partner onboarding",
     "points": "Deal registration; partner tiers; shared lead inbox"},
    {"name": "TechCRM Onboarding Suite", "desc": "Guided onboarding and data migration for new CRM customers.",
     "benefits": "Go live sooner, clean migrated data, trained teams",
     "points": "Data import wizard; admin training; migration checklist"},
]

OBJECTIVES = {
    "product launch": "Introduce {p} to target accounts and book first demos",
    "product promotion": "Drive trials of {p} among qualified leads",
    "lead nurturing": "Move warm leads towards a sales conversation about {p}",
    "re engagement": "Re-engage leads who went quiet in the last quarter",
    "seasonal": "Close more {p} deals before the financial year ends",
    "event promotion": "Fill seats for the {p} product webinar",
    "customer retention": "Keep existing customers renewing and using {p}",
    "cross sell": "Offer {p} to customers already using another TechCRM product",
    "upsell": "Move current customers to a higher {p} plan",
}

OFFERS = [
    (None, None, None),
    ("15% off annual plans for new customers", "15%", None),
    ("Flat ₹5,000 off on onboarding for sign-ups this month", "₹5,000", None),
    ("Two months free on annual billing", None, None),
    ("20% off the first year for teams of 25 users or more", "20%", None),
    (None, None, "Starts at ₹1,499 per user per month"),
    (None, None, "Plans from ₹999 per user per month, billed annually"),
    ("Complimentary data migration for the first 50 customers", None, "Starts at ₹2,499 per user per month"),
    ("10% off when you bundle two TechCRM products", "10%", None),
]

CTAS = ["Book a demo", "Start your trial", "Talk to sales", "Register now", "Get a quote", "Upgrade today",
        "Reserve your seat", "Learn more"]
TONES = ["Professional", "Friendly", "Persuasive", "Formal", "Enthusiastic", "Informative", "Conversational"]
STYLES = [None, None, "Concise", "Benefit-led", "Storytelling", "Direct response"]
EXTRA_INSTRUCTIONS = [
    None, None, None,
    "Keep sentences short.",
    "Address operations and sales heads.",
    "Mention the live webinar on 15 March at 11 AM.",
    "Avoid jargon; write for first-time CRM buyers.",
    "Use {{first_name}} in the greeting.",
    "Refer to the reader's company as {{company}} once.",
]
FEEDBACK = [
    "Make it shorter.", "Sound more formal.", "Lead with the main benefit.", "Make the CTA more direct.",
    "Less salesy, more informative.",
]


# ============================================================
# CAMPAIGNS
# ============================================================
def _pick(rng, pool, lo, hi):
    return rng.sample(pool, rng.randint(lo, hi))


def make_audience(rng, product):
    a = {
        "industries": _pick(rng, INDUSTRIES, 1, 3),
        "companySize": rng.choice([None, "11-50", "51-200", "201-500", "200+", "50+", "501-1000"]),
        "locations": _pick(rng, LOCATIONS, 0, 3) if rng.random() < 0.6 else [],
        "customerTypes": _pick(rng, CUSTOMER_TYPES, 1, 2) if rng.random() < 0.5 else [],
        "leadSources": _pick(rng, LEAD_SOURCES, 1, 2) if rng.random() < 0.15 else [],
        "productInterest": product["name"] if rng.random() < 0.5 else None,
        "minDealValue": rng.choice([None, None, 200000, 500000, 1000000]),
        "maxDealValue": rng.choice([None, None, None, 5000000, 20000000]),
        "purchaseTimeline": rng.choice([None, None, "Within 3 Months", "Within 1 Month"]),
        "customerSegment": rng.choice([None, "Growing mid-market teams", "Enterprise IT buyers",
                                       "Owner-led small businesses", "Multi-branch retailers"]),
        "customerStatus": rng.choice(["ANY", "ANY", "NEW_LEADS", "EXISTING_CUSTOMERS"]),
    }
    return a


def describe_audience(a) -> str:
    """ContentSource.describeAudience."""
    parts = []

    def add(label, value):
        if value:
            parts.append(f"{label}: {value}")

    add("Industries", ", ".join(a["industries"]))
    add("Company size", a["companySize"])
    add("Locations", ", ".join(a["locations"]))
    add("Customer types", ", ".join(a["customerTypes"]))
    add("Lead sources", ", ".join(a["leadSources"]))
    add("Product interest", a["productInterest"])
    if a["minDealValue"] is not None or a["maxDealValue"] is not None:
        lo = "any" if a["minDealValue"] is None else str(a["minDealValue"])
        hi = "any" if a["maxDealValue"] is None else str(a["maxDealValue"])
        add("Deal value (INR)", f"{lo} to {hi}")
    add("Purchase timeline", a["purchaseTimeline"])
    if a["customerStatus"] not in (None, "ANY"):
        add("Customer status", "existing customers" if a["customerStatus"] == "EXISTING_CUSTOMERS" else "new leads")
    return "; ".join(parts)


def make_criteria(rng, a):
    pool = [
        ("INDUSTRY", "IN", ", ".join(a["industries"])),
        ("COMPANY_SIZE", "GTE", rng.choice(["20", "50", "100", "200"])),
        ("INTEREST_LEVEL", "GTE", rng.choice(["Medium", "High"])),
        ("ESTIMATED_DEAL_VALUE", "GTE", rng.choice(["300000", "500000", "1000000"])),
        ("PURCHASE_TIMELINE_DAYS", "LTE", rng.choice(["30", "60", "90"])),
        ("LEAD_SCORE", "GTE", rng.choice(["40", "50", "60"])),
        ("QUALIFICATION_STATUS", "NEQ", "UNQUALIFIED"),
    ]
    if a["locations"]:
        pool.append(("LOCATION", "IN", ", ".join(a["locations"])))
    if a["customerTypes"]:
        pool.append(("CUSTOMER_TYPE", "IN", ", ".join(a["customerTypes"])))
    n = rng.choices([0, 1, 2, 3], weights=[15, 35, 35, 15])[0]
    return rng.sample(pool, n), ("ANY" if rng.random() < 0.2 else "ALL")


def make_campaign(rng):
    product = rng.choice(PRODUCTS)
    ctype = rng.choice(CAMPAIGN_TYPES)
    a = make_audience(rng, product)
    criteria, match = make_criteria(rng, a)
    brief = {"campaign_type": ctype, "objective": OBJECTIVES[ctype].format(p=product["name"]),
             "product_name": product["name"]}
    audience = describe_audience(a)
    if audience:
        brief["target_audience"] = audience
    if a["customerSegment"]:
        brief["customer_segment"] = a["customerSegment"]
    return {"product": product, "type": ctype, "audience": a, "criteria": criteria, "match": match, "brief": brief}


# ============================================================
# LEADS
# ============================================================
def make_lead(rng, c):
    a = c["audience"]
    fit = rng.random() < 0.5  # half the leads are drawn toward the target audience

    def maybe(value, p_missing=0.12):
        return None if rng.random() < p_missing else value

    industry = rng.choice(a["industries"]) if fit else rng.choice(INDUSTRIES)
    size = rng.choice(SIZE_BANDS) if rng.random() < 0.8 else str(rng.randint(5, 3000))
    location = rng.choice(a["locations"]) if fit and a["locations"] else rng.choice(LOCATIONS)
    ctype = rng.choice(a["customerTypes"]) if fit and a["customerTypes"] else rng.choice(CUSTOMER_TYPES)
    product = c["product"]["name"] if (fit and rng.random() < 0.7) else rng.choice(PRODUCTS)["name"]
    level = rng.choices(["High", "Medium", "Low"], weights=[4, 4, 2] if fit else [2, 4, 4])[0]
    value = rng.choice([80000, 150000, 350000, 600000, 900000, 1500000, 2500000, 4000000, 8000000, 25000000])
    value = value + rng.randint(0, 99) * 1000
    facts = {
        "industry": maybe(industry),
        "company_size": maybe(size, 0.15),
        "location": maybe(location),
        "customer_type": maybe(ctype, 0.2),
        "lead_source": maybe(rng.choice(LEAD_SOURCES), 0.2),
        "product_interest": maybe(product, 0.15),
        "interest_level": maybe(level, 0.1),
        "crm_lead_score": maybe(rng.randint(15, 95), 0.15),
        "estimated_deal_value": maybe(f"{value}.00" if rng.random() < 0.7 else str(value), 0.2),
        "purchase_timeline": maybe(rng.choice(TIMELINES), 0.15),
        "product_quantity": maybe(rng.randint(1, 1500), 0.3),
        "qualification_status": maybe(rng.choices(QUALIFICATION, weights=[5, 3, 2])[0], 0.1),
        "contact_status": maybe(rng.choice(CONTACT), 0.25),
    }
    return "LD" + str(rng.randint(100, 99999)), facts


# ============================================================
# TEACHER — the target verdict for one lead
# ============================================================
def _overlap(band_a, band_b) -> bool:
    lo_a, hi_a = band_a
    lo_b, hi_b = band_b
    return (hi_b is None or lo_a <= hi_b) and (hi_a is None or lo_b <= hi_a)


def _inr(value) -> str:
    return "₹" + f"{int(float(value)):,}"


def teacher(rng, c, facts, results, combined, mode):
    a = c["audience"]
    pos, neg = [], []  # (points, text)
    fit = 50

    def plus(points, text):
        nonlocal fit
        fit += points
        (pos if points > 0 else neg).append((abs(points), text))

    ind = facts.get("industry")
    if ind:
        if ind in a["industries"]:
            plus(12, f"Industry {ind} is one of the campaign's target industries")
        else:
            plus(-15, f"Industry {ind} is outside the target industries ({', '.join(a['industries'])})")

    size = facts.get("company_size")
    if size and a["companySize"]:
        lead_band, target_band = rules.parse_size(size), rules.parse_size(a["companySize"])
        if lead_band and target_band:
            if _overlap(lead_band, target_band):
                plus(8, f"Company size {size} employees fits the targeted {a['companySize']}")
            else:
                plus(-8, f"Company size {size} employees is outside the targeted {a['companySize']}")

    loc = facts.get("location")
    if loc and a["locations"]:
        if loc in a["locations"]:
            plus(5, f"Located in {loc}, a target location")
        else:
            plus(-8, f"Location {loc} is not among the target locations")

    ct = facts.get("customer_type")
    if ct and a["customerTypes"]:
        if ct in a["customerTypes"]:
            plus(6, f"Customer type {ct} matches the campaign audience")
        else:
            plus(-6, f"Customer type {ct} is not a targeted customer type")

    pi = facts.get("product_interest")
    if pi:
        if pi == c["product"]["name"]:
            plus(8, f"Interested in {pi}, the product this campaign promotes")
        else:
            plus(-4, f"Product interest is {pi}, not {c['product']['name']}")

    level = facts.get("interest_level")
    if level == "High":
        plus(12, "High product interest level (Hot lead)")
    elif level == "Medium":
        plus(4, "Medium product interest level (Warm lead)")
    elif level == "Low":
        plus(-10, "Low product interest level (Cold lead)")

    tl = facts.get("purchase_timeline")
    if tl:
        days = rules.TIMELINE_DAYS[tl]
        if days[1] is not None and days[1] <= 30:
            plus(8, f"Purchase timeline '{tl}' signals near-term buying")
        elif days[1] is not None and days[1] <= 90:
            plus(3, f"Purchase timeline '{tl}' is within the next quarter")
        else:
            plus(-6, f"Purchase timeline '{tl}' is more than three months away")

    dv = facts.get("estimated_deal_value")
    if dv and (a["minDealValue"] is not None or a["maxDealValue"] is not None):
        v = float(dv)
        inside = (a["minDealValue"] is None or v >= a["minDealValue"]) and \
                 (a["maxDealValue"] is None or v <= a["maxDealValue"])
        if inside:
            plus(6, f"Estimated deal value {_inr(dv)} is within the targeted range")
        else:
            plus(-6, f"Estimated deal value {_inr(dv)} is outside the targeted range")

    score = facts.get("crm_lead_score")
    if score is not None:
        delta = round((score - 50) / 5)
        if delta >= 4:
            plus(delta, f"CRM lead score of {score} is strong")
        elif delta <= -4:
            plus(delta, f"CRM lead score of {score} is low")
        else:
            fit += delta

    q = facts.get("qualification_status")
    if q == "QUALIFIED":
        plus(5, "Already qualified by the sales team")
    elif q == "UNQUALIFIED":
        plus(-12, "Marked unqualified by the sales team")

    if facts.get("contact_status") == "Not Reachable":
        plus(-5, "Contact status is Not Reachable")

    unknown = [r for r in results if r["outcome"] == "UNKNOWN"]
    failed = [r for r in results if r["outcome"] == "FAIL"]
    passed = [r for r in results if r["outcome"] == "PASS"]
    for r in passed[:2]:
        pos.append((3, f"Meets rule: {r['label']}"))
        fit += 3
    for r in unknown:
        fit -= 4
        why = "not recorded" if r["actual"] is None else f"'{r['actual']}' is not precise enough"
        neg.append((4, f"Rule could not be verified: {r['label']} ({why})"))

    fit = max(5, min(97, fit))
    rule_fail = combined == "FAIL"
    if rule_fail:
        for r in failed:
            lead_value = "not recorded" if r["actual"] is None else r["actual"]
            neg.insert(0, (100, f"Fails required rule: {r['label']} (lead: {lead_value})"))
        final = min(round(fit * 0.6), 55)
        eligible = False
    else:
        final = fit
        eligible = fit >= 60

    important = ["industry", "company_size", "interest_level", "estimated_deal_value",
                 "purchase_timeline", "product_interest"]
    missing = [k for k in important if facts.get(k) is None]
    confidence = 0.92 - 0.07 * len(missing) - 0.07 * len(unknown)
    confidence = round(max(0.35, min(0.95, confidence)), 2)

    pos.sort(key=lambda x: -x[0])
    neg.sort(key=lambda x: -x[0])
    positives = [t for _, t in pos[:5]]
    negatives = [t for _, t in neg[:5]]
    if len(negatives) < 5 and missing and rng.random() < 0.6:
        names = {"industry": "industry", "company_size": "company size", "interest_level": "interest level",
                 "estimated_deal_value": "deal value", "purchase_timeline": "purchase timeline",
                 "product_interest": "product interest"}
        negatives.append("No " + " or ".join(names[m] for m in missing[:2]) + " recorded, which lowers confidence")

    reason = _reason(rng, c, final, eligible, rule_fail, failed, unknown, positives, negatives)
    return {
        "score": int(final),
        "eligibility": "eligible" if eligible else "not_eligible",
        "confidence": confidence,
        "positive_factors": positives,
        "negative_factors": negatives,
        "reason": reason,
    }


def _lc(text: str) -> str:
    return text[0].lower() + text[1:] if text else text


def _band(score: int) -> str:
    return "strong" if score >= 80 else "reasonable" if score >= 60 else "weak" if score >= 40 else "poor"


def _reason(rng, c, score, eligible, rule_fail, failed, unknown, positives, negatives) -> str:
    ctype = c["type"]
    sentences = []
    if rule_fail:
        r = failed[0]
        lead_value = "not recorded" if r["actual"] is None else r["actual"]
        sentences.append(f"Not eligible because the lead fails the required rule '{r['label']}' "
                         f"(lead value: {lead_value}).")
        if positives:
            sentences.append(f"Apart from that, {_lc(positives[0])}, but the rule takes precedence.")
    elif eligible:
        opener = rng.choice(["{B} fit for this {t} campaign", "A {b} fit for the {t} campaign"])
        opener = opener.format(B=_band(score).capitalize(), b=_band(score), t=ctype)
        if len(positives) >= 2:
            sentences.append(f"{opener}: {_lc(positives[0])} and {_lc(positives[1])}.")
        elif positives:
            sentences.append(f"{opener}: {_lc(positives[0])}.")
        else:
            sentences.append(f"{opener} based on the recorded attributes.")
        real_negs = [n for n in negatives if not n.startswith("Rule could not") and not n.startswith("No ")]
        if real_negs:
            sentences.append(f"{real_negs[0]}, which lowers the score.")
    else:
        opener = f"{_band(score).capitalize()} fit for this {ctype} campaign"
        real_negs = [n for n in negatives if not n.startswith("Rule could not")]
        if len(real_negs) >= 2:
            sentences.append(f"{opener}: {_lc(real_negs[0])} and {_lc(real_negs[1])}.")
        elif real_negs:
            sentences.append(f"{opener}: {_lc(real_negs[0])}.")
        else:
            sentences.append(f"{opener}: too few recorded attributes match the campaign audience.")
    if unknown and not rule_fail:
        sentences.append(f"The rule '{unknown[0]['label']}' could not be checked from the recorded data, "
                         "so it was weighed using the other attributes.")
    return " ".join(sentences[:3])


def lead_rows(n: int, seed: int) -> list:
    rng = random.Random(seed)
    rows = []
    campaign = None
    for i in range(n):
        if campaign is None or i % 6 == 0:  # several leads per campaign, like a real run
            campaign = make_campaign(rng)
        ref, facts = make_lead(rng, campaign)
        results, combined, mode = rules.evaluate(campaign["criteria"], campaign["match"], facts)
        target = teacher(rng, campaign, facts, results, combined, mode)
        target = {"lead_id": ref, **target}
        user = fmt.build_lead_user_prompt(campaign["brief"], mode, results, rules.prompt_lead(ref, facts))
        if rng.random() < 0.05:
            user = fmt.lead_retry_prompt(user, rng.choice([
                "Reply was not a JSON object",
                f"lead_id did not match the lead that was sent (expected {ref})",
                "score must be between 0 and 100",
            ]))
        text = fmt.render_target(target)
        fmt.parse_lead_evaluation(text, ref)  # the backend must accept every target
        rows.append({
            "task": "lead_evaluation",
            "messages": [
                {"role": "system", "content": fmt.LEAD_SYSTEM_PROMPT},
                {"role": "user", "content": user},
                {"role": "assistant", "content": text},
            ],
            "meta": json.dumps({"combined": combined, "mode": mode, "eligible": target["eligibility"],
                                "score": target["score"]}),
        })
    return rows


# ============================================================
# CONTENT — briefs and distillation
# ============================================================
def make_content_request(rng):
    product = rng.choice(PRODUCTS)
    ctype = rng.choice(CAMPAIGN_TYPES)
    a = make_audience(rng, product)
    offer, discount, pricing = rng.choice(OFFERS)
    start = date(2026, rng.randint(1, 12), rng.randint(1, 28))
    brief = {
        "campaign_name": f"{product['name'].replace('TechCRM ', '')} {ctype.title()} {start.year}",
        "campaign_type": ctype,
        "objective": OBJECTIVES[ctype].format(p=product["name"]),
    }
    if rng.random() < 0.5:
        brief["description"] = f"{ctype.capitalize()} campaign for {product['name']}. {product['desc']}"
    if rng.random() < 0.6:
        brief["start_date"] = start.isoformat()
        brief["end_date"] = (start + timedelta(days=rng.choice([14, 21, 30, 45]))).isoformat()
    brief["product_name"] = product["name"]
    brief["product_description"] = product["desc"]
    if rng.random() < 0.8:
        brief["main_benefits"] = product["benefits"]
    if offer:
        brief["offer"] = offer
    if discount:
        brief["discount"] = discount
    if pricing:
        brief["pricing"] = pricing
    if rng.random() < 0.7:
        brief["key_selling_points"] = product["points"]
    brief["call_to_action"] = rng.choice(CTAS)
    audience = describe_audience(a)
    if audience:
        brief["target_audience"] = audience
    if a["customerSegment"]:
        brief["customer_segment"] = a["customerSegment"]

    style = {"tone": rng.choice(TONES), "language": "English"}
    ws = rng.choice(STYLES)
    if ws:
        style["writing_style"] = ws
    extra = rng.choice(EXTRA_INSTRUCTIONS)
    if extra:
        style["additional_instructions"] = extra

    all_channels = ["core", "email", "poster", "sms", "notification", "social"]
    chosen = _pick(rng, all_channels[1:], 1, 3)
    if rng.random() < 0.7:
        chosen = ["core"] + chosen
    channels = [ch for ch in all_channels if ch in chosen]
    feedback = rng.choice(FEEDBACK) if rng.random() < 0.1 else None
    return brief, style, channels, feedback


def _post(server: str, system: str, user: str, temperature: float, timeout: int) -> str:
    body = json.dumps({
        "model": "crm-llama-3.1-8b-lora",
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": temperature,
        "max_tokens": 1800,
    }).encode("utf-8")
    req = urllib.request.Request(server.rstrip("/") + "/v1/chat/completions", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return payload["choices"][0]["message"]["content"]


def _distill_one(server, brief, style, channels, variant, feedback, attempts, timeout):
    user = fmt.build_content_user_prompt(brief, style, channels, variant, feedback)
    grounding = fmt.grounding_text(brief, style)
    for attempt in range(attempts):
        # First try at the backend's own temperature; later tries a little hotter for a different sample.
        temperature = 0.4 if attempt == 0 else 0.7
        reply = _post(server, fmt.CONTENT_SYSTEM_PROMPT, user, temperature, timeout)
        accepted, rejected = fmt.check_content_reply(reply, channels, grounding)
        if not rejected:
            target = {ch: accepted[ch] for ch in channels}
            # Valid, but too close to a limit to teach from: see LENGTH_MARGIN.
            if fmt.over_margin(target):
                continue
            return user, target, attempt + 1
    return user, None, attempts


def _tested_channel(element, channels):
    ch = fmt.AB_ELEMENTS[element]
    if ch is not None:
        return ch if ch in channels else None
    for d in fmt.DELIVERABLE:
        if d in channels:
            return d
    return channels[0] if channels else None


def distill(server: str, n: int, seed: int, attempts: int, timeout: int) -> None:
    done = 0
    if CONTENT_PATH.exists():
        done = sum(1 for _ in CONTENT_PATH.open(encoding="utf-8"))
    print(f"{done} content rows already in {CONTENT_PATH.name}; target {n}")
    # One RNG per brief, seeded by its index, so a restart continues where it
    # stopped instead of repeating briefs already written.
    state_path = DATA_DIR / "content.state.json"
    tried = json.loads(state_path.read_text())["tried"] if state_path.exists() else 0

    started = time.time()
    session_tried = 0
    with CONTENT_PATH.open("a", encoding="utf-8") as out:
        while done < n:
            rng = random.Random(seed * 1_000_003 + tried)
            brief, style, channels, feedback = make_content_request(rng)
            want_variant = rng.random()
            tried += 1
            session_tried += 1
            state_path.write_text(json.dumps({"tried": tried}))
            try:
                user, target, used = _distill_one(server, brief, style, channels, None, feedback, attempts, timeout)
            except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError) as e:
                print(f"  request failed ({e}); is the server up at {server}?")
                time.sleep(5)
                continue
            if target is None:
                print(f"  [{tried}] dropped: no valid reply in {attempts} samples")
                continue
            rows = [(user, target, None)]

            # One in five: also write variant B of an A/B test against this A.
            if want_variant < 0.2:
                options = [e for e in fmt.AB_ELEMENTS if _tested_channel(e, channels)]
                element = rng.choice(options)
                tested = _tested_channel(element, channels)
                variant = {"element": element, "variant_a": {tested: target[tested]}}
                try:
                    vuser, vtarget, _ = _distill_one(server, brief, style, [tested], variant, None, attempts, timeout)
                    if vtarget is not None and vtarget[tested] != target[tested]:
                        rows.append((vuser, vtarget, element))
                except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError):
                    pass

            for u, t, element in rows:
                out.write(json.dumps({
                    "task": "content_generation",
                    "messages": [
                        {"role": "system", "content": fmt.CONTENT_SYSTEM_PROMPT},
                        {"role": "user", "content": u},
                        {"role": "assistant", "content": fmt.render_target(t)},
                    ],
                    "meta": json.dumps({"channels": list(t), "variant_element": element, "samples": used}),
                }, ensure_ascii=False) + "\n")
                out.flush()
                done += 1
            rate = (time.time() - started) / max(1, session_tried)
            print(f"  [{done}/{n}] kept ({'+B ' if len(rows) > 1 else ''}{used} sample(s)); "
                  f"{rate:.0f}s per brief")


def build(seed: int) -> None:
    rows = []
    for path in (LEADS_PATH, CONTENT_PATH):
        if not path.exists():
            raise FileNotFoundError(f"{path} missing. Run the 'leads' and 'content' steps first.")
        rows += [json.loads(line) for line in path.open(encoding="utf-8") if line.strip()]
    # content.jsonl rows distilled before LENGTH_MARGIN existed can sit right at
    # a limit; they stay on disk but are not trained on.
    kept = [r for r in rows if r["task"] != "content_generation"
            or not fmt.over_margin(json.loads(r["messages"][2]["content"]))]
    if len(kept) != len(rows):
        print(f"Skipped {len(rows) - len(kept)} content rows over {fmt.LENGTH_MARGIN:.0%} "
              "of a character limit")
    rows = kept
    random.Random(seed).shuffle(rows)
    with TRAIN_PATH.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
    tasks = {}
    for row in rows:
        tasks[row["task"]] = tasks.get(row["task"], 0) + 1
    print(f"Wrote {len(rows)} rows to {TRAIN_PATH}: {tasks}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_leads = sub.add_parser("leads")
    p_leads.add_argument("--rows", type=int, default=700)
    p_leads.add_argument("--seed", type=int, default=42)
    p_content = sub.add_parser("content")
    p_content.add_argument("--rows", type=int, default=320)
    p_content.add_argument("--seed", type=int, default=7)
    p_content.add_argument("--server", default="http://localhost:8001")
    p_content.add_argument("--attempts", type=int, default=3)
    p_content.add_argument("--timeout", type=int, default=300)
    p_build = sub.add_parser("build")
    p_build.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if args.cmd == "leads":
        rows = lead_rows(args.rows, args.seed)
        with LEADS_PATH.open("w", encoding="utf-8") as out:
            for row in rows:
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
        elig = sum(1 for r in rows if json.loads(r["meta"])["eligible"] == "eligible")
        fails = sum(1 for r in rows if json.loads(r["meta"])["combined"] == "FAIL")
        print(f"Wrote {len(rows)} lead rows to {LEADS_PATH}: {elig} eligible, {fails} rule-fail")
    elif args.cmd == "content":
        distill(args.server, args.rows, args.seed, args.attempts, args.timeout)
    else:
        build(args.seed)


if __name__ == "__main__":
    main()
