"""
build_realistic.py
==================
Writes eval/realistic.jsonl: 40 hand-written leads in the shape the CRM bridge
(server/main.py build_lead_input) sends -- no Company Location, Customer
Requirement taken from the "Notes from sales executive" box, typed the way reps
type: abbreviations, missing punctuation, the odd typo.

There is no real lead data to test against, so this is the stand-in. The notes
were written by hand and labelled with the tier rules in
data/generate_dataset.py, and none of their wording appears in the generator's
phrase banks: a model can only get them right by reading.

Each case also names the total the four lookup factors (employees, quantity,
deal value, timeline) must add up to. Most are set so the lead sits on a band
boundary -- a requirement tier read 5 points wrong flips Cold/Warm or
Warm/Hot, which is the mistake that matters to a sales team. The values that
produce that total are drawn here with a fixed seed and rounded the way people
enter them.

Run:
    python adapters/lead_scoring/eval/build_realistic.py
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parent / "data"))
import prompt_format as fmt  # noqa: E402
from generate_dataset import format_inr  # noqa: E402

OUT = HERE / "realistic.jsonl"
SEED = 2026

# (company, sales-rep note, requirement tier, lookup-factor total)
# Boundary totals: tier 0 -> 30 (Cold at 30, Warm if read as 5)
#                  tier 5 -> 30 (Warm at 35, Cold if read as 0)
#                  tier 10 -> 50 (Warm at 60, Hot if read as 15)
#                  tier 15 -> 50 (Hot at 65, Warm if read as 10)
#                  tier 20 -> 45 (Hot at 65, Warm if read as 15)
CASES = [
    # ---- tier 0: no concrete need -------------------------------------------------
    ("Shree Balaji Traders", "asked for brochure on whatsapp, didnt mention any requirement", 0, 30),
    ("Kaveri Agro Foods Pvt Ltd", "Walk-in at our expo booth, took a visiting card. No discussion on needs.", 0, 30),
    ("Northbay Clinics", "Customer asked whether our software runs on Mac. That was the whole call.", 0, 30),
    ("Arvind Hosiery Mills", "Student from a B-school doing a survey on CRM tools, not a buyer", 0, 30),
    ("Greenleaf Interiors", "Replied to our LinkedIn post asking what we do. No project at their end.", 0, 30),
    ("Metro Pipes & Fittings", "returned missed call, person said they were just checking our website", 0, 30),
    ("Coastal Shipping Agencies", "Wants to know about our company background and client list. Nothing specific.", 0, 15),
    ("Pinnacle Dental Labs", "Newsletter signup. Called once, said they're not looking for anything rn", 0, 60),

    # ---- tier 5: exploratory --------------------------------------------------------
    ("Sunrise Motors", "Comparing 4 vendors incl. Salesforce. Want our quote only to compare, decision maybe in Q3.", 5, 30),
    ("Bharat Electricals", "Asked for price list, will discuss in next year's budget meeting", 5, 30),
    ("Lotus Wellness Spa", "Their consultant is preparing a vendor comparison sheet, we are one of the options", 5, 30),
    ("Rathi Steel Works", "Still deciding whether to go for a CRM or carry on with Excel, wants a rough cost", 5, 30),
    ("Skyline Realtors", "RFI received - early stage, they are surveying the market", 5, 30),
    ("Vega Pharma Distributors", "evaluating zoho, freshsales and us. no timeline or scope finalised", 5, 30),
    ("Amrut Sweets", "Owner said he wants to 'see what's out there' before deciding anything", 5, 10),
    ("Elite Packaging Co", "Wants an indicative quote for a proposal they may put to management next year", 5, 55),

    # ---- tier 10: a defined, standard need ------------------------------------------
    ("Prakash Auto Spares", "Sales team of 15 tracking leads on whatsapp + excel, want proper lead tracking & follow up reminders", 10, 50),
    ("Medlife Diagnostics", "Need a helpdesk for patient complaints, right now everything comes to one email id", 10, 50),
    ("Orbit Logistics", "Want dashboards for the MD to see monthly sales by region", 10, 50),
    ("Sapphire Jewellers", "Looking for a system to keep all client contacts and their purchase/visit history", 10, 50),
    ("Kisan Agri Inputs", "Need email marketing to their dealer list + basic contact mgmt", 10, 50),
    ("TrueValue Furnishings", "they want to track quotations and deals in a pipeline view", 10, 50),
    ("Nimbus Broadband", "Customer service team wants ticket tracking with SMS status updates to subscribers", 10, 20),
    ("Udaan Travels", "need website enquiry forms to land in a CRM so reps can be assigned manually", 10, 70),

    # ---- tier 15: a specific, demanding need ----------------------------------------
    ("Gujarat Polymers Ltd", "Must integrate with SAP ECC for customer master and order history, non-negotiable", 15, 50),
    ("Annapurna Foods", "Need Tally Prime integration so invoices auto-sync with deals", 15, 50),
    ("Crestline Insurance Brokers", "Want an automated approval chain for quotes over 5L - RSM, then NSM, then finance", 15, 50),
    ("Helix Edtech", "Interested mainly in AI lead scoring to rank inbound leads for their call centre", 15, 50),
    ("Precision Castings", "Need API integration with their custom-built ERP (java), plus webhooks on deal stage change", 15, 50),
    ("Bluewater Hotels", "40 branches, need territory-based auto lead assignment + escalation if not contacted in 24 hrs", 15, 50),
    ("Vertex Fintech", "Require integration with Oracle NetSuite and automated renewal workflows", 15, 15),
    ("Indus Tiles", "Want predictive scoring and next-best-action suggestions for their reps", 15, 35),

    # ---- tier 20: a buying signal ---------------------------------------------------
    ("Royal Enfield Dealers Assn", "Budget approved by board last week. Asked for final quote + agreement draft.", 20, 45),
    ("Ganesh Cotton Exports", "They've chosen us. Legal is reviewing the MSA, PO expected by month end.", 20, 45),
    ("Aster Hospitals", "Director wants demo Friday, if OK they place the order same day - funds already allocated", 20, 45),
    ("Mahalaxmi Builders", "Purchase committee approved, need our GST details and bank info for vendor onboarding", 20, 45),
    ("Quickserve Restaurants", "Ready to sign for 120 users, negotiating only on payment terms", 20, 45),
    ("Silverstone Securities", "CEO confirmed go-ahead, want to start implementation on the 1st", 20, 45),
    ("Deccan Chemicals", "Shortlist is down to us and Salesforce; money is sanctioned, they decide this week", 20, 70),
    ("Zenith Coaching Centres", "Asked us to send the order form today, they want to pay annual upfront", 20, 25),
]

# Values people actually type, per lookup tier.
EMPLOYEES = {0: [12, 25, 40, 48], 5: [60, 85, 120, 150, 200], 10: [250, 400, 600, 850, 1000],
             15: [1200, 1800, 2500, 4000, 5000], 20: [6000, 8500, 12000, 25000]}
QUANTITY = {0: [2, 5, 8, 10], 5: [15, 25, 40, 50], 10: [60, 75, 90, 100], 15: [120, 200, 300, 450],
            20: [600, 900, 1200, 2500]}
DEAL = {0: [45_000, 60_000, 85_000, 99_000], 5: [1_50_000, 3_00_000, 5_50_000, 8_00_000],
        10: [12_00_000, 18_50_000, 30_00_000, 45_00_000], 15: [60_00_000, 90_00_000, 1_40_00_000],
        20: [2_50_00_000, 4_00_00_000, 7_50_00_000]}


def numbers_for(rng: random.Random, total: int) -> tuple:
    while True:
        pts = [rng.choice([0, 5, 10, 15, 20]) for _ in range(4)]
        if sum(pts) == total:
            break
    timeline = rng.choice([t for t, p in fmt.TIMELINE_POINTS.items() if p == pts[3]])
    return (rng.choice(EMPLOYEES[pts[0]]), rng.choice(QUANTITY[pts[1]]),
            format_inr(rng.choice(DEAL[pts[2]])), timeline)


def main() -> None:
    rng = random.Random(SEED)
    rows = []
    for index, (company, note, tier, total) in enumerate(CASES, 1):
        employees, qty, deal, timeline = numbers_for(rng, total)
        lead_input = "\n".join([
            f"Company Name: {company}",
            f"Employees Count: {employees}",
            f"Product Quantity: {qty}",
            f"Deal Value: {deal}",
            f"Purchase Timeline: {timeline}",
            f"Customer Requirement: {note}",
        ])
        fields = fmt._parse_input_fields(lead_input)
        check = (fmt._tier_points(int(fields["Employees Count"]), fmt.EMPLOYEES_COUNT_TIERS)
                 + fmt._tier_points(int(fields["Product Quantity"]), fmt.PRODUCT_QUANTITY_TIERS)
                 + fmt._tier_points(int("".join(c for c in deal if c.isdigit())), fmt.DEAL_VALUE_TIERS)
                 + fmt.TIMELINE_POINTS[timeline])
        assert check == total, (company, check, total)
        rows.append({"id": f"REAL-LS-{index:03d}", "input": lead_input, "requirement_points": tier})

    with OUT.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} rows to {OUT}")


if __name__ == "__main__":
    main()
