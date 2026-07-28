"""
generate_training_data.py
==========================

Regenerates data/train.jsonl for the CRM lead-scoring fine-tune.

Why this exists: the original 50-example dataset was a repurposed public
"Leads" dataset (Lead Origin, Lead Source, Total Visits, Specialization...)
with only two distinct output strings across all 50 rows. It shares no
fields with what the real CRM app actually sends, and never taught the
model to output JSON — every JSON response you've seen from serve.py was
the base Llama-Instruct model improvising, not something this fine-tune
learned. That's why scoring intermittently falls back to free text and
serve.py's UNSCORED path fires.

This script generates synthetic-but-principled examples instead:
  - The "instruction"+"input" text is built to EXACTLY match what
    serve.py's build_lead_message() sends at inference (same field names,
    same JSON-format instruction), so train/inference distributions line
    up 1:1.
  - The "output" is the JSON object directly: {"score", "label", "reason"}.
  - Ground truth comes from a deterministic scoring function (engagement
    signal tier + deal size, with jitter) rather than another LLM
    improvising — the training signal is consistent and learnable.

Run:
    python scripts/generate_training_data.py [--count 300] [--seed 42]
"""

import argparse
import json
import random
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = PROJECT_ROOT / "data" / "train.jsonl"

INSTRUCTION = "Analyze the following sales lead and score it for conversion likelihood."

RESPONSE_FORMAT_HINT = (
    "Respond with ONLY a JSON object in this exact format:\n"
    '{"score": <integer 0-100>, "label": "HOT" | "WARM" | "COLD", "reason": "<one sentence>"}'
)

# ============================================================
# LEAD ATTRIBUTE POOLS
# ============================================================

FIRST_NAMES = [
    "Priya", "Rahul", "Ananya", "John", "Mei", "Carlos", "Sara", "David",
    "Fatima", "Tom", "Elena", "Kenji", "Grace", "Omar", "Lucia", "Noah",
    "Aisha", "Marcus", "Ingrid", "Diego", "Yuki", "Sofia", "Liam", "Chidi",
]
LAST_NAMES = [
    "Sharma", "Verma", "Iyer", "Okafor", "Lin", "Mendes", "Kim", "Thompson",
    "Al-Sayed", "Becker", "Petrova", "Tanaka", "Adeyemi", "Rossi", "Novak",
    "Nakamura", "Silva", "Johansson", "Fischer", "Nguyen", "Osei", "Andersen",
]
COMPANY_PREFIXES = [
    "Summit", "Bright", "Nimbus", "Prime", "Global", "Vertex", "Northwind",
    "Cascade", "Beacon", "Ironclad", "Bluepeak", "Redwood", "Silverline",
    "Trueform", "Anchor", "Skyline", "Fieldstone", "Lucent", "Meridian",
]
COMPANY_SUFFIXES = [
    "Technologies", "Solutions", "Industries", "Group", "Partners", "Labs",
    "Systems", "Holdings", "Ventures", "Networks", "Capital", "Studio",
    "Logistics", "Foods", "Health", "Retail", "Manufacturing",
]
INDUSTRIES = [
    "Healthcare", "Technology", "Finance", "Retail", "Manufacturing",
    "Education", "Hospitality", "Legal", "Real Estate", "Construction",
    "Marketing", "Logistics", "Non-profit", "Government", "Media",
    "Insurance", "Telecommunications", "Agriculture",
]
EMPLOYEE_COUNTS = ["1-10", "10-50", "50-200", "200-500", "500-1000", "1000+"]
PRODUCTS = ["CRM Lite", "CRM Suite", "CRM Enterprise", "Analytics Add-on", "Marketing Automation Module"]
SOURCE_CHANNELS = [
    "Website", "Referral", "Cold Call", "Trade Show", "LinkedIn",
    "Organic Search", "Partner Referral", "Email Campaign",
]

# (min, max) deal value by tier, and a scoring bonus applied at that tier
DEAL_VALUE_TIERS = [
    ("small", 1_000, 20_000, 0),
    ("medium", 20_000, 150_000, 3),
    ("large", 150_000, 2_000_000, 8),
    ("enterprise", 2_000_000, 15_000_000, 14),
]

# ============================================================
# ENGAGEMENT TIERS: (notes template, reason template, score range)
# reason templates may reference {company} for specificity.
# ============================================================

HIGH_ENGAGEMENT = [
    ("The CFO personally requested a live demo next week.",
     "The customer shows strong buying intent as the CFO has personally requested a demo."),
    ("Budget approved, ready to sign this quarter.",
     "Budget is already approved and the customer is ready to move forward this quarter."),
    ("Multiple stakeholders engaged, RFP in progress.",
     "Multiple stakeholders at {company} are engaged and an RFP is already in progress."),
    ("VP of Sales personally reached out asking for pricing.",
     "A VP-level decision maker reached out directly asking for pricing, a strong buying signal."),
    ("Champion internally pushing for approval, comparing us against one competitor.",
     "An internal champion is actively pushing for approval, indicating high conversion likelihood."),
    ("Signed NDA and requested a technical deep-dive session.",
     "The customer signed an NDA and requested a technical deep-dive, showing serious evaluation intent."),
    ("Procurement has started contract review after a successful pilot.",
     "A successful pilot has moved this lead into contract review with procurement."),
    ("CEO referred us directly and asked for a proposal by Friday.",
     "A direct CEO referral with a proposal deadline signals urgent, high-priority interest."),
    ("Renewal upsell conversation, existing customer expanding to enterprise tier.",
     "An existing customer is expanding their contract, a very high-confidence conversion signal."),
    ("Attended 3 demos, asked detailed implementation and SLA questions.",
     "Repeated demo attendance with detailed implementation questions shows strong purchase intent."),
]

MEDIUM_ENGAGEMENT = [
    ("Attended a webinar and asked detailed pricing questions.",
     "Attending the webinar and asking pricing questions shows moderate engagement but no confirmed urgency."),
    ("Downloaded the pricing PDF and opened 3 follow-up emails.",
     "Downloading pricing materials and opening follow-up emails shows interest without a firm commitment."),
    ("Requested a demo but hasn't confirmed a time yet.",
     "A demo was requested but not yet scheduled, indicating interest that hasn't fully materialized."),
    ("Engaged on LinkedIn, asked about integration options.",
     "LinkedIn engagement and integration questions suggest moderate, exploratory interest."),
    ("Filled out a contact form and replied to one follow-up email.",
     "A form submission with one reply shows initial engagement but limited depth so far."),
    ("Compared our pricing page against two competitors' sites.",
     "Comparison-shopping behavior indicates active but early-stage evaluation."),
    ("Mid-level manager inquired, said budget approval is 'a few months out'.",
     "Interest exists but budget approval is not imminent, tempering near-term conversion likelihood."),
    ("Asked for a case study relevant to their industry.",
     "Requesting an industry-specific case study shows genuine but still-early evaluation."),
    ("Opened every email in the nurture sequence but hasn't replied.",
     "Consistent email opens without replies suggest interest that hasn't yet turned into direct engagement."),
    ("Free trial started, logged in twice this week.",
     "Trial activity shows hands-on evaluation, though usage is still light."),
]

LOW_ENGAGEMENT = [
    ("Picked up a brochure at a trade show, no follow-up yet.",
     "Only passive trade-show contact so far, with no follow-up engagement."),
    ("Opened one email, no reply since.",
     "A single email open with no further activity indicates weak engagement."),
    ("Filled out a form but hasn't responded to two outreach attempts.",
     "The lead has gone quiet after the initial form submission despite follow-up attempts."),
    ("Lead quality unclear, minimal information collected at signup.",
     "Very little information is available, making this a low-confidence, low-engagement lead."),
    ("Job title and company size unknown, generic inquiry submitted.",
     "An unqualified generic inquiry with no firmographic detail limits conversion confidence."),
    ("Clicked a single ad, landed on the homepage, no further activity.",
     "A single ad click with no further activity suggests minimal genuine interest."),
    ("Requested a brochure by email, no response to the follow-up call.",
     "The lead requested materials but did not engage with the follow-up call."),
    ("Intern submitted an inquiry on behalf of the company, no decision-maker contact yet.",
     "The inquiry came from a non-decision-maker with no further contact established."),
]

NEGATIVE_ENGAGEMENT = [
    ("Explicitly said not interested and asked to be removed from the list.",
     "The customer explicitly declined interest and requested removal, indicating no conversion likelihood."),
    ("No response after 3 follow-up attempts over a month.",
     "The customer shows no engagement after multiple follow-up attempts."),
    ("Phone number unreachable, all emails bounced.",
     "Unreachable contact details make this lead effectively dead for now."),
    ("Unsubscribed from all marketing communications.",
     "Unsubscribing from all communications is a clear signal of disinterest."),
    ("Said they already signed with a competitor.",
     "The customer has already committed to a competitor, eliminating near-term conversion likelihood."),
    ("Budget was cut and the project was cancelled internally.",
     "An internally cancelled project with no budget removes any near-term conversion likelihood."),
    ("Requested not to be contacted again after a cold call.",
     "An explicit request not to be contacted again rules out further pursuit."),
    ("Marked our last email as spam.",
     "Marking the outreach as spam is a strong negative signal for engagement."),
]

TIERS = [
    ("negative", NEGATIVE_ENGAGEMENT, 0, 14),
    ("low", LOW_ENGAGEMENT, 14, 34),
    ("medium", MEDIUM_ENGAGEMENT, 36, 60),
    ("high", HIGH_ENGAGEMENT, 68, 98),
]
# Weighted rather than uniform: medium gets picked more often since its
# score range is narrower and easily bleeds into COLD/HOT after the deal
# value bonus + jitter, which otherwise starves the WARM label of examples.
TIER_WEIGHTS = [0.20, 0.22, 0.34, 0.24]


def label_for_score(score: int) -> str:
    if score >= 70:
        return "HOT"
    if score >= 38:
        return "WARM"
    return "COLD"


def build_lead_block(fields: dict) -> str:
    order = ["Full Name", "Company", "Industry", "Employee Count", "Product",
             "Estimated Deal Value", "Source Channel", "Notes"]
    return "\n".join(f"{k}: {fields[k]}" for k in order if fields.get(k) is not None)


def generate_example(rng: random.Random) -> dict:
    tier_name, note_pairs, lo, hi = rng.choices(TIERS, weights=TIER_WEIGHTS, k=1)[0]
    notes, reason_template = rng.choice(note_pairs)

    company = f"{rng.choice(COMPANY_PREFIXES)} {rng.choice(COMPANY_SUFFIXES)}"
    full_name = f"{rng.choice(FIRST_NAMES)} {rng.choice(LAST_NAMES)}"
    # The current "Add lead" wizard doesn't even collect industry/employee
    # count — real leads usually arrive without them. Reflect that instead
    # of a dataset where every field is always conveniently populated.
    industry = rng.choice(INDUSTRIES) if rng.random() < 0.45 else None
    employee_count = rng.choice(EMPLOYEE_COUNTS) if rng.random() < 0.45 else None
    product = rng.choice(PRODUCTS)
    source_channel = rng.choice(SOURCE_CHANNELS)

    # Negative-engagement leads shouldn't be biased toward big deal values
    # (a rejection is a rejection regardless of company size), so only
    # apply the deal-value bonus for tiers where size plausibly matters,
    # and keep medium's bonus small so it doesn't spill into HOT.
    tier_label, dv_lo, dv_hi, bonus = rng.choice(DEAL_VALUE_TIERS)
    deal_value = round(rng.uniform(dv_lo, dv_hi), -2)
    if tier_name == "high":
        score_bonus = bonus
    elif tier_name == "medium":
        score_bonus = min(bonus, 5)
    else:
        score_bonus = 0

    score = int(round(rng.uniform(lo, hi))) + score_bonus + rng.randint(-2, 2)
    score = max(0, min(100, score))
    label = label_for_score(score)
    reason = reason_template.format(company=company)

    fields = {
        "Full Name": full_name,
        "Company": company,
        "Industry": industry,
        "Employee Count": employee_count,
        "Product": product,
        "Estimated Deal Value": deal_value,
        "Source Channel": source_channel,
        "Notes": notes,
    }

    lead_block = build_lead_block(fields)
    user_input = f"{lead_block}\n\n{RESPONSE_FORMAT_HINT}"
    output = json.dumps({"score": score, "label": label, "reason": reason})

    return {"instruction": INSTRUCTION, "input": user_input, "output": output}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=300)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)

    seen_blocks = set()
    examples = []
    attempts = 0
    while len(examples) < args.count and attempts < args.count * 20:
        attempts += 1
        ex = generate_example(rng)
        # Avoid exact duplicate (instruction+input) pairs — full_name/company
        # are randomized per example so collisions should be rare, but skip
        # them defensively rather than let the model see an identical
        # prompt with two different labels.
        if ex["input"] in seen_blocks:
            continue
        seen_blocks.add(ex["input"])
        examples.append(ex)

    rng.shuffle(examples)

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        for ex in examples:
            f.write(json.dumps(ex) + "\n")

    label_counts = {"HOT": 0, "WARM": 0, "COLD": 0}
    for ex in examples:
        label_counts[json.loads(ex["output"])["label"]] += 1

    print(f"Wrote {len(examples)} examples to {OUT_PATH}")
    print(f"Label distribution: {label_counts}")


if __name__ == "__main__":
    main()
