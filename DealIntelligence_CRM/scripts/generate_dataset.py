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

Run:
    python scripts/generate_dataset.py                 # 600 rows
    python scripts/generate_dataset.py --rows 1000
    python scripts/generate_dataset.py --validate      # round-trip vs XGBoost
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import deal_state_format as fmt  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_PATH = PROJECT_ROOT / "data" / "train.jsonl"
SEED = 42


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
# One fragment per (field, new value). The fragment must justify that value on
# its own, because it is the only evidence the model gets for it.

MOVE_NOTES = {
    "customer_sentiment": {
        "Negative": [
            "The room turned cold when we walked through the revised pricing; {contact} said this is not what they were led to expect.",
            "{contact} was visibly frustrated about the unresolved tickets from the pilot and said confidence is low.",
        ],
        "Neutral": [
            "{contact} was businesslike throughout — no enthusiasm either way, just working through the agenda.",
            "Tone was measured. {contact} listened, took notes, and gave little away.",
        ],
        "Positive": [
            "{contact} was visibly enthusiastic after the demo and said this is clearly the direction they want to go.",
            "Good energy in the room. {contact} called the walkthrough 'exactly what we've been missing'.",
        ],
    },
    "buying_intent": {
        "Low": [
            "{contact} said they are still early and are not looking to commit to anything this quarter.",
            "No commercial conversation. {contact} described this as background research for now.",
        ],
        "Medium": [
            "{contact} asked for indicative pricing to share internally, but stopped short of talking terms.",
            "They want to see a costed outline before deciding whether to take it further.",
        ],
        "High": [
            "{contact} asked us to send a formal commercial proposal and walked through their PO process.",
            "They asked what the contracting steps look like and who needs to sign on our side.",
        ],
    },
    "budget_status": {
        "Not Allocated": [
            "There is no line item for this in the current cycle; {contact} confirmed nothing has been set aside.",
            "{contact} was clear that no budget exists for this yet.",
        ],
        "Under Review": [
            "The request is with Finance and {contact} expects a view within a few weeks.",
            "{contact} has submitted the business case; it sits with Finance for review.",
        ],
        "Partially Approved": [
            "Finance has released funding for the first phase only; the rest depends on how phase one lands.",
            "{contact} confirmed partial sign-off — enough to start, not enough for the full rollout.",
        ],
        "Fully Approved": [
            "{contact} confirmed Finance has approved the full amount for this financial year.",
            "Budget is signed off in full. {contact} shared the approval reference.",
        ],
    },
    "decision_maker_involvement": {
        "No": [
            "{contact} attended alone again; the decision maker has not been introduced.",
            "We still have no line to whoever owns this decision.",
        ],
        "Indirect": [
            "{contact} is relaying to the decision maker but they have not joined a call themselves.",
            "The sponsor is aware and receiving updates second-hand, but was not present.",
        ],
        "Yes": [
            "Their {exec} joined for the second half and asked direct questions about rollout.",
            "{contact} brought the {exec} into the meeting; they own the decision and engaged throughout.",
        ],
    },
    "customer_urgency": {
        "Low": [
            "No deadline attached. {contact} said this can wait until next planning cycle.",
            "There is no pressure internally to move on this in the near term.",
        ],
        "Medium": [
            "They would like this in place within the next couple of quarters, but nothing is fixed.",
            "{contact} mentioned a soft target of next quarter.",
        ],
        "High": [
            "They need to be live before the {season} peak, which puts a firm date on delivery.",
            "{contact} has committed to their board that this lands this quarter.",
        ],
        "Critical": [
            "Their existing contract terminates in six weeks and they cannot operate without a replacement.",
            "A regulatory deadline forces this live within the month — {contact} called it non-negotiable.",
        ],
    },
    "product_interest_level": {
        "Low": [
            "Only a narrow slice of the platform is of interest; the rest they consider unnecessary.",
            "{contact} sees limited fit beyond one small use case.",
        ],
        "Medium": [
            "They see a fit for the core modules but nothing beyond that yet.",
            "Interest is real but confined to the standard feature set.",
        ],
        "High": [
            "{contact} walked through several modules in detail and could see them in use across the team.",
            "They spent most of the session on the advanced capabilities and asked strong questions.",
        ],
        "Very High": [
            "{contact} wants the full platform and has already sketched out how each module maps to their teams.",
            "They described this as strategic and want every module in scope.",
        ],
    },
    "implementation_readiness": {
        "Not Ready": [
            "Their environment is not prepared — no owner assigned and no timeline for getting one.",
            "{contact} conceded they have nobody free to run this internally.",
        ],
        "Partially Ready": [
            "They have named a technical owner but the environment work has not started.",
            "Some groundwork is done; the data migration has not been scoped.",
        ],
        "Ready": [
            "Their technical team has completed the prerequisites and is waiting on us to schedule.",
            "{contact} confirmed the environment is prepared and the team is allocated.",
        ],
        "Fully Ready": [
            "Everything is in place — environment, owner, migration plan and a cutover date they proposed.",
            "They are ready to begin immediately; {contact} asked for our earliest start date.",
        ],
    },
}

OBJECTION_RAISED = {
    "Price Too High": "{contact} pushed back hard on the commercial numbers, calling them well above what they budgeted.",
    "Budget Not Allocated": "{contact} flagged that no funding has been set aside for this yet.",
    "Competitor Preference": "Part of their team is leaning toward an incumbent alternative.",
    "Lack of Internal Buy-in": "{contact} admitted that not everyone internally is convinced this is needed.",
    "Long Implementation Time": "Our implementation timeline was raised as a serious concern.",
    "Missing Features": "They identified gaps against their requirements list that matter to them.",
    "No Urgent Business Need": "{contact} was candid that nothing is forcing them to act right now.",
    "Poor Past Experience / Support": "A bad experience with a previous vendor's support came up repeatedly.",
    "Security / Compliance Concerns": "Their security team raised questions we have not yet answered.",
    "Unfavorable Contract Terms": "{contact} objected to the contract length and the exit terms.",
}

OBJECTION_RESOLVED = {
    "Price Too High": "The revised commercial structure landed well and {contact} considers pricing settled.",
    "Budget Not Allocated": "Funding has since been identified, so the budget concern is closed.",
    "Competitor Preference": "{contact} confirmed the alternative has been ruled out.",
    "Lack of Internal Buy-in": "The internal stakeholders are now aligned behind this.",
    "Long Implementation Time": "Our phased plan addressed the timeline concern to their satisfaction.",
    "Missing Features": "The roadmap session closed out the feature gaps they had raised.",
    "No Urgent Business Need": "A business driver has emerged that gives this real priority.",
    "Poor Past Experience / Support": "The support model walkthrough resolved their earlier reservations.",
    "Security / Compliance Concerns": "Their security team signed off after the compliance review.",
    "Unfavorable Contract Terms": "Legal agreed revised terms and that objection is closed.",
}

RISK_NOTES = {
    "No Risk Identified": "Nothing came up that puts the opportunity at risk.",
    "Budget Constraints": "Cost pressure across the business is the main threat to this going ahead.",
    "Competitor Pressure": "A competing vendor is actively working the same stakeholders.",
    "Economic Uncertainty": "{contact} noted a company-wide spending review that could affect this.",
    "Internal Politics": "There is disagreement between departments about who owns this.",
    "Stakeholder Turnover": "Our main sponsor is changing roles, which puts continuity at risk.",
    "Technical Concerns": "Their architects are unconvinced the integration will hold at their volumes.",
    "Timeline Conflict": "This now clashes with another programme competing for the same people.",
}

REQUIREMENT_NOTES = {
    "API / Technical Integration": "The requirement centres on API integration with their existing stack.",
    "Basic Feature Set": "They need the standard feature set and nothing more elaborate.",
    "Compliance-driven Requirements": "Their requirements are driven by a compliance obligation.",
    "Customized Integration": "They need a tailored integration built around their internal systems.",
    "Enterprise-grade Security": "Enterprise security controls are the defining requirement.",
    "Multi-department Rollout": "Scope now covers a rollout across several departments.",
    "Scalable Infrastructure": "Their priority is infrastructure that scales with projected growth.",
    "Standard Package": "The standard package covers what they are asking for.",
}

OUTCOME_NOTES = {
    "No Show / Cancelled": "The meeting was cancelled at short notice and did not take place.",
    "Rescheduled": "We had to cut short and reschedule the substantive discussion.",
    "Discussed Requirements": "We worked through their requirements in detail.",
    "Proposal Sent": "We issued the formal proposal following the session.",
    "Verbal Agreement": "{contact} gave a verbal commitment to proceed, subject to paperwork.",
}

COMPETITOR_NOTES = {
    "Yes": "A competing vendor was named as also being in the evaluation.",
    "No": "No other vendor came up.",
}

UPSELL_NOTES = {
    "Yes": "{contact} raised interest in additional modules beyond the current scope.",
    "No": "Nothing came up beyond the agreed scope.",
}

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
]

SEASONS = ["festive", "financial year-end", "summer", "audit"]


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

# What each ordinal contributes to overall lead quality. Budget and decision
# maker carry the most weight because they gate whether a deal can close at all;
# sentiment carries least because a cheerful contact with no authority and no
# budget is not a good lead.
_LEAD_WEIGHTS = {
    "budget_status": 0.22,
    "decision_maker_involvement": 0.22,
    "buying_intent": 0.20,
    "customer_urgency": 0.14,
    "product_interest_level": 0.12,
    "customer_sentiment": 0.10,
}


def _position(field: str, value: str) -> float:
    """Where a value sits on its scale, 0.0 (worst) to 1.0 (best)."""
    scale = SCALES[field]
    return scale.index(value) / (len(scale) - 1)


def _lead_score(state: dict) -> int:
    """Overall lead quality, penalised by unresolved objections."""
    quality = sum(weight * _position(field, state[field])
                  for field, weight in _LEAD_WEIGHTS.items())
    objections = 0 if state["main_objections"] == fmt.NO_OBJECTIONS else len(
        [o for o in state["main_objections"].split(";") if o.strip()]
    )
    # 4 points per open objection, capped so a pile of them cannot erase an
    # otherwise strong deal entirely.
    return max(0, min(100, round(quality * 100) - min(20, objections * 4)))


def _relationship_strength(previous: dict, new: dict) -> float:
    """Trust accumulates and erodes gradually — it is the one field with memory.

    Moves at most one point per meeting, driven by how the meeting went rather
    than by where the deal stands, because a warm meeting with a stalled deal
    still builds the relationship.
    """
    delta = 0
    sentiment = SCALES["customer_sentiment"]
    if sentiment.index(new["customer_sentiment"]) > sentiment.index(previous["customer_sentiment"]):
        delta += 1
    elif sentiment.index(new["customer_sentiment"]) < sentiment.index(previous["customer_sentiment"]):
        delta -= 1

    if new["meeting_outcome"] in ("Proposal Sent", "Verbal Agreement"):
        delta += 1
    elif new["meeting_outcome"] == "No Show / Cancelled":
        delta -= 1

    if new["decision_maker_involvement"] == "Yes" and previous["decision_maker_involvement"] != "Yes":
        delta += 1

    delta = max(-1, min(1, delta))
    return float(max(0, min(10, previous["relationship_strength"] + delta)))


def _engagement_score(state: dict) -> int:
    """How engaged the customer was in THIS meeting.

    Unlike relationship_strength it has no memory: it describes the meeting just
    held, which is why a cancellation floors it regardless of prior history.
    """
    if state["meeting_outcome"] == "No Show / Cancelled":
        return 5
    if state["meeting_outcome"] == "Rescheduled":
        return 25

    base = 40
    base += round(25 * _position("customer_sentiment", state["customer_sentiment"]))
    base += round(20 * _position("product_interest_level", state["product_interest_level"]))
    if state["decision_maker_involvement"] == "Yes":
        base += 10
    if state["meeting_outcome"] == "Verbal Agreement":
        base += 10
    elif state["meeting_outcome"] == "Proposal Sent":
        base += 5
    return max(0, min(100, base))


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
    state["lead_score"] = _lead_score(state)
    state["engagement_score"] = _engagement_score(state)
    return state


def advance(rng, state: dict, trajectory: str, company) -> tuple:
    """One meeting. Returns (new_state, meeting_notes)."""
    _, contact, exec_title = company
    up_p, down_p = TRAJECTORIES[trajectory]
    new = dict(state)
    fragments = []

    def fill(text):
        return text.format(contact=contact, exec=exec_title, season=rng.choice(SEASONS))

    # --- ordinal fields -------------------------------------------------
    for field, scale in SCALES.items():
        if rng.random() > MOBILITY[field]:
            continue
        moved = _step(rng, scale, state[field], up_p, down_p)
        if moved != state[field]:
            new[field] = moved
            fragments.append(fill(rng.choice(MOVE_NOTES[field][moved])))

    # --- objections -----------------------------------------------------
    current = [] if state["main_objections"] == fmt.NO_OBJECTIONS else [
        o.strip() for o in state["main_objections"].split(";") if o.strip()
    ]
    if current and rng.random() < (0.55 if up_p > down_p else 0.10):
        resolved = rng.choice(current)
        current.remove(resolved)
        fragments.append(fill(OBJECTION_RESOLVED[resolved]))
    if rng.random() < (0.15 if up_p > down_p else 0.55):
        candidates = [t for t in fmt.OBJECTION_TOKENS if t not in current]
        if candidates:
            raised = rng.choice(candidates)
            current.append(raised)
            fragments.append(fill(OBJECTION_RAISED[raised]))
    new["main_objections"] = "; ".join(current) if current else fmt.NO_OBJECTIONS

    # --- single-value categoricals --------------------------------------
    if rng.random() < 0.25:
        risk = rng.choice(fmt.RISK_VALUES if down_p >= up_p else ["No Risk Identified"] * 3 + fmt.RISK_VALUES)
        if risk != state["risk_factors"]:
            new["risk_factors"] = risk
            fragments.append(fill(RISK_NOTES[risk]))

    if rng.random() < 0.20:
        requirement = rng.choice(fmt.REQUIREMENT_VALUES)
        if requirement != state["customer_requirements"]:
            new["customer_requirements"] = requirement
            fragments.append(fill(REQUIREMENT_NOTES[requirement]))

    if rng.random() < 0.25:
        competitor = "Yes" if down_p >= up_p else rng.choice(["No", "No", "Yes"])
        if competitor != state["competitor_mention"]:
            new["competitor_mention"] = competitor
            fragments.append(fill(COMPETITOR_NOTES[competitor]))

    if rng.random() < 0.20:
        upsell = "Yes" if up_p > down_p and rng.random() < 0.6 else "No"
        if upsell != state["upsell_opportunity"]:
            new["upsell_opportunity"] = upsell
            fragments.append(fill(UPSELL_NOTES[upsell]))

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
    fragments.append(fill(OUTCOME_NOTES[outcome]))

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
    new["lead_score"] = _lead_score(new)
    new["relationship_strength"] = _relationship_strength(state, new)
    new["engagement_score"] = _engagement_score(new)

    # A meeting with no stated evidence would train the model to invent
    # movement from nothing.
    if not fragments:
        fragments.append(fill("We held a routine check-in; nothing material changed since last time."))

    rng.shuffle(fragments)
    return new, " ".join(fragments)


def generate(rows: int, seed: int = SEED) -> list:
    rng = random.Random(seed)
    examples = []
    while len(examples) < rows:
        company = rng.choice(COMPANIES)
        trajectory = rng.choices(
            list(TRAJECTORIES), weights=[0.30, 0.20, 0.20, 0.15, 0.15]
        )[0]
        state = initial_state(rng)
        for _ in range(rng.randint(2, 6)):
            if len(examples) >= rows:
                break
            new_state, notes = advance(rng, state, trajectory, company)
            examples.append({
                "instruction": fmt.INSTRUCTION,
                "input": fmt.build_user_turn(state, notes).split("\n\n", 1)[1],
                "output": fmt.render_state(new_state),
                # Not consumed by training — kept so a row can be traced back
                # to the journey that produced it when a target looks wrong.
                "meta": {"trajectory": trajectory, "company": company[0]},
            })
            state = new_state
    return examples


# ============================================================
# VALIDATION
# ============================================================

def validate(examples: list) -> int:
    """Every target must survive the real scorer. Returns the failure count."""
    try:
        import joblib
        import pandas as pd
        xgboost_dir = PROJECT_ROOT.parent / "XgBoost"
        sys.path.insert(0, str(xgboost_dir))
        from deal_score_pipeline import transform_for_inference

        bundles = sorted((xgboost_dir / "models").glob("*.pkl"))
        if not bundles:
            print("  [SKIP] no model bundle in XgBoost/models")
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
    parser.add_argument("--rows", type=int, default=600)
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

    trajectories = {}
    for example in examples:
        key = example["meta"]["trajectory"]
        trajectories[key] = trajectories.get(key, 0) + 1
    print(f"\nWrote {len(examples)} rows to {args.out}")
    print("Trajectory mix: " + ", ".join(f"{k}={v}" for k, v in sorted(trajectories.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
