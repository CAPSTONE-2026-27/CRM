"""
generate_dataset.py
===================
Builds the lead-scoring training set: a lead profile in, the scored text format
out. Replaces the earlier generator (and its ground_reasoning.py /
reformat_dataset.py post-processing), which were never committed.

What the model actually has to learn
------------------------------------
Four of the five scoring factors are fixed range lookups, and
prompt_format.reconcile_output() recomputes them -- and the total, and the
Hot/Warm/Cold band -- from the input after generation. The one judgement left
to the model is the Customer Requirement tier, read from the sales executive's
note. In production that field is "Notes from sales executive": free text.

The first dataset drew every requirement from 20 fixed phrases, so the model
never had to read one it had not memorised, and reconcile_output() could
override every one of them. That made held-out accuracy meaningless for the
only decision that mattered. This generator writes requirements the way reps
type them, from phrase banks per tier, and keeps a disjoint set of phrases for
the eval split only: eval measures reading unseen wording, not recall.

Tier meaning (also the labelling rule for eval/realistic.jsonl):
   0  no concrete need: general enquiry, wants to know what the product does
   5  exploratory: comparing vendors, collecting quotes, early research
  10  a defined, standard need: contact records, sales tracking, ticketing,
      dashboards, replacing spreadsheets
  15  a specific, demanding need: ERP/SAP/Tally integration, AI lead scoring,
      workflow automation, multi-branch approvals, API integration
  20  a buying signal: budget approved, ready to sign, finalising the vendor,
      wants a demo this week in order to purchase

Run:
    python adapters/lead_scoring/data/generate_dataset.py               # 900 rows
    python adapters/lead_scoring/data/generate_dataset.py --rows 1200
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR))
import prompt_format as fmt  # noqa: E402

OUT_PATH = ADAPTER_DIR / "data" / "train.jsonl"
SEED = 42
EVAL_FRACTION = 0.10

ACTIONS = {
    "Hot": "Assign immediately to a senior sales representative and initiate contact within 24 hours.",
    "Warm": "Add to the active follow-up queue and re-engage within 3-5 business days.",
    "Cold": "Add to the long-term nurture sequence and revisit in 30-60 days.",
}

# Band mix of the original 500 rows (50% Hot / 30% Warm / 20% Cold), nudged
# toward Cold so the Cold/Warm boundary -- where every held-out miss in the
# first run sat -- gets more examples.
BAND_WEIGHTS = {"Hot": 0.42, "Warm": 0.33, "Cold": 0.25}


# ============================================================
# REQUIREMENT PHRASES
# ============================================================
# TRAIN phrases appear only in the train split, EVAL phrases only in the eval
# split. Neither may reuse wording from eval/realistic.jsonl, which is the
# hand-written test set.

TRAIN_PHRASES = {
    0: [
        "Wanted to understand what the product does.",
        "Just making a general enquiry.",
        "Reached out with a casual query, nothing specific yet.",
        "Only gathering basic information for now.",
        "Filled the website form, said they were just curious",
        "Asked us to email a brochure, no specific requirement mentioned",
        "Called to ask what a CRM is and whether it is relevant to them",
        "General question about our website, no project behind it",
        "Visitor from the webinar, no need identified",
        "Wanted to know if we have an office in their city, nothing else",
        "Said they saw our ad and wanted to know more, no plans",
        "Downloaded the ebook, did not respond to follow-up questions",
        "intern from their team asking for info for a college project",
        "no requirement shared, just asked for company profile",
        # Contrast for tier 5: none of these mention price, quotes or vendors.
        "Asked who our existing clients are, nothing beyond that",
        "Clicked an ad by mistake, no interest",
        "Just wanted our office address for a delivery query",
        "Researcher writing an article about CRM trends",
        "Signed up for updates only, said they have no plans",
        "Asked what our company does, did not ask about products",
    ],
    5: [
        "Collecting quotations for a future decision.",
        "Exploring options available in the market.",
        "Comparing a few vendors before deciding.",
        "Doing early research on possible solutions.",
        "Shortlisting 3-4 CRM vendors, decision maybe next year",
        "Asked for a rough quote to compare with Zoho and Freshworks",
        "They are evaluating options, no fixed scope yet",
        "Benchmarking prices for next year's budget planning",
        "Looking around, wants to see what is available before committing",
        "Procurement asked for indicative pricing from several vendors",
        "early stage, building a list of possible tools",
        "wants to compare us vs hubspot, not decided anything",
        "researching options after their consultant suggested a CRM",
        "said they will review proposals from multiple vendors later this year",
        # Price and comparison requests in many shapes: the first adapter read
        # these as 0 when the wording was unfamiliar.
        "Requested our rate card to put alongside two other vendors",
        "Their procurement team issued an RFQ to several CRM suppliers",
        "Sent a tender document, we are one of five bidders",
        "Want ballpark pricing for an internal cost comparison",
        "An outside consultant is benchmarking CRM tools for them",
        "Currently trialling Pipedrive and want to see how we compare",
        "Unsure if they need a CRM at all, asked what it would cost",
        "Market study for next financial year, asked for standard prices",
        "asked for our pricing sheet, said they're weighing up options",
        "looking at 3 vendors, wants a feature + cost comparison",
        "board asked them to survey CRM options before any decision",
        "requested a budgetary estimate, no commitment yet",
    ],
    10: [
        "Interested in sales tracking and email automation.",
        "Looking for a CRM to manage customer records.",
        "Needs a better customer support ticketing system.",
        "Wants an analytics dashboard for the sales team.",
        "Need one place to store all customer contacts, currently in Excel",
        "Want to track leads and follow-ups for their 12-person sales team",
        "Support team needs a ticket system with email integration",
        "Looking for pipeline tracking and monthly sales reports",
        "wants to move off spreadsheets for managing their client list",
        "need basic lead management and reminders for follow ups",
        "Sales manager wants a dashboard showing team performance",
        "Need email campaigns plus a contact database",
        "want to log calls and meetings against each customer",
        "looking for a simple helpdesk to handle customer complaints",
        "Want a shared customer list with follow-up reminders for the team",
        "Need simple sales reports by region each month",
    ],
    15: [
        "Needs ERP integration with the existing system.",
        "Looking specifically for AI-based lead scoring.",
        "Wants workflow automation for the sales pipeline.",
        "Requires SAP integration across departments.",
        "Must integrate with Tally for invoicing and sync customer master",
        "Need automated approval workflows for discounts above 10%",
        "Want predictive lead scoring trained on their past deals",
        "Need two-way sync with their Oracle ERP for orders",
        "multi branch setup with role based access and regional approvals",
        "need REST API integration with their in-house order system",
        "want automatic lead assignment rules by territory and product",
        "require SAP S/4HANA connector for quote to cash",
        "need custom workflows to route service tickets by SLA",
        "want AI to prioritise which leads reps call first",
        "Quotes must sync automatically with their accounting software",
        "Need rules that route leads to reps by pin code",
    ],
    20: [
        "Ready to buy, only finalizing the vendor choice.",
        "Budget already approved and ready to move forward.",
        "Management has approved funds and wants to start soon.",
        "Wants a product demo this week before purchasing.",
        "CFO signed off the budget, asked us to send the contract",
        "They have issued a PO request internally, want final pricing today",
        "Decision made in our favour pending legal review of the agreement",
        "Approved budget for 50 licences, want to start onboarding next week",
        "asked for the order form, ready to sign this month",
        "final round between us and one other vendor, budget is approved",
        "wants demo on thursday for the director then will place order",
        "funds sanctioned, procurement asked for our vendor registration docs",
        "board approved the project, want to begin implementation immediately",
        "requested payment terms and invoice details to close the deal",
        "Legal is reviewing our agreement, purchase expected this month",
        "Management signed off, waiting only for our bank details to pay",
    ],
}

EVAL_PHRASES = {
    0: [
        "Asked a general question about what our software covers",
        "only wanted product literature, nothing planned",
        "random enquiry through chat, no business need stated",
        "wanted to know if we offer free trials, did not say why",
        "contacted us after a trade show, no requirement discussed",
    ],
    5: [
        "Gathering quotes to present alongside two competitors",
        "exploring tools, may revisit after the next financial year",
        "doing a market scan of CRM providers for their head office",
        "wants ballpark figures only, still deciding if they need a CRM",
        "evaluating a few platforms, has not set a scope or budget",
    ],
    10: [
        "Want to keep customer details and follow-up notes in one system",
        "need a ticketing tool so complaints stop getting lost in email",
        "wants reports on deals won and lost each month",
        "looking for contact management with email reminders",
        "need lead tracking for the inside sales team",
    ],
    15: [
        "Needs the CRM to sync with Microsoft Dynamics finance module",
        "want rules that auto-assign leads and escalate stale deals",
        "need SAP B1 integration for stock and order status",
        "looking for machine learning based lead prioritisation",
        "need API hooks into their e-commerce backend for orders",
    ],
    20: [
        "MD has approved the spend, they want the agreement by Friday",
        "selected us, waiting only on the final commercial sign off",
        "budget released, want to kick off the project next Monday",
        "ready to issue a purchase order once the quote is revised",
        "demo for the owner tomorrow, purchase planned right after",
    ],
}

CONTEXT_CLAUSES = [
    "", "", "",
    "Driven by the regional sales head.",
    "Part of an ongoing digital transformation.",
    "Operating across multiple branches.",
    "Expanding into new markets this year.",
    "Replacing an older in-house tool.",
    "The team is growing quickly.",
    "Following a recent internal review.",
    "Contact is the IT manager.",
    "Came through a partner referral.",
    "Spoke to the operations lead.",
]

REP_PREFIXES = [
    "", "", "", "",
    "Spoke to {name} - ",
    "Call with {name}: ",
    "{name} (IT head) says ",
    "Per {name}, ",
    "Inbound lead. ",
    "Met at expo. ",
]

CONTACT_NAMES = ["Rahul", "Priya", "Mr. Sharma", "Ms. Fernandes", "Arjun", "Neha", "Karthik",
                 "Mrs. Iyer", "David", "Fatima", "Sanjay", "Anita", "Mohammed", "Lakshmi"]


def render_requirement(rng: random.Random, phrase: str) -> str:
    """Dress a phrase the way it would land in a CRM notes field.

    The canonical template phrases are mostly left as written so the
    reconcile_output() lookup still sees them; everything else gets the mess of
    real notes -- a who-said-it prefix, a context clause, missing full stops.
    """
    canonical = phrase in fmt.CUSTOMER_REQUIREMENT_TIERS
    text = phrase
    if not canonical or rng.random() < 0.3:
        prefix = rng.choice(REP_PREFIXES).format(name=rng.choice(CONTACT_NAMES))
        if prefix and not canonical:
            text = prefix + (text[0].lower() + text[1:] if not text[:2].isupper() else text)
    clause = rng.choice(CONTEXT_CLAUSES)
    if clause:
        text = text.rstrip(".") + ". " + clause
    elif not canonical and rng.random() < 0.5:
        text = text.rstrip(".")
    return text.strip()


# ============================================================
# NUMERIC FACTORS
# ============================================================

def _tier_ranges(tiers):
    """[(low, high, points)] from the (upper bound, points) lookup table."""
    ranges, low = [], 1
    for upper, pts in tiers:
        ranges.append((low, upper, pts))
        low = upper + 1
    return ranges


EMPLOYEE_RANGES = _tier_ranges(fmt.EMPLOYEES_COUNT_TIERS)
EMPLOYEE_RANGES[-1] = (EMPLOYEE_RANGES[-1][0], 60_000, 20)
QUANTITY_RANGES = _tier_ranges(fmt.PRODUCT_QUANTITY_TIERS)
QUANTITY_RANGES[-1] = (QUANTITY_RANGES[-1][0], 5_000, 20)
DEAL_RANGES = _tier_ranges(fmt.DEAL_VALUE_TIERS)
DEAL_RANGES[0] = (25_000, DEAL_RANGES[0][1], 0)
DEAL_RANGES[-1] = (DEAL_RANGES[-1][0], 90_000_000, 20)


def _sample_in(rng, ranges, points):
    low, high, _ = next(r for r in ranges if r[2] == points)
    return rng.randint(low, high)


def format_inr(value: int) -> str:
    """Lakh/crore grouping, the only currency shape the model has ever seen."""
    digits = str(value)
    if len(digits) <= 3:
        return f"₹{digits}"
    head, tail = digits[:-3], digits[-3:]
    groups = []
    while len(head) > 2:
        groups.insert(0, head[-2:])
        head = head[:-2]
    if head:
        groups.insert(0, head)
    return "₹" + ",".join(groups) + "," + tail


# ============================================================
# COMPANIES
# ============================================================

NAME_FIRST = ["Pioneer", "Horizon", "Quantum", "Apex", "Silverline", "Bluestone", "Crescent", "Evergreen",
              "Summit", "Nova", "Vertex", "Orchid", "Sterling", "Lotus", "Harbor", "Redwood", "Zenith",
              "Saffron", "Indus", "Meridian", "Cobalt", "Northstar", "Granite", "Vista"]
NAME_SECTOR = ["Hospitality", "Real Estate", "IT", "Logistics", "Healthcare", "Retail", "Manufacturing",
               "Pharma", "Finance", "Education", "Foods", "Textiles", "Auto Components", "Telecom"]
NAME_SUFFIX = ["Pvt Ltd", "Group", "Technologies", "Solutions", "Ltd", "LLP", "Inc.", "K.K.", "GmbH",
               "Enterprises", "Services"]
LOCATIONS = ["Mumbai, India", "Delhi, India", "Bengaluru, India", "Pune, India", "Chennai, India",
             "Hyderabad, India", "Singapore, Singapore", "Dubai, UAE", "London, UK", "Paris, France",
             "Toronto, Canada", "Sydney, Australia", "New York, USA", "Tokyo, Japan", "Berlin, Germany"]


# ============================================================
# ROWS
# ============================================================

def _band(total: int) -> str:
    return next(qual for cutoff, qual, _ in fmt.QUALIFICATION_BY_SCORE if total <= cutoff)


def _sample_points(rng, band: str) -> list:
    """Five factor tiers whose sum lands in the requested band."""
    while True:
        pts = [rng.choice([0, 5, 10, 15, 20]) for _ in range(5)]
        if _band(sum(pts)) == band:
            return pts


def make_row(rng: random.Random, split: str) -> dict:
    band = rng.choices(list(BAND_WEIGHTS), weights=list(BAND_WEIGHTS.values()))[0]
    emp_pts, qty_pts, deal_pts, time_pts, req_pts = _sample_points(rng, band)

    employees = _sample_in(rng, EMPLOYEE_RANGES, emp_pts)
    quantity = _sample_in(rng, QUANTITY_RANGES, qty_pts)
    deal_value = _sample_in(rng, DEAL_RANGES, deal_pts)
    deal = format_inr(deal_value)
    # Values are drawn by tier (that is what spreads them across the range);
    # the points they earn are the continuous ones reconcile_output() serves.
    emp_pts = fmt.employees_points(employees)
    qty_pts = fmt.quantity_points(quantity)
    deal_pts = fmt.deal_value_points(deal_value)
    timeline = rng.choice([t for t, p in fmt.TIMELINE_POINTS.items() if p == time_pts])
    bank = TRAIN_PHRASES if split == "train" else EVAL_PHRASES
    requirement = render_requirement(rng, rng.choice(bank[req_pts]))

    company = f"{rng.choice(NAME_FIRST)} {rng.choice(NAME_SECTOR)} {rng.choice(NAME_SUFFIX)}"
    lines = [f"Company Name: {company}"]
    # The CRM bridge (server/main.py build_lead_input) never sends a location,
    # and the original data always did. Half and half, so neither shape is new.
    if rng.random() < 0.5:
        lines.append(f"Company Location: {rng.choice(LOCATIONS)}")
    lines += [
        f"Employees Count: {employees}",
        f"Product Quantity: {quantity}",
        f"Deal Value: {deal}",
        f"Purchase Timeline: {timeline}",
        f"Customer Requirement: {requirement}",
    ]

    total = emp_pts + qty_pts + deal_pts + time_pts + req_pts
    qualification, priority = next(
        (q, p) for cutoff, q, p in fmt.QUALIFICATION_BY_SCORE if total <= cutoff)
    bullets = [
        fmt._employees_bullet(employees, emp_pts),
        fmt._quantity_bullet(quantity, qty_pts),
        fmt._deal_value_bullet(deal, deal_pts),
        fmt._timeline_bullet(timeline, time_pts),
        fmt._requirement_bullet(requirement, req_pts),
    ]
    output = (
        f"Lead Score: {total}/100\n\n"
        f"Qualification:\n{qualification}\n\n"
        f"Priority:\n{priority}\n\n"
        "Reason:\n" + "\n".join(f"• {b}" for b in bullets) + "\n\n"
        f"Recommended Action:\n{ACTIONS[qualification]}"
    )
    return {
        "instruction": fmt.INSTRUCTION,
        "input": "\n".join(lines),
        "output": output,
        "split": split,
        "requirement_points": req_pts,
    }


def generate(rows: int, seed: int = SEED) -> list:
    rng = random.Random(seed)
    n_eval = round(rows * EVAL_FRACTION)
    out, seen = [], set()
    while len(out) < rows:
        split = "eval" if len(out) < n_eval else "train"
        row = make_row(rng, split)
        if row["input"] in seen:
            continue
        seen.add(row["input"])
        out.append(row)
    rng.shuffle(out)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=900)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--out", type=Path, default=OUT_PATH)
    args = parser.parse_args()

    rows = generate(args.rows, args.seed)
    # Every target must survive the serving path unchanged: reconcile_output()
    # recomputing it from the input has to agree with what was generated.
    for row in rows:
        if fmt.reconcile_output(row["output"], row["input"]) != row["output"]:
            raise SystemExit(f"Target disagrees with reconcile_output():\n{row['input']}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")

    bands, splits = {}, {}
    for row in rows:
        q = row["output"].split("Qualification:\n")[1].split("\n")[0]
        bands[q] = bands.get(q, 0) + 1
        splits[row["split"]] = splits.get(row["split"], 0) + 1
    print(f"Wrote {len(rows)} rows to {args.out}")
    print("Bands: " + ", ".join(f"{k}={v}" for k, v in sorted(bands.items())))
    print("Split: " + ", ".join(f"{k}={v}" for k, v in sorted(splits.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
