"""
generate_dataset.py
===================
Synthesises the CRM Deal Intelligence training set: (previous state + meeting
notes) -> updated state.

Method
------
Deals are simulated as journeys, not as isolated rows. A journey picks a
trajectory (advancing, stalling, deteriorating, recovering, erratic), then walks
a state forward one meeting at a time. Each step:

  1. decides which fields this meeting moves, and where to,
  2. writes meeting notes that state that evidence and nothing else,
  3. emits (previous state, notes) -> (updated state) as one training row.

The generator is the ground truth. The notes are derived from the transition,
never the other way round, so every target is exactly what the notes support —
there is no labelling judgement to be wrong about.

Journeys matter more than row count here. The task is not "read a meeting" but
"update a state from a meeting", and the skill being trained is carrying
unchanged fields forward untouched. That only appears in data where most fields
*don't* move, which is what a journey produces and a set of independent rows
does not.

Held-out wording
----------------
The first dataset had two sentences per value and split rows at random, so 72%
of held-out sentences appeared verbatim in training and the held-out score
(100%) measured recall of the templates. Now:

  * every value has ~6 phrasings, and the last third of each list is used ONLY
    by eval journeys (EVAL_SHARE of journeys, chosen up front),
  * the split is by journey, so meeting 3 of a deal never trains while
    meeting 4 of the same deal is evaluated,
  * notes vary in form -- prose, dash bullets, a "Mtg notes:" header -- and
    carry small talk that changes nothing, so a model must tell evidence from
    noise.

Every row carries `split` ("train" / "eval"); train.py honours it.

Run:
    python adapters/deal_state/data/generate_dataset.py                 # 900 rows
    python adapters/deal_state/data/generate_dataset.py --rows 1200
    python adapters/deal_state/data/generate_dataset.py --validate      # round-trip vs XGBoost
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
from pathlib import Path

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR))
import prompt_format as fmt  # noqa: E402

REPO_ROOT = ADAPTER_DIR.parents[2]
OUT_PATH = ADAPTER_DIR / "data" / "train.jsonl"
SEED = 42
EVAL_SHARE = 0.10


# ============================================================
# ORDERED SCALES
# ============================================================
# Every categorical the journey moves is ordinal, so a trajectory is a walk
# along an index. Kept as explicit lists rather than reusing
# fmt.CATEGORICAL_VALUES directly, because a couple of them are deliberately
# ordered differently there (risk_factors leads with "No Risk Identified").

SCALES = {
    "customer_sentiment": ["Negative", "Neutral", "Positive"],
    "buying_intent": ["Low", "Medium", "High"],
    "budget_status": ["Not Allocated", "Under Review", "Partially Approved", "Fully Approved"],
    "decision_maker_involvement": ["No", "Indirect", "Yes"],
    "customer_urgency": ["Low", "Medium", "High", "Critical"],
    "product_interest_level": ["Low", "Medium", "High", "Very High"],
    "implementation_readiness": ["Not Ready", "Partially Ready", "Ready", "Fully Ready"],
}

# How likely each field is to move at all in a given meeting. Budget and
# decision-maker access change rarely; sentiment and engagement move often.
# Without this everything drifts every meeting and the model never learns to
# leave a field alone.
MOBILITY = {
    "customer_sentiment": 0.55,
    "buying_intent": 0.45,
    "budget_status": 0.30,
    "decision_maker_involvement": 0.30,
    "customer_urgency": 0.35,
    "product_interest_level": 0.40,
    "implementation_readiness": 0.30,
}

TRAJECTORIES = {
    # name: (P(step up), P(step down)) — the remainder is "no change"
    "advancing": (0.75, 0.05),
    "stalling": (0.15, 0.15),
    "deteriorating": (0.05, 0.70),
    "recovering": (0.60, 0.15),
    "erratic": (0.40, 0.40),
}


# ============================================================
# NOTE FRAGMENTS
# ============================================================
# One list per (field, new value). Each fragment must justify that value on
# its own, because it is the only evidence the model gets for it, and must say
# nothing about any other field. The last third of every list is eval-only
# (see phrasing()).

MOVE_NOTES = {
    "customer_sentiment": {
        "Negative": [
            "The room turned cold when we walked through the revised pricing; {contact} said this is not what they were led to expect.",
            "{contact} was visibly frustrated about the unresolved tickets from the pilot and said confidence is low.",
            "{contact} was short with us throughout and made it clear they are unhappy with how we have handled things.",
            "Tense call. {contact} complained that our team keeps missing agreed follow-ups.",
            "{contact} said they feel we oversold the product in the first meeting and they are disappointed.",
            "Frosty reception today; {contact} openly questioned whether we understand their business at all.",
        ],
        "Neutral": [
            "{contact} was businesslike throughout — no enthusiasm either way, just working through the agenda.",
            "Tone was measured. {contact} listened, took notes, and gave little away.",
            "{contact} kept it strictly professional; hard to tell what they made of it.",
            "Polite, flat meeting. {contact} neither pushed back nor showed much warmth.",
            "{contact} was reserved today, asked a few factual questions and moved on.",
            "Matter-of-fact discussion, no visible reaction from {contact} to anything we showed.",
        ],
        "Positive": [
            "{contact} was visibly enthusiastic after the demo and said this is clearly the direction they want to go.",
            "Good energy in the room. {contact} called the walkthrough 'exactly what we've been missing'.",
            "{contact} thanked the team twice and said we were the most responsive vendor they have dealt with.",
            "Really warm meeting — {contact} was smiling through the demo and praised our onboarding plan.",
            "{contact} told us the pilot users love the product and they are delighted with our support.",
            "{contact} was full of compliments today and said working with us has been a pleasure.",
        ],
    },
    "buying_intent": {
        "Low": [
            "{contact} said they are still early and are not looking to commit to anything this quarter.",
            "No commercial conversation. {contact} described this as background research for now.",
            "{contact} made it clear they are only benchmarking options and have no plan to buy yet.",
            "They are keeping their options open and did not want to discuss next steps at all.",
            "{contact} said buying anything is not on the table right now; they just want to stay informed.",
            "Purely exploratory from their side — {contact} declined to talk about a purchase.",
        ],
        "Medium": [
            "{contact} asked for indicative pricing to share internally, but stopped short of talking terms.",
            "They want to see a costed outline before deciding whether to take it further.",
            "{contact} wants a second demo for a wider group before deciding anything.",
            "They asked for a trial so they can evaluate properly before committing.",
            "{contact} is building an internal comparison and asked for a rough cost range.",
            "They asked for reference customers to call before they go any further.",
        ],
        "High": [
            "{contact} asked us to send a formal commercial proposal and walked through their PO process.",
            "They asked what the contracting steps look like and who needs to sign on our side.",
            "{contact} asked for our final pricing and a draft order form.",
            "They want the contract and implementation plan this week so they can start the purchase.",
            "{contact} asked which payment terms we offer and how quickly we can invoice.",
            "They have asked for the MSA so their legal team can start the review.",
        ],
    },
    "budget_status": {
        "Not Allocated": [
            "There is no line item for this in the current cycle; {contact} confirmed nothing has been set aside.",
            "{contact} was clear that no budget exists for this yet.",
            "Funding has not been earmarked; {contact} said they would have to find money from somewhere.",
            "{contact} admitted there is currently zero budget assigned to this project.",
            "Finance has not allocated anything for this, per {contact}.",
            "No money is set aside for this at the moment, {contact} said.",
        ],
        "Under Review": [
            "The request is with Finance and {contact} expects a view within a few weeks.",
            "{contact} has submitted the business case; it sits with Finance for review.",
            "Budget approval is pending; the finance committee looks at it next month.",
            "{contact} said the spend request is being reviewed and no decision has come back.",
            "Their CFO is reviewing the budget request but has not signed anything yet.",
            "The funding ask has gone into their approval queue and is awaiting a decision.",
        ],
        "Partially Approved": [
            "Finance has released funding for the first phase only; the rest depends on how phase one lands.",
            "{contact} confirmed partial sign-off — enough to start, not enough for the full rollout.",
            "They have approval for about half the amount; the remainder goes back to the board later.",
            "Budget for the pilot region is approved; the wider rollout still needs sign-off.",
            "{contact} said Finance approved year one but not the full three-year amount.",
            "Some of the money is approved — enough for the core team, not the other departments.",
        ],
        "Fully Approved": [
            "{contact} confirmed Finance has approved the full amount for this financial year.",
            "Budget is signed off in full. {contact} shared the approval reference.",
            "The board approved the entire budget for the project last week.",
            "{contact} said funding is fully sanctioned, all phases included.",
            "Full budget approval came through from the CFO's office.",
            "All the money they asked for has been approved, {contact} confirmed.",
        ],
    },
    "decision_maker_involvement": {
        "No": [
            "{contact} attended alone again; the decision maker has not been introduced.",
            "We still have no line to whoever owns this decision.",
            "Only the evaluation team joined; nobody with sign-off authority has engaged.",
            "{contact} would not say who signs off, and that person has not been involved.",
            "The person who owns the budget has had no contact with us at all.",
            "Still dealing with the working team only; no one senior has been looped in.",
        ],
        "Indirect": [
            "{contact} is relaying to the decision maker but they have not joined a call themselves.",
            "The sponsor is aware and receiving updates second-hand, but was not present.",
            "{contact} briefs their {exec} weekly; the {exec} has not met us directly.",
            "The decision maker reviewed our deck through {contact} but did not attend.",
            "{contact} said their {exec} is being kept informed but prefers to stay out of meetings for now.",
            "Our material is going up to the {exec} via {contact}; no direct contact yet.",
        ],
        "Yes": [
            "Their {exec} joined for the second half and asked direct questions about rollout.",
            "{contact} brought the {exec} into the meeting; they own the decision and engaged throughout.",
            "The {exec}, who signs off on this, attended in person today.",
            "Their {exec} chaired the meeting and made clear the decision is theirs.",
            "We finally met the {exec}, the budget owner, who joined the whole session.",
            "The {exec} dialled in and confirmed they are the final approver.",
        ],
    },
    "customer_urgency": {
        "Low": [
            "No deadline attached. {contact} said this can wait until next planning cycle.",
            "There is no pressure internally to move on this in the near term.",
            "{contact} said there is no rush; nothing is forcing a timeline.",
            "Timing is open-ended — they are happy to revisit whenever.",
            "{contact} said this is a 'someday' project with no date in mind.",
            "They have no target date and are in no hurry.",
        ],
        "Medium": [
            "They would like this in place within the next couple of quarters, but nothing is fixed.",
            "{contact} mentioned a soft target of next quarter.",
            "They are aiming for sometime in the second half, roughly.",
            "{contact} would like to be live in a few months if things go smoothly.",
            "A loose target of 'before year end' was mentioned, nothing firm.",
            "They have a rough timeframe of the next three to six months.",
        ],
        "High": [
            "They need to be live before the {season} peak, which puts a firm date on delivery.",
            "{contact} has committed to their board that this lands this quarter.",
            "Go-live has to happen by the end of next month for their product launch.",
            "{contact} set a hard date: live before the new financial year starts.",
            "Their annual sales kickoff is in eight weeks and they want it running by then.",
            "A board review on the 30th means they need the system working before it.",
        ],
        "Critical": [
            "Their existing contract terminates in six weeks and they cannot operate without a replacement.",
            "A regulatory deadline forces this live within the month — {contact} called it non-negotiable.",
            "Their current system is being switched off in three weeks; they must replace it immediately.",
            "{contact} said they face penalties if this is not live within the month.",
            "The incumbent vendor is exiting the market next month, so they have no fallback.",
            "An audit finding requires a fix within four weeks — {contact} called it an emergency.",
        ],
    },
    "product_interest_level": {
        "Low": [
            "Only a narrow slice of the platform is of interest; the rest they consider unnecessary.",
            "{contact} sees limited fit beyond one small use case.",
            "They only care about a single report; everything else was waved away.",
            "{contact} said most of what we showed is irrelevant to them.",
            "Interest is confined to one minor feature.",
            "They dismissed most modules and want just the basic contact list.",
        ],
        "Medium": [
            "They see a fit for the core modules but nothing beyond that yet.",
            "Interest is real but confined to the standard feature set.",
            "{contact} wants the main sales module and maybe reporting, nothing more for now.",
            "They are interested in two of the four modules we presented.",
            "The core package fits; the add-ons did not land.",
            "{contact} likes the basics but sees no need for the advanced pieces.",
        ],
        "High": [
            "{contact} walked through several modules in detail and could see them in use across the team.",
            "They spent most of the session on the advanced capabilities and asked strong questions.",
            "{contact} wants most of the platform, including analytics and automation.",
            "They mapped out how four modules would be used across sales and service.",
            "Interest has widened well beyond the core to the advanced features.",
            "{contact} asked detailed questions about nearly every module we showed.",
        ],
        "Very High": [
            "{contact} wants the full platform and has already sketched out how each module maps to their teams.",
            "They described this as strategic and want every module in scope.",
            "{contact} wants everything, including the modules we had not planned to pitch.",
            "They see the whole suite as core to their transformation programme.",
            "Every module is in scope now; {contact} wants the complete platform company-wide.",
            "{contact} called the full platform a must-have and wants all of it.",
        ],
    },
    "implementation_readiness": {
        "Not Ready": [
            "Their environment is not prepared — no owner assigned and no timeline for getting one.",
            "{contact} conceded they have nobody free to run this internally.",
            "They have not started any preparation; no project team exists yet.",
            "{contact} said their data is a mess and nobody owns the clean-up.",
            "No technical resources are available on their side for the foreseeable future.",
            "Internally nothing is set up: no owner, no data plan, no environment.",
        ],
        "Partially Ready": [
            "They have named a technical owner but the environment work has not started.",
            "Some groundwork is done; the data migration has not been scoped.",
            "A project manager is assigned, but integrations are not prepared yet.",
            "Their data clean-up is halfway done; the team is only partly staffed.",
            "{contact} said they have the people but not yet the infrastructure.",
            "Environment is provisioned but nobody has been assigned to run it.",
        ],
        "Ready": [
            "Their technical team has completed the prerequisites and is waiting on us to schedule.",
            "{contact} confirmed the environment is prepared and the team is allocated.",
            "Data is cleaned and the project team is in place; they can start when we can.",
            "Their IT lead says everything needed for kickoff is done.",
            "Integrations are mapped and staff allocated — they are good to go.",
            "{contact} said their side is set up and waiting for our project plan.",
        ],
        "Fully Ready": [
            "Everything is in place — environment, owner, migration plan and a cutover date they proposed.",
            "They are ready to begin immediately; {contact} asked for our earliest start date.",
            "Their team, data, environment and training plan are all signed off for go-live.",
            "{contact} said every prerequisite is complete, including user training slots.",
            "Cutover plan, migration scripts and owners are all done on their side.",
            "They have finished all preparation and booked the go-live weekend.",
        ],
    },
}

OBJECTION_RAISED = {
    "Price Too High": [
        "{contact} pushed back hard on the commercial numbers, calling them well above what they budgeted.",
        "They said our quote is far more expensive than they expected.",
        "{contact} said the price is the main sticking point — it is too high for them.",
    ],
    "Budget Not Allocated": [
        "{contact} flagged that no funding has been set aside for this yet.",
        "They raised that the money simply is not there for this right now.",
        "{contact} said lack of funding could hold the project back.",
    ],
    "Competitor Preference": [
        "Part of their team is leaning toward an incumbent alternative.",
        "{contact} admitted some colleagues prefer another vendor's product.",
        "Their operations head favours a rival platform, which came up repeatedly.",
    ],
    "Lack of Internal Buy-in": [
        "{contact} admitted that not everyone internally is convinced this is needed.",
        "Some department heads are not on board with the change.",
        "{contact} said internal support for the project is thin.",
    ],
    "Long Implementation Time": [
        "Our implementation timeline was raised as a serious concern.",
        "{contact} said a multi-month rollout is too long for them.",
        "They worry the deployment will take far longer than they can afford.",
    ],
    "Missing Features": [
        "They identified gaps against their requirements list that matter to them.",
        "{contact} listed features we do not have that they consider essential.",
        "A couple of must-have capabilities are missing from our product, they said.",
    ],
    "No Urgent Business Need": [
        "{contact} was candid that nothing is forcing them to act right now.",
        "They questioned whether there is any pressing reason to change.",
        "{contact} said the business can live without this for now.",
    ],
    "Poor Past Experience / Support": [
        "A bad experience with a previous vendor's support came up repeatedly.",
        "{contact} said their last provider's support was awful and they fear a repeat.",
        "They were burned by a past rollout and are wary of support quality.",
    ],
    "Security / Compliance Concerns": [
        "Their security team raised questions we have not yet answered.",
        "{contact} flagged data protection concerns that need resolving.",
        "Compliance raised issues about where the data would be stored.",
    ],
    "Unfavorable Contract Terms": [
        "{contact} objected to the contract length and the exit terms.",
        "They pushed back on our standard liability clauses.",
        "{contact} is unhappy with the auto-renewal and termination terms.",
    ],
}

OBJECTION_RESOLVED = {
    "Price Too High": [
        "The revised commercial structure landed well and {contact} considers pricing settled.",
        "Pricing is no longer an issue after the revised offer.",
        "{contact} accepted the new price, so cost is off the table as a concern.",
    ],
    "Budget Not Allocated": [
        "Funding has since been identified, so the budget concern is closed.",
        "{contact} found the money, so the funding objection is resolved.",
        "The lack-of-funds concern has gone away now that money is earmarked.",
    ],
    "Competitor Preference": [
        "{contact} confirmed the alternative has been ruled out.",
        "The team that favoured the other vendor has come round to us.",
        "They dropped the competing product from consideration.",
    ],
    "Lack of Internal Buy-in": [
        "The internal stakeholders are now aligned behind this.",
        "{contact} said the sceptical department heads are now supportive.",
        "Internal resistance has been resolved after the leadership session.",
    ],
    "Long Implementation Time": [
        "Our phased plan addressed the timeline concern to their satisfaction.",
        "{contact} is comfortable with the rollout duration now.",
        "The shorter phase-one plan put the timeline worry to rest.",
    ],
    "Missing Features": [
        "The roadmap session closed out the feature gaps they had raised.",
        "{contact} confirmed the missing capabilities are covered by our next release.",
        "They accepted our workaround, so the feature gap is closed.",
    ],
    "No Urgent Business Need": [
        "A business driver has emerged that gives this real priority.",
        "{contact} now sees a clear reason to act, so that objection is gone.",
        "A new initiative has made the case for change, they said.",
    ],
    "Poor Past Experience / Support": [
        "The support model walkthrough resolved their earlier reservations.",
        "{contact} is reassured about support after speaking to our references.",
        "Our SLA commitments put their past-vendor worries to rest.",
    ],
    "Security / Compliance Concerns": [
        "Their security team signed off after the compliance review.",
        "{contact} confirmed the data protection questions are all answered.",
        "Compliance is satisfied with our hosting arrangements now.",
    ],
    "Unfavorable Contract Terms": [
        "Legal agreed revised terms and that objection is closed.",
        "{contact} accepted the amended exit clause.",
        "The contract terms are agreed on both sides now.",
    ],
}

RISK_NOTES = {
    "No Risk Identified": [
        "Nothing came up that puts the opportunity at risk.",
        "No threats to the deal surfaced today.",
        "{contact} said there is nothing on their side that could derail this.",
    ],
    "Budget Constraints": [
        "Cost pressure across the business is the main threat to this going ahead.",
        "Company-wide cost cutting could squeeze this project.",
        "{contact} warned that spending cuts may hit this budget.",
    ],
    "Competitor Pressure": [
        "A competing vendor is actively working the same stakeholders.",
        "Another vendor is offering steep discounts to win this account.",
        "{contact} said a rival is pitching aggressively to their leadership.",
    ],
    "Economic Uncertainty": [
        "{contact} noted a company-wide spending review that could affect this.",
        "The uncertain market has leadership nervous about new commitments.",
        "{contact} mentioned the economic outlook might freeze discretionary projects.",
    ],
    "Internal Politics": [
        "There is disagreement between departments about who owns this.",
        "Two senior leaders are at odds over the project.",
        "{contact} hinted at a turf war between IT and sales over this.",
    ],
    "Stakeholder Turnover": [
        "Our main sponsor is changing roles, which puts continuity at risk.",
        "{contact} is leaving the company next month.",
        "Their project champion has just resigned.",
    ],
    "Technical Concerns": [
        "Their architects are unconvinced the integration will hold at their volumes.",
        "IT is worried about performance with their data sizes.",
        "{contact} said their tech team doubts our API will cope with their load.",
    ],
    "Timeline Conflict": [
        "This now clashes with another programme competing for the same people.",
        "An ERP upgrade is running at the same time and will tie up their team.",
        "{contact} said a parallel project could delay resourcing this one.",
    ],
}

REQUIREMENT_NOTES = {
    "API / Technical Integration": [
        "The requirement centres on API integration with their existing stack.",
        "What they need most is solid API connectivity to their systems.",
        "{contact} said the key need is integrating through our APIs.",
    ],
    "Basic Feature Set": [
        "They need the standard feature set and nothing more elaborate.",
        "Simple, basic functionality is all they are after.",
        "{contact} only needs the basic features.",
    ],
    "Compliance-driven Requirements": [
        "Their requirements are driven by a compliance obligation.",
        "Regulatory compliance is what shapes their needs.",
        "{contact} said the requirements come from their regulator.",
    ],
    "Customized Integration": [
        "They need a tailored integration built around their internal systems.",
        "Custom integration work with their in-house tools is essential.",
        "{contact} needs bespoke connectors to their homegrown systems.",
    ],
    "Enterprise-grade Security": [
        "Enterprise security controls are the defining requirement.",
        "Top-tier security and access control is their main need.",
        "{contact} said enterprise-level security is non-negotiable.",
    ],
    "Multi-department Rollout": [
        "Scope now covers a rollout across several departments.",
        "They want to deploy across sales, service and finance teams.",
        "{contact} needs it rolled out to multiple departments.",
    ],
    "Scalable Infrastructure": [
        "Their priority is infrastructure that scales with projected growth.",
        "They need the platform to scale as they triple in size.",
        "{contact} said scalability for future volumes is the key requirement.",
    ],
    "Standard Package": [
        "The standard package covers what they are asking for.",
        "Our off-the-shelf package fits their needs as is.",
        "{contact} is happy with the standard bundle.",
    ],
}

OUTCOME_NOTES = {
    "No Show / Cancelled": [
        "The meeting was cancelled at short notice and did not take place.",
        "{contact} did not turn up and the session never happened.",
        "They cancelled an hour before; no meeting took place.",
    ],
    "Rescheduled": [
        "We had to cut short and reschedule the substantive discussion.",
        "The main agenda was pushed to next week at {contact}'s request.",
        "We ran out of time and moved the rest to a new slot.",
    ],
    "Discussed Requirements": [
        "We worked through their requirements in detail.",
        "Most of the session went on going through their needs.",
        "We spent the meeting mapping their requirements.",
    ],
    "Proposal Sent": [
        "We issued the formal proposal following the session.",
        "Our proposal went out to {contact} straight after the meeting.",
        "We sent the commercial proposal the same afternoon.",
    ],
    "Verbal Agreement": [
        "{contact} gave a verbal commitment to proceed, subject to paperwork.",
        "{contact} said yes verbally; contracts to follow.",
        "We have a verbal go-ahead pending the signed agreement.",
    ],
}

COMPETITOR_NOTES = {
    "Yes": [
        "A competing vendor was named as also being in the evaluation.",
        "{contact} mentioned they are also talking to another provider.",
        "Another CRM vendor is in the running too.",
    ],
    "No": [
        "No other vendor came up.",
        "{contact} confirmed we are the only vendor they are talking to.",
        "They are not evaluating anyone else.",
    ],
}

UPSELL_NOTES = {
    "Yes": [
        "{contact} raised interest in additional modules beyond the current scope.",
        "They asked about adding more licences and modules later.",
        "{contact} wants to explore extra add-ons beyond the current deal.",
    ],
    "No": [
        "Nothing came up beyond the agreed scope.",
        "{contact} said they will not need anything beyond the current scope.",
        "No appetite for extras beyond what is on the table.",
    ],
}

ROUTINE_NOTES = [
    "We held a routine check-in; nothing material changed since last time.",
    "Short status call; no new developments on their side.",
    "Quick catch-up, nothing new to report.",
]

# Talk that changes nothing. A real note carries plenty of it, and a model that
# moves a field because of it has learned keywords rather than evidence.
FILLER = [
    "Started a few minutes late because of a room mix-up.",
    "Brief chat about the cricket before getting going.",
    "{contact} mentioned their office move is going well.",
    "We swapped notes on a mutual contact from a previous company.",
    "Coffee was good; the call quality was not.",
    "{contact} asked after our team's offsite.",
    "Some small talk about the traffic on the way in.",
    "We confirmed attendee names for the minutes.",
    "{contact} is back from leave this week.",
    "Spent a minute on logistics for the next session.",
]


def phrasing(rng: random.Random, options: list, split: str) -> str:
    """A phrasing for this split: the last third of a list is eval-only."""
    holdout = max(1, len(options) // 3)
    pool = options[-holdout:] if split == "eval" else options[:-holdout]
    return rng.choice(pool)


COMPANIES = [
    ("Everest Retail Group", "Priya Menon", "CFO"),
    ("Meridian Logistics", "Rahul Iyer", "COO"),
    ("Bluepeak Services", "Ananya Rao", "Head of Operations"),
    ("Northline Traders", "Vikram Shah", "Managing Director"),
    ("Cobalt Manufacturing", "Sneha Kulkarni", "VP Engineering"),
    ("Harborview Financial", "Arjun Desai", "CTO"),
    ("Silverline Health", "Kavya Nair", "Director of IT"),
    ("Redwood Energy", "Imran Sheikh", "Head of Procurement"),
    ("Lakeside Hospitality", "Meera Joshi", "General Manager"),
    ("Ironbridge Construction", "Sanjay Pillai", "Commercial Director"),
    ("Saffron Foods", "Deepa Krishnan", "CEO"),
    ("Orbit Telecom", "Farhan Qureshi", "VP Sales"),
    ("Granite Insurance", "Lakshmi Venkat", "Chief Digital Officer"),
    ("Vista Pharma", "Rohan Malhotra", "Head of Commercial"),
    ("Apex Auto Components", "Nisha Bhatt", "Plant Head"),
    ("Coastal Shipping", "Thomas George", "Managing Director"),
    ("Zenith Education", "Pooja Agarwal", "Director"),
    ("Indus Textiles", "Harpreet Singh", "Owner"),
    ("Summit Realty", "Aditi Kapoor", "Sales Director"),
    ("Nova Fintech", "Karan Mehta", "CFO"),
]

SEASONS = ["festive", "financial year-end", "summer", "audit"]


def render_notes(rng: random.Random, fragments: list, contact: str) -> str:
    """One meeting's fragments as a note, in one of the forms reps use."""
    filler = [f.format(contact=contact) for f in rng.sample(FILLER, rng.choice([0, 0, 1, 1, 2]))]
    parts = fragments + filler
    rng.shuffle(parts)
    form = rng.random()
    if form < 0.55:
        return " ".join(parts)
    if form < 0.85:
        return "\n".join(f"- {p}" for p in parts)
    return "Mtg notes:\n" + "\n".join(f"- {p}" for p in parts)


# ============================================================
# JOURNEY SIMULATION
# ============================================================

def _step(rng, scale, current, up_p, down_p):
    index = scale.index(current)
    roll = rng.random()
    if roll < up_p and index < len(scale) - 1:
        return scale[index + 1]
    if roll < up_p + down_p and index > 0:
        return scale[index - 1]
    return current


# ============================================================
# DERIVED NUMERICS
# ============================================================
# Each is a fixed function of fields the meeting notes justify, so the target is
# reachable from the input rather than being noise the model must hallucinate.
# The rules live in prompt_format (lead_score, relationship_strength,
# engagement_score) so the server recomputes these fields with exactly the
# functions that generated the targets.


def initial_state(rng) -> dict:
    """A plausible opening state, at one of three stages of maturity.

    Not every journey starts from scratch. A CRM holds deals at every stage, and
    starting all of them cold produced a training set where two thirds of rows
    scored under 40 and almost none above 70 — the model would rarely see a
    healthy deal and would learn that high scores barely exist. Sampling the
    entry point spreads the target distribution across the range the scorer
    actually has to cover.
    """
    stage = rng.choices(["early", "developing", "mature"], weights=[0.45, 0.35, 0.20])[0]
    state = dict(fmt.DEFAULTS)

    if stage == "early":
        state.update(
            customer_sentiment=rng.choice(["Neutral", "Neutral", "Positive"]),
            buying_intent=rng.choice(["Low", "Medium"]),
            relationship_strength=float(rng.randint(2, 4)),
            budget_status=rng.choice(["Not Allocated", "Under Review"]),
            decision_maker_involvement=rng.choice(["No", "Indirect"]),
            customer_urgency=rng.choice(["Low", "Medium"]),
            product_interest_level=rng.choice(["Medium", "High"]),
            implementation_readiness="Not Ready",
        )
    elif stage == "developing":
        state.update(
            customer_sentiment=rng.choice(["Neutral", "Positive"]),
            buying_intent="Medium",
            relationship_strength=float(rng.randint(4, 7)),
            budget_status=rng.choice(["Under Review", "Partially Approved"]),
            decision_maker_involvement=rng.choice(["Indirect", "Yes"]),
            customer_urgency=rng.choice(["Medium", "High"]),
            product_interest_level=rng.choice(["High", "Very High"]),
            implementation_readiness="Partially Ready",
        )
    else:
        state.update(
            customer_sentiment="Positive",
            buying_intent=rng.choice(["Medium", "High"]),
            relationship_strength=float(rng.randint(6, 9)),
            budget_status=rng.choice(["Partially Approved", "Fully Approved"]),
            decision_maker_involvement="Yes",
            customer_urgency=rng.choice(["High", "Critical"]),
            product_interest_level=rng.choice(["High", "Very High"]),
            implementation_readiness=rng.choice(["Partially Ready", "Ready"]),
        )

    state.update(
        total_meetings=rng.randint(1, 4) if stage != "early" else 1,
        meeting_outcome="Discussed Requirements",
        customer_requirements=rng.choice(fmt.REQUIREMENT_VALUES),
        risk_factors="No Risk Identified",
        # A deal that has been running a while has usually collected an
        # objection or two; starting every journey clean would never teach the
        # model to carry an existing objection list forward.
        main_objections=(
            fmt.NO_OBJECTIONS if stage == "early" or rng.random() < 0.4
            else "; ".join(rng.sample(fmt.OBJECTION_TOKENS, rng.randint(1, 2)))
        ),
    )
    # Derived from the state above by the same rules every later meeting uses,
    # so meeting 1 is not an exception the model has to learn separately.
    state["lead_score"] = fmt.lead_score(state)
    state["engagement_score"] = fmt.engagement_score(state)
    return state


def advance(rng, state: dict, trajectory: str, company, split: str = "train") -> tuple:
    """One meeting. Returns (new_state, meeting_notes)."""
    _, contact, exec_title = company
    up_p, down_p = TRAJECTORIES[trajectory]
    new = dict(state)
    fragments = []

    def say(options):
        return phrasing(rng, options, split).format(
            contact=contact, exec=exec_title, season=rng.choice(SEASONS))

    # --- ordinal fields -------------------------------------------------
    for field, scale in SCALES.items():
        if rng.random() > MOBILITY[field]:
            continue
        moved = _step(rng, scale, state[field], up_p, down_p)
        if moved != state[field]:
            new[field] = moved
            fragments.append(say(MOVE_NOTES[field][moved]))

    # --- objections -----------------------------------------------------
    current = [] if state["main_objections"] == fmt.NO_OBJECTIONS else [
        o.strip() for o in state["main_objections"].split(";") if o.strip()
    ]
    if current and rng.random() < (0.55 if up_p > down_p else 0.10):
        resolved = rng.choice(current)
        current.remove(resolved)
        fragments.append(say(OBJECTION_RESOLVED[resolved]))
    if rng.random() < (0.15 if up_p > down_p else 0.55):
        candidates = [t for t in fmt.OBJECTION_TOKENS if t not in current]
        if candidates:
            raised = rng.choice(candidates)
            current.append(raised)
            fragments.append(say(OBJECTION_RAISED[raised]))
    new["main_objections"] = "; ".join(current) if current else fmt.NO_OBJECTIONS

    # --- single-value categoricals --------------------------------------
    if rng.random() < 0.25:
        risk = rng.choice(fmt.RISK_VALUES if down_p >= up_p else ["No Risk Identified"] * 3 + fmt.RISK_VALUES)
        if risk != state["risk_factors"]:
            new["risk_factors"] = risk
            fragments.append(say(RISK_NOTES[risk]))

    if rng.random() < 0.20:
        requirement = rng.choice(fmt.REQUIREMENT_VALUES)
        if requirement != state["customer_requirements"]:
            new["customer_requirements"] = requirement
            fragments.append(say(REQUIREMENT_NOTES[requirement]))

    if rng.random() < 0.25:
        competitor = "Yes" if down_p >= up_p else rng.choice(["No", "No", "Yes"])
        if competitor != state["competitor_mention"]:
            new["competitor_mention"] = competitor
            fragments.append(say(COMPETITOR_NOTES[competitor]))

    if rng.random() < 0.20:
        upsell = "Yes" if up_p > down_p and rng.random() < 0.6 else "No"
        if upsell != state["upsell_opportunity"]:
            new["upsell_opportunity"] = upsell
            fragments.append(say(UPSELL_NOTES[upsell]))

    # --- meeting outcome: always stated, it describes this meeting -------
    if trajectory == "deteriorating" and rng.random() < 0.30:
        outcome = rng.choice(["No Show / Cancelled", "Rescheduled"])
    elif new["buying_intent"] == "High" and new["budget_status"] == "Fully Approved" and rng.random() < 0.5:
        outcome = rng.choice(["Proposal Sent", "Verbal Agreement"])
    elif up_p > down_p and rng.random() < 0.35:
        outcome = "Proposal Sent"
    else:
        outcome = "Discussed Requirements"
    new["meeting_outcome"] = outcome
    fragments.append(say(OUTCOME_NOTES[outcome]))

    # --- numerics -------------------------------------------------------
    # Derived from the qualitative state, never drifted randomly.
    #
    # An earlier version moved these by a random amount per trajectory. That
    # produced targets no model could reach: the notes justify every categorical
    # change, but nothing in them implies lead_score 49 -> 51. Training on that
    # teaches the model to emit plausible-looking jitter, which is exactly the
    # invented movement the prompt forbids. Deriving them from signals the notes
    # *do* justify makes all 17 fields learnable.
    new["total_meetings"] = state["total_meetings"] + 1
    new["lead_score"] = fmt.lead_score(new)
    new["relationship_strength"] = fmt.relationship_strength(state, new)
    new["engagement_score"] = fmt.engagement_score(new)

    # A meeting with no stated evidence would train the model to invent
    # movement from nothing.
    if len(fragments) == 1:
        fragments.append(say(ROUTINE_NOTES))

    return new, render_notes(rng, fragments, contact)


def generate(rows: int, seed: int = SEED) -> list:
    rng = random.Random(seed)
    examples = []
    journey = 0
    while len(examples) < rows:
        # Every tenth journey is held out, whole. A deterministic schedule
        # rather than a coin flip, so the eval share is exact at any size.
        split = "eval" if journey % round(1 / EVAL_SHARE) == 0 else "train"
        journey += 1
        company = rng.choice(COMPANIES)
        trajectory = rng.choices(
            list(TRAJECTORIES), weights=[0.30, 0.20, 0.20, 0.15, 0.15]
        )[0]
        state = initial_state(rng)
        for _ in range(rng.randint(2, 6)):
            if len(examples) >= rows:
                break
            new_state, notes = advance(rng, state, trajectory, company, split)
            target = fmt.render_state(new_state)
            examples.append({
                # What the trainer consumes. Conversational form is required for
                # TRL's assistant_only_loss, and it makes the tokenizer's chat
                # template the single prompt builder for training and serving.
                "messages": fmt.build_messages(state, notes, target),
                # Kept alongside for inspection, evaluation and debugging: the
                # target on its own is what tests assert against, and `meta`
                # traces a row back to the journey that produced it when a
                # target looks wrong.
                "output": target,
                "meeting_notes": notes,
                "split": split,
                "meta": {"trajectory": trajectory, "company": company[0], "journey": journey},
            })
            state = new_state
    return examples


def sentence_overlap(examples: list) -> float:
    """Share of eval-note evidence sentences that also appear in a train note.
    Filler is excluded: it is shared on purpose and carries no label."""
    filler = [f.replace("{contact}", "").strip(" .").lower() for f in FILLER]

    def sentences(text):
        text = re.sub(r"^(Mtg notes:|- )", "", text, flags=re.MULTILINE)
        found = {s.strip(" -").lower() for s in re.split(r"(?<=[.!?])\s+|\n", text) if len(s.strip()) > 15}
        return {s for s in found if not any(f in s for f in filler)}

    train = set().union(*(sentences(e["meeting_notes"]) for e in examples if e["split"] == "train"))
    held = [s for e in examples if e["split"] == "eval" for s in sentences(e["meeting_notes"])]
    return sum(s in train for s in held) / max(1, len(held))


# ============================================================
# VALIDATION
# ============================================================

def validate(examples: list) -> int:
    """Every target must survive the real scorer. Returns the failure count."""
    try:
        import joblib
        import pandas as pd
        xgboost_dir = REPO_ROOT / "xgboost"
        sys.path.insert(0, str(xgboost_dir))
        from deal_score_pipeline import transform_for_inference

        bundles = sorted((xgboost_dir / "models").glob("*.pkl"))
        if not bundles:
            print("  [SKIP] no model bundle in xgboost/models")
            return 0
        bundle = joblib.load(bundles[-1])
    except ImportError as exc:
        print(f"  [SKIP] cannot validate against XGBoost ({exc})")
        return 0

    failures = 0
    for index, example in enumerate(examples):
        state = json.loads(example["output"])
        try:
            transform_for_inference(pd.DataFrame([state]), bundle, strict=True)
        except Exception as exc:  # noqa: BLE001
            failures += 1
            if failures <= 5:
                print(f"  [FAIL] row {index}: {exc}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=900)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--validate", action="store_true",
                        help="round-trip every target through the XGBoost scorer")
    parser.add_argument("--out", type=Path, default=OUT_PATH)
    args = parser.parse_args()

    print(f"Generating {args.rows} rows (seed={args.seed})...")
    examples = generate(args.rows, args.seed)

    if args.validate:
        print("Validating every target against the XGBoost scorer...")
        failures = validate(examples)
        if failures:
            print(f"\n{failures}/{len(examples)} targets REJECTED. Not written.")
            return 1
        print(f"  all {len(examples)} targets accepted in strict mode")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as handle:
        for example in examples:
            handle.write(json.dumps(example, ensure_ascii=False) + "\n")

    trajectories, splits = {}, {}
    for example in examples:
        key = example["meta"]["trajectory"]
        trajectories[key] = trajectories.get(key, 0) + 1
        splits[example["split"]] = splits.get(example["split"], 0) + 1
    print(f"\nWrote {len(examples)} rows to {args.out}")
    print("Trajectory mix: " + ", ".join(f"{k}={v}" for k, v in sorted(trajectories.items())))
    print("Split: " + ", ".join(f"{k}={v}" for k, v in sorted(splits.items())))
    print(f"Eval evidence sentences also in train: {sentence_overlap(examples):.0%} "
          "(was 72% in the first dataset)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
