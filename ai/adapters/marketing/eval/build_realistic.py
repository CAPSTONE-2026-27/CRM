"""
build_realistic.py
==================
Writes eval/realistic.jsonl: hand-written campaigns, leads and content briefs
in the row format eval/evaluate.py scores.

    python adapters/marketing/eval/build_realistic.py
    python adapters/marketing/eval/evaluate.py --heldout adapters/marketing/eval/realistic.jsonl --label realistic

There is no real campaign data yet (data/collect_feedback.py will supply it
once the Marketing Head has reviewed some), so this is the stand-in.

Lead evaluation: 4 campaigns written the way a marketer sets them up, 5 leads
each with the gaps and formats a CRM actually holds -- missing attributes,
"200-500" and "1000+" sizes, deal values with ".00", unqualified or
unreachable contacts, a lead that fails a required rule but looks attractive
otherwise. Labels come from the same rule engine port (rules.py) and teacher
the training data used, so they follow the backend's own rules.

Content generation: 10 briefs a marketer might actually submit -- sparse ones,
over-long ones, ones with specific figures and dates, tight SMS and
notification channels. No reference copy is needed: evaluate.py checks each
reply against the ContentOutputValidator port and the length margin.
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent.parent))
sys.path.insert(0, str(HERE.parent / "data"))
from adapters.marketing import prompt_format as fmt  # noqa: E402
from adapters.marketing import rules  # noqa: E402
from generate_dataset import PRODUCTS, describe_audience, teacher  # noqa: E402

OUT = HERE / "realistic.jsonl"
SEED = 2026


def product(name: str) -> dict:
    return next(p for p in PRODUCTS if p["name"] == name)


def campaign(product_name, ctype, objective, audience, criteria, match="ALL"):
    a = {"industries": [], "companySize": None, "locations": [], "customerTypes": [], "leadSources": [],
         "productInterest": None, "minDealValue": None, "maxDealValue": None, "purchaseTimeline": None,
         "customerSegment": None, "customerStatus": "ANY"}
    a.update(audience)
    brief = {"campaign_type": ctype, "objective": objective, "product_name": product_name}
    described = describe_audience(a)
    if described:
        brief["target_audience"] = described
    if a["customerSegment"]:
        brief["customer_segment"] = a["customerSegment"]
    return {"product": product(product_name), "type": ctype, "audience": a,
            "criteria": criteria, "match": match, "brief": brief}


CAMPAIGNS = [
    (campaign("TechCRM Field Force", "product launch",
              "Book field-sales demos with manufacturers and distributors in western India",
              {"industries": ["Manufacturing", "Logistics"], "companySize": "51-200",
               "locations": ["Mumbai", "Pune", "Ahmedabad"], "productInterest": "TechCRM Field Force",
               "customerSegment": "Growing mid-market teams"},
              [("INDUSTRY", "IN", "Manufacturing, Logistics"), ("COMPANY_SIZE", "GTE", "50")]),
     [
         ("LD48213", {"industry": "Manufacturing", "company_size": "201-500", "location": "Pune",
                      "customer_type": "Mid-Market", "lead_source": "Trade Show",
                      "product_interest": "TechCRM Field Force", "interest_level": "High",
                      "crm_lead_score": 78, "estimated_deal_value": "1850000.00",
                      "purchase_timeline": "Within 1 Month", "qualification_status": "QUALIFIED",
                      "contact_status": "Meeting Scheduled"}),
         ("LD48214", {"industry": "Retail", "company_size": "1000+", "location": "Mumbai",
                      "product_interest": "TechCRM Field Force", "interest_level": "High",
                      "crm_lead_score": 88, "estimated_deal_value": "4200000.00",
                      "purchase_timeline": "Immediately", "contact_status": "Contacted"}),
         ("LD48215", {"industry": "Logistics", "company_size": "11-50", "location": "Ahmedabad",
                      "interest_level": "Medium", "crm_lead_score": 55,
                      "purchase_timeline": "Within 3 Months"}),
         ("LD48216", {"industry": "Manufacturing", "location": "Nagpur",
                      "product_interest": "TechCRM CPQ", "interest_level": "Low",
                      "estimated_deal_value": "350000", "contact_status": "Not Reachable"}),
         ("LD48217", {"industry": "Logistics", "company_size": "75", "location": "Mumbai",
                      "customer_type": "SMB", "product_interest": "TechCRM Field Force",
                      "interest_level": "Medium", "crm_lead_score": 61,
                      "estimated_deal_value": "900000.00", "purchase_timeline": "Within 2 Months",
                      "qualification_status": "PENDING"}),
     ]),

    (campaign("TechCRM Service Desk", "lead nurturing",
              "Move warm healthcare and hospitality leads towards a Service Desk conversation",
              {"industries": ["Healthcare", "Hospitality"], "minDealValue": 500000,
               "purchaseTimeline": "Within 3 Months", "customerTypes": ["Mid-Market", "Enterprise"]},
              [("ESTIMATED_DEAL_VALUE", "GTE", "500000"), ("PURCHASE_TIMELINE_DAYS", "LTE", "90"),
               ("QUALIFICATION_STATUS", "NEQ", "UNQUALIFIED")]),
     [
         ("LD51002", {"industry": "Healthcare", "company_size": "501-1000", "location": "Bengaluru",
                      "customer_type": "Enterprise", "product_interest": "TechCRM Service Desk",
                      "interest_level": "High", "crm_lead_score": 72, "estimated_deal_value": "2600000.00",
                      "purchase_timeline": "Within 2 Months", "qualification_status": "QUALIFIED"}),
         ("LD51003", {"industry": "Hospitality", "company_size": "201-500", "customer_type": "Mid-Market",
                      "interest_level": "High", "crm_lead_score": 80, "estimated_deal_value": "1500000.00",
                      "purchase_timeline": "Within 1 Month", "qualification_status": "UNQUALIFIED"}),
         ("LD51004", {"industry": "Healthcare", "customer_type": "Mid-Market",
                      "product_interest": "TechCRM Service Desk", "interest_level": "Medium",
                      "crm_lead_score": 58, "purchase_timeline": "Within 3 Months",
                      "qualification_status": "PENDING"}),
         ("LD51005", {"industry": "Education", "company_size": "51-200", "customer_type": "SMB",
                      "interest_level": "Medium", "estimated_deal_value": "250000",
                      "purchase_timeline": "More than 3 Months", "qualification_status": "PENDING"}),
         ("LD51006", {"industry": "Hospitality", "company_size": "1000+", "location": "Dubai",
                      "customer_type": "Enterprise", "product_interest": "TechCRM Service Desk",
                      "interest_level": "High", "crm_lead_score": 91, "estimated_deal_value": "7500000.00",
                      "purchase_timeline": "Within 15 Days", "qualification_status": "QUALIFIED",
                      "contact_status": "Follow Up"}),
     ]),

    (campaign("TechCRM Analytics Pro", "cross sell",
              "Offer Analytics Pro to existing customers in financial services and IT",
              {"industries": ["Financial Services", "IT Services"], "customerStatus": "EXISTING_CUSTOMERS",
               "customerSegment": "Enterprise IT buyers"},
              [("INDUSTRY", "IN", "Financial Services, IT Services"), ("INTEREST_LEVEL", "GTE", "Medium"),
               ("LEAD_SCORE", "GTE", "50")], match="ANY"),
     [
         ("LD60110", {"industry": "Financial Services", "company_size": "1000+", "location": "Mumbai",
                      "customer_type": "Enterprise", "product_interest": "TechCRM Analytics Pro",
                      "interest_level": "High", "crm_lead_score": 84, "estimated_deal_value": "3200000.00",
                      "purchase_timeline": "Within 1 Month"}),
         ("LD60111", {"industry": "Retail", "company_size": "51-200", "interest_level": "Low",
                      "crm_lead_score": 30, "purchase_timeline": "More than 3 Months"}),
         ("LD60112", {"industry": "Telecom", "company_size": "201-500", "interest_level": "Medium",
                      "crm_lead_score": 47, "product_interest": "TechCRM Analytics Pro",
                      "estimated_deal_value": "800000.00"}),
         ("LD60113", {"company_size": "11-50", "location": "Hyderabad", "lead_source": "Website"}),
         ("LD60114", {"industry": "IT Services", "company_size": "501-1000", "customer_type": "Mid-Market",
                      "product_interest": "TechCRM Sales Cloud", "interest_level": "Medium",
                      "crm_lead_score": 66, "estimated_deal_value": "1100000.00",
                      "purchase_timeline": "Within 3 Months", "qualification_status": "QUALIFIED"}),
     ]),

    (campaign("TechCRM Onboarding Suite", "seasonal",
              "Close onboarding deals with new customers before the financial year ends",
              {"industries": ["Real Estate", "Retail", "Automotive"], "locations": ["Delhi", "Jaipur"],
               "maxDealValue": 5000000, "purchaseTimeline": "Within 1 Month",
               "customerStatus": "NEW_LEADS"},
              [("LOCATION", "IN", "Delhi, Jaipur"), ("PURCHASE_TIMELINE_DAYS", "LTE", "30")]),
     [
         ("LD70301", {"industry": "Real Estate", "company_size": "51-200", "location": "Delhi",
                      "customer_type": "SMB", "product_interest": "TechCRM Onboarding Suite",
                      "interest_level": "High", "crm_lead_score": 70, "estimated_deal_value": "1200000.00",
                      "purchase_timeline": "Within 15 Days", "qualification_status": "QUALIFIED"}),
         ("LD70302", {"industry": "Automotive", "company_size": "201-500", "location": "Gurgaon",
                      "product_interest": "TechCRM Onboarding Suite", "interest_level": "High",
                      "crm_lead_score": 82, "estimated_deal_value": "2000000.00",
                      "purchase_timeline": "Immediately"}),
         ("LD70303", {"industry": "Retail", "location": "Jaipur", "interest_level": "Medium",
                      "crm_lead_score": 52}),
         ("LD70304", {"industry": "Retail", "company_size": "1000+", "location": "Delhi",
                      "customer_type": "Enterprise", "interest_level": "Medium", "crm_lead_score": 64,
                      "estimated_deal_value": "9500000.00", "purchase_timeline": "Within 1 Month",
                      "qualification_status": "PENDING"}),
         ("LD70305", {"industry": "Real Estate", "company_size": "11-50", "location": "Jaipur",
                      "customer_type": "Startup", "product_interest": "TechCRM Onboarding Suite",
                      "interest_level": "Low", "crm_lead_score": 35, "estimated_deal_value": "150000",
                      "purchase_timeline": "Within 2 Months", "contact_status": "Not Reachable"}),
     ]),
]

FULL_LEAD_KEYS = ["industry", "company_size", "location", "customer_type", "lead_source", "product_interest",
                  "interest_level", "crm_lead_score", "estimated_deal_value", "purchase_timeline",
                  "product_quantity", "qualification_status", "contact_status"]

# (brief, style, channels)
BRIEFS = [
    ({"campaign_name": "Field Force Monsoon Push", "campaign_type": "product promotion",
      "objective": "Get distributors' sales heads to start a 14-day Field Force trial before the festive season",
      "product_name": "TechCRM Field Force",
      "product_description": "Field sales app with visit planning and GPS check-ins.",
      "main_benefits": "Better territory coverage, verified visits, less paperwork",
      "offer": "14-day free trial, no card needed", "call_to_action": "Start your trial"},
     {"tone": "Friendly", "language": "English", "writing_style": "Concise"},
     ["core", "sms", "notification"]),

    ({"campaign_name": "Service Desk webinar", "campaign_type": "event promotion",
      "objective": "Fill 200 seats for the live Service Desk webinar on 12 March at 4 PM",
      "product_name": "TechCRM Service Desk", "product_description": "Ticketing and customer support with SLA tracking.",
      "call_to_action": "Register now"},
     {"tone": "Professional", "language": "English",
      "additional_instructions": "Mention the webinar date and time. Keep it under two short paragraphs."},
     ["email", "social"]),

    ({"campaign_name": "CPQ year-end", "campaign_type": "seasonal",
      "objective": "Close CPQ deals before 31 March",
      "product_name": "TechCRM CPQ", "product_description": "Configure, price and quote tool connected to the CRM.",
      "main_benefits": "Accurate quotes, faster approvals, consistent pricing",
      "key_selling_points": "Discount approval rules; PDF quotes; product bundles",
      "discount": "20%", "offer": "20% off the first year for teams of 25 users or more",
      "pricing": "Starts at ₹2,499 per user per month", "call_to_action": "Get a quote",
      "target_audience": "Industries: Manufacturing, Automotive; Company size: 201-500"},
     {"tone": "Persuasive", "language": "English", "writing_style": "Direct response"},
     ["core", "email", "poster", "sms"]),

    ({"campaign_name": "Analytics upsell", "campaign_type": "upsell",
      "objective": "Move Sales Cloud customers onto Analytics Pro",
      "product_name": "TechCRM Analytics Pro", "call_to_action": "Upgrade today"},
     {"tone": "Informative", "language": "English"},
     ["email", "notification"]),

    ({"campaign_name": "Partner Portal launch - channel partners in South India", "campaign_type": "product launch",
      "objective": "Introduce the Partner Portal to our 150 channel partners and get them to register their first deal through it instead of emailing spreadsheets to the regional managers, which is causing double-counting and disputes every quarter",
      "description": "Product launch campaign for TechCRM Partner Portal. A portal for channel partners to register deals and share leads. Partners currently email Excel sheets; the portal replaces that with deal registration, partner tiers and a shared lead inbox.",
      "product_name": "TechCRM Partner Portal",
      "product_description": "A portal for channel partners to register deals and share leads.",
      "main_benefits": "Fewer channel conflicts, faster partner onboarding",
      "key_selling_points": "Deal registration; partner tiers; shared lead inbox",
      "call_to_action": "Register your first deal", "customer_segment": "Channel partners"},
     {"tone": "Conversational", "language": "English", "writing_style": "Benefit-led",
      "additional_instructions": "Use {{first_name}} in the greeting."},
     ["core", "email", "sms", "notification", "social"]),

    ({"campaign_name": "Win-back Q3", "campaign_type": "re engagement",
      "objective": "Re-engage leads who went quiet in the last quarter",
      "product_name": "TechCRM Sales Cloud",
      "product_description": "A sales CRM that tracks leads, deals and pipeline stages in one place.",
      "offer": "Two months free on annual billing", "call_to_action": "Talk to sales"},
     {"tone": "Friendly", "language": "English", "additional_instructions": "Keep sentences short."},
     ["sms", "notification"]),

    ({"campaign_name": "Onboarding Suite - new customers", "campaign_type": "customer retention",
      "objective": "Help customers who signed up this month go live in 30 days",
      "product_name": "TechCRM Onboarding Suite",
      "product_description": "Guided onboarding and data migration for new CRM customers.",
      "main_benefits": "Go live sooner, clean migrated data, trained teams",
      "key_selling_points": "Data import wizard; admin training; migration checklist",
      "offer": "Complimentary data migration for the first 50 customers", "call_to_action": "Book a session"},
     {"tone": "Professional", "language": "English", "writing_style": "Storytelling"},
     ["core", "email", "poster"]),

    ({"campaign_name": "Marketing Hub trial", "campaign_type": "product promotion",
      "objective": "Drive trials of Marketing Hub among qualified leads",
      "product_name": "TechCRM Marketing Hub",
      "product_description": "Campaign management with audience rules, content approval and analytics.",
      "pricing": "Plans from ₹999 per user per month, billed annually", "call_to_action": "Start your trial"},
     {"tone": "Enthusiastic", "language": "English", "additional_instructions": "Avoid jargon; write for first-time CRM buyers."},
     ["social", "poster", "notification"]),

    ({"campaign_name": "Service Desk for clinics", "campaign_type": "lead nurturing",
      "objective": "Move warm clinic leads towards a sales conversation about Service Desk",
      "product_name": "TechCRM Service Desk",
      "main_benefits": "Quicker resolutions, happier customers, visible SLAs",
      "call_to_action": "Book a demo", "target_audience": "Industries: Healthcare; Company size: 11-50",
      "customer_segment": "Owner-led small businesses"},
     {"tone": "Formal", "language": "English"},
     ["email", "sms"]),

    ({"campaign_name": "Bundle offer", "campaign_type": "cross sell",
      "objective": "Offer CPQ to customers already using Sales Cloud",
      "product_name": "TechCRM CPQ", "offer": "10% off when you bundle two TechCRM products",
      "discount": "10%", "call_to_action": "Learn more"},
     {"tone": "Persuasive", "language": "English", "additional_instructions": "Refer to the reader's company as {{company}} once."},
     ["core", "notification", "sms"]),
]


def main() -> None:
    rng = random.Random(SEED)
    rows = []
    for c, leads in CAMPAIGNS:
        for ref, facts in leads:
            facts = {k: facts.get(k) for k in FULL_LEAD_KEYS}
            results, combined, mode = rules.evaluate(c["criteria"], c["match"], facts)
            target = {"lead_id": ref, **teacher(rng, c, facts, results, combined, mode)}
            text = fmt.render_target(target)
            fmt.parse_lead_evaluation(text, ref)
            rows.append({
                "task": "lead_evaluation",
                "messages": [
                    {"role": "system", "content": fmt.LEAD_SYSTEM_PROMPT},
                    {"role": "user", "content": fmt.build_lead_user_prompt(
                        c["brief"], mode, results, rules.prompt_lead(ref, facts))},
                    {"role": "assistant", "content": text},
                ],
                "meta": json.dumps({"combined": combined, "mode": mode, "eligible": target["eligibility"],
                                    "score": target["score"], "source": "realistic"}),
            })
    for brief, style, channels in BRIEFS:
        rows.append({
            "task": "content_generation",
            "messages": [
                {"role": "system", "content": fmt.CONTENT_SYSTEM_PROMPT},
                {"role": "user", "content": fmt.build_content_user_prompt(brief, style, channels)},
                # No reference copy: evaluate.py scores the reply against the validator.
                {"role": "assistant", "content": "{}"},
            ],
            "meta": json.dumps({"channels": channels, "source": "realistic"}),
        })
    with OUT.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    leads = [json.loads(r["meta"]) for r in rows if r["task"] == "lead_evaluation"]
    print(f"Wrote {len(rows)} rows to {OUT}: {len(leads)} leads "
          f"({sum(m['eligible'] == 'eligible' for m in leads)} eligible, "
          f"{sum(m['combined'] == 'FAIL' for m in leads)} rule-fail), {len(BRIEFS)} briefs")


if __name__ == "__main__":
    main()
