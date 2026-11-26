"""
build_realistic.py
==================
Writes eval/realistic.jsonl: 24 hand-written deal meetings -- a previous CRM
state, the rep's notes in their own shorthand, and the state those notes
should produce.

There is no real deal data to test against, so this is the stand-in. None of
the notes reuse the generator's phrasings, several jump more than one step on a
scale (real meetings do; the generator never does), and some are traps:
  * small talk and status calls that should change nothing,
  * a value the notes restate that is already in the previous state,
  * frustration that is about us (sentiment) vs. a requirement (no sentiment).

Each case lists only the fields the notes change, plus meeting_outcome (every
meeting has one). The expected state is the previous state with those changes
applied, and the four derived numerics recomputed by
prompt_format.apply_derived_fields() -- exactly what the server returns.

Run:
    python adapters/deal_state/eval/build_realistic.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import prompt_format as fmt  # noqa: E402

OUT = HERE / "realistic.jsonl"

S, I, B, D, U, P, R = ("customer_sentiment", "buying_intent", "budget_status",
                       "decision_maker_involvement", "customer_urgency",
                       "product_interest_level", "implementation_readiness")


def state(sent, intent, budget, dm, urgency, interest, ready, *, meetings=2, rel=5.0,
          objections=fmt.NO_OBJECTIONS, competitor="No", upsell="No",
          requirements="Standard Package", risk="No Risk Identified") -> dict:
    s = dict(fmt.DEFAULTS)
    s.update({S: sent, I: intent, B: budget, D: dm, U: urgency, P: interest, R: ready,
              "total_meetings": meetings, "relationship_strength": float(rel),
              "main_objections": objections, "competitor_mention": competitor,
              "upsell_opportunity": upsell, "customer_requirements": requirements,
              "risk_factors": risk, "meeting_outcome": "Discussed Requirements"})
    s["lead_score"] = fmt.lead_score(s)
    s["engagement_score"] = fmt.engagement_score(s)
    return s


DISCUSSED = "Discussed Requirements"

# (previous state, notes, changes)
CASES = [
    (state("Neutral", "Low", "Not Allocated", "No", "Low", "Medium", "Not Ready", meetings=1, rel=3),
     "Call w/ Sunil. Finance put the CRM request into this month's budget review, decision expected "
     "in 3 wks. He wants a demo for the sales mgrs before going further. Discussed reqs.",
     {B: "Under Review", I: "Medium", "meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Under Review", "Indirect", "Medium", "High", "Partially Ready",
           meetings=3, rel=6, objections="Price Too High", competitor="Yes",
           requirements="Multi-department Rollout"),
     "- CFO approved the full budget!\n- revised pricing accepted, no more pushback on cost\n"
     "- they want the contract + impl plan this week\n- sent proposal after the call",
     {B: "Fully Approved", "main_objections": fmt.NO_OBJECTIONS, I: "High",
      "meeting_outcome": "Proposal Sent"}),

    (state("Positive", "High", "Fully Approved", "Yes", "High", "Very High", "Ready", meetings=5, rel=8),
     "Mtg cancelled by them 10 min before. No reason given.",
     {"meeting_outcome": "No Show / Cancelled"}),

    (state("Neutral", "Medium", "Partially Approved", "Indirect", "Medium", "High", "Partially Ready",
           meetings=3, rel=5),
     "Tough one. Ravi was annoyed with how slow we've been on follow-ups, said they're losing faith "
     "in us. He also flagged that their security team still has open questions on where the data is "
     "hosted. Rest of the time we went through their requirements.",
     {S: "Negative", "main_objections": "Security / Compliance Concerns", "meeting_outcome": DISCUSSED}),

    (state("Neutral", "Medium", "Under Review", "No", "Medium", "Medium", "Not Ready", meetings=2, rel=4),
     "Finally met their MD (she signs off). She joined for 30 min and asked direct questions on "
     "rollout cost and timelines. Mentioned they're also talking to Zoho. Worked through requirements.",
     {D: "Yes", "competitor_mention": "Yes", "meeting_outcome": DISCUSSED}),

    (state("Positive", "High", "Fully Approved", "Yes", "High", "High", "Ready", meetings=6, rel=8),
     "Verbal yes from Anjali!! Paperwork to follow next week. She also asked about adding the "
     "service module + 30 more licences later in the year.",
     {"meeting_outcome": "Verbal Agreement", "upsell_opportunity": "Yes"}),

    (state("Neutral", "Medium", "Under Review", "Indirect", "Medium", "Medium", "Partially Ready",
           meetings=3, rel=5, objections="No Urgent Business Need"),
     "Big change - their current vendor is shutting the product down end of next month, so they have "
     "to replace it ASAP, can't operate without it. So the 'nothing forcing us' point is gone. "
     "Discussed reqs.",
     {U: "Critical", "main_objections": fmt.NO_OBJECTIONS, "meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Partially Approved", "Yes", "High", "High", "Partially Ready",
           meetings=4, rel=7),
     "Our champion Deepak is moving to the Singapore office next month - worried the project loses "
     "its sponsor. Otherwise a routine status call, nothing else new. Went over requirements briefly.",
     {"risk_factors": "Stakeholder Turnover", "meeting_outcome": DISCUSSED}),

    (state("Neutral", "Low", "Not Allocated", "No", "Low", "Low", "Not Ready", meetings=2, rel=3),
     "small talk mostly - his kid's cricket match, the new office. no updates on their side. "
     "checked in on requirements.",
     {"meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Under Review", "Indirect", "High", "High", "Partially Ready",
           meetings=4, rel=6, objections="Long Implementation Time", competitor="Yes"),
     "- phased rollout plan worked, they're fine with the timeline now\n"
     "- IT has the environment set up and team allocated, waiting on us to schedule kickoff\n"
     "- they've dropped the other vendor, we're the only one in the running now\n- went through reqs",
     {"main_objections": fmt.NO_OBJECTIONS, R: "Ready", "competitor_mention": "No",
      "meeting_outcome": DISCUSSED}),

    (state("Positive", "High", "Partially Approved", "Yes", "High", "Very High", "Ready", meetings=5, rel=7),
     "They said our quote is way above what Finance expects and want a better number. Legal also "
     "pushed back on the 3-yr lock-in and the exit clause. We'll revise. Discussed reqs.",
     {"main_objections": "Price Too High; Unfavorable Contract Terms", "meeting_outcome": DISCUSSED}),

    (state("Neutral", "Medium", "Under Review", "Indirect", "Medium", "Medium", "Partially Ready",
           meetings=3, rel=5),
     "Rescheduled - their IT head got pulled into an outage, we covered 10 mins and moved the rest "
     "to Thursday.",
     {"meeting_outcome": "Rescheduled"}),

    (state("Neutral", "Medium", "Under Review", "Indirect", "Medium", "High", "Partially Ready",
           meetings=3, rel=5),
     "Scope grew - they now want it for sales, service AND finance teams. Kavita wants every module "
     "in, calls it strategic for them. Discussed reqs in detail.",
     {"customer_requirements": "Multi-department Rollout", P: "Very High", "meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Under Review", "Yes", "Medium", "High", "Partially Ready",
           meetings=3, rel=6),
     "Finance has pulled the CRM request - there's no money set aside for it now. Ajay is still keen "
     "personally. Discussed what a smaller phase 1 could look like.",
     {B: "Not Allocated", "meeting_outcome": DISCUSSED}),

    (state("Negative", "Low", "Not Allocated", "No", "Low", "Medium", "Not Ready", meetings=3, rel=3,
           objections="Poor Past Experience / Support"),
     "Better meeting. He spoke to two of our references and is now comfortable with our support "
     "model. Much warmer today, thanked us for being patient. Wants a trial for the sales team "
     "before deciding. Went through their reqs again.",
     {"main_objections": fmt.NO_OBJECTIONS, S: "Positive", I: "Medium", "meeting_outcome": DISCUSSED}),

    (state("Positive", "High", "Fully Approved", "Yes", "Critical", "Very High", "Fully Ready",
           meetings=6, rel=8),
     "Proposal sent tonight. Their architects raised a worry that our API won't hold up at their "
     "transaction volumes - need to address that before they sign.",
     {"meeting_outcome": "Proposal Sent", "risk_factors": "Technical Concerns"}),

    (state("Neutral", "Medium", "Partially Approved", "Indirect", "Medium", "Medium", "Partially Ready",
           meetings=3, rel=5),
     "Sales head and IT head clearly disagree on who owns the CRM - IT wants control, sales wants to "
     "run it. Could stall things. Otherwise covered requirements.",
     {"risk_factors": "Internal Politics", "meeting_outcome": DISCUSSED}),

    (state("Neutral", "Medium", "Under Review", "Indirect", "Low", "Medium", "Not Ready", meetings=2, rel=4),
     "They now want to go live before their annual sales conference on 15 March, so there's a hard "
     "date. Rahul nominated a project manager on their side but nothing else is set up yet. "
     "Discussed requirements.",
     {U: "High", R: "Partially Ready", "meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Under Review", "Indirect", "Medium", "High", "Partially Ready",
           meetings=3, rel=6),
     "Compliance is driving this now - the regulator wants an audit trail on all customer comms and "
     "that's the core requirement. Talked it through.",
     {"customer_requirements": "Compliance-driven Requirements", "meeting_outcome": DISCUSSED}),

    (state("Positive", "High", "Fully Approved", "Yes", "High", "High", "Ready", meetings=5, rel=8),
     "Pricing call w/ procurement. Very matter-of-fact, none of the earlier enthusiasm, they just went "
     "line by line. Sent the final proposal afterwards.",
     {S: "Neutral", "meeting_outcome": "Proposal Sent"}),

    (state("Neutral", "Medium", "Under Review", "No", "Medium", "Medium", "Partially Ready",
           meetings=2, rel=4),
     "Contact says their COO is now in the loop and getting weekly updates from her, but the COO "
     "hasn't joined any call. Covered reqs.",
     {D: "Indirect", "meeting_outcome": DISCUSSED}),

    (state("Positive", "Medium", "Partially Approved", "Yes", "High", "High", "Ready", meetings=4, rel=7,
           objections="Missing Features"),
     "Roadmap walkthrough closed the feature gap - they're happy the Q2 release covers what they "
     "need. VP said they're ready to sign once the paperwork is sorted - verbal go-ahead.",
     {"main_objections": fmt.NO_OBJECTIONS, I: "High", "meeting_outcome": "Verbal Agreement"}),

    (state("Neutral", "Low", "Not Allocated", "No", "Low", "Low", "Not Ready", meetings=2, rel=3),
     "Still only cares about the one dashboard, rest of the platform not relevant they said. No "
     "change otherwise. Spent the time on reqs.",
     {"meeting_outcome": DISCUSSED}),

    (state("Positive", "High", "Fully Approved", "Yes", "High", "Very High", "Ready", meetings=6, rel=8,
           upsell="Yes"),
     "Kickoff prep call. Their team, data migration and training plan are all signed off, cutover "
     "weekend booked. Went over requirements one last time.",
     {R: "Fully Ready", "meeting_outcome": DISCUSSED}),
]


def main() -> None:
    rows = []
    for index, (previous, notes, changes) in enumerate(CASES, 1):
        new = dict(previous, **changes)
        coerced, repairs = fmt.coerce_state(new)
        assert not repairs, (index, repairs)
        expected, _ = fmt.apply_derived_fields(previous, coerced)
        rows.append({"id": f"REAL-DS-{index:03d}", "previous_state": previous,
                     "meeting_notes": notes, "expected_state": expected,
                     "changed_fields": sorted(changes)})
    with OUT.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} rows to {OUT}")


if __name__ == "__main__":
    main()
