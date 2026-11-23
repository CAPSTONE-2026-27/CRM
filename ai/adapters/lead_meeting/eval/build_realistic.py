"""
build_realistic.py
==================
Writes eval/realistic.jsonl: 40 hand-written qualification-meeting notes in the
shapes reps actually type -- three-line notes, dash bullets, abbreviations,
lower case, the odd long write-up -- labelled by hand with the guidance in
prompt_format.SYSTEM_PROMPT.

There is no real meeting data to test against, so this is the stand-in. The
training notes are 150-250 words of polished LLM prose; most of these are
nothing like that, which is the point.

Labelling rules (same as the prompt):
  sentiment   reaction to US. Positive = warm/enthusiastic/impressed/praise;
              Negative = sceptical of us, pushback, questioned continuing,
              frustration with us or a previous supplier; otherwise Neutral.
              Frustration with their current system is not Negative.
  intent      High = asked for proposal/quote/contract/implementation plan;
              Medium = demo, trial, POC, further evaluation; Low = info only.
  decision    Present = an approver attended; Indirect = approver briefed or
              represented; Absent = only evaluators/technical staff.
  urgency     High = named date/deadline/compelling event; Medium = rough
              timeframe; Low = none / no rush.
  interest    High = most of the platform; Medium = core or two areas;
              Low = one narrow use case.
  A signal the note never touches gets the middle value
  (Neutral / Medium / Indirect / Medium / Medium).

Run:
    python adapters/lead_meeting/eval/build_realistic.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import prompt_format as fmt  # noqa: E402

OUT = HERE / "realistic.jsonl"

# (note, (sentiment, buying_intent, decision_maker_involvement, customer_urgency, product_interest_level))
CASES = [
    ("Call w/ Ramesh (IT mgr) + 1 analyst. Only want the WhatsApp integration bit, nothing else. "
     "Asked us to send some material, will 'get back'. No timeline. Polite, no real reaction.",
     ("Neutral", "Low", "Absent", "Low", "Low")),

    ("MD joined!! Loved the demo, said it's exactly what they need. Wants full proposal + pricing "
     "for all 4 modules by Fri. Go-live before Diwali rush (6 wks).",
     ("Positive", "High", "Present", "High", "High")),

    ("- Met procurement + ops analyst\n"
     "- Compared us to Zoho on price the whole time, kept saying we're too expensive, not sure it's worth switching\n"
     "- Want a trial account for 2 wks first\n"
     "- Decision 'sometime next quarter'\n"
     "- Interested in core sales module only",
     ("Negative", "Medium", "Absent", "Medium", "Medium")),

    ("Spoke to Anita (Head of Sales, owns the budget). Businesslike, no small talk. Asked for a "
     "commercial proposal + implementation plan. Their Salesforce contract ends 31 Mar, must be live "
     "by then. Wants sales, service, marketing and analytics.",
     ("Neutral", "High", "Present", "High", "High")),

    ("quick catch up with their IT guy. he'll brief the CTO. they want a demo for the wider team. "
     "no deadline on their side, whenever it fits. interested in reporting dashboards + pipeline only. "
     "no reaction either way",
     ("Neutral", "Medium", "Indirect", "Low", "Medium")),

    ("Owner (signs the cheques) sat in. Very sceptical - said the last CRM vendor took their money and "
     "vanished, doesn't trust SaaS companies. Just wanted a brochure. No plans this year.",
     ("Negative", "Low", "Present", "Low", "Medium")),

    ("Mtg notes:\n"
     "- CFO + COO attended\n"
     "- impressed w/ the CPQ module, called our quoting 'miles ahead' of what they have\n"
     "- asked for pricing for 200 users and a contract draft\n"
     "- board deadline: live by end of Q2\n"
     "- want CPQ + sales cloud + analytics + partner portal",
     ("Positive", "High", "Present", "High", "High")),

    ("Junior analysts only. Gathering info for an internal report on CRM options. Generic questions, "
     "took notes, polite. Nothing requested. No timeframe. Only looked at the mobile app.",
     ("Neutral", "Low", "Absent", "Low", "Low")),

    ("Rohit (project lead) will take our deck to the steering committee. They'd like a follow-up demo "
     "with the support team. Hoping to decide in the next couple of months. Interest is mainly "
     "ticketing + SLA. Rohit was warm, said ours was the most helpful vendor team so far.",
     ("Positive", "Medium", "Indirect", "Medium", "Medium")),

    ("VP Ops (budget owner) + 2 IT. Pushed back hard on our implementation timeline, said 12 wks is "
     "unacceptable and doubts we can deliver. Still wants a detailed proposal. Regulatory audit in "
     "8 wks is the driver. Only interested in the audit trail.",
     ("Negative", "High", "Present", "High", "Low")),

    ("Met Kavita Rao, IT Director at Sunshine Retail (approx 900 staff, 60 stores). She was clear the "
     "final call sits with their COO, who she will brief this week. They run a mix of Tally and Excel "
     "today and want to consolidate. Kavita went through every module with us - sales, service, "
     "loyalty, analytics - and could see most of them working across store ops and head office. She "
     "asked for a sandbox to evaluate for a few weeks with her team. Timeline is loosely 'this "
     "financial year'. Reaction was measured; she listened, asked detailed questions and didn't give "
     "much away about how we compare.",
     ("Neutral", "Medium", "Indirect", "Medium", "High")),

    ("their director no-showed, met only the sys admin. he said the project is on ice, no budget, "
     "revisit next year maybe. didnt want anything sent.",
     ("Neutral", "Low", "Absent", "Low", "Medium")),

    ("Great mtg. Founder + CTO both there, super enthusiastic, kept saying 'this is what we've been "
     "looking for'. Want a demo for the whole sales team next week. No hard deadline but want something "
     "in place in 2-3 months. Mostly keen on lead mgmt + email automation.",
     ("Positive", "Medium", "Present", "Medium", "Medium")),

    ("Their head of procurement was abrupt, questioned why they should even continue talks at our "
     "price. CFO not present - procurement reports to her and will update her. Asked for a revised "
     "quote. Current tool licence expires in 5 weeks.",
     ("Negative", "High", "Indirect", "High", "Medium")),

    ("Ops team (3 ppl, no managers). wanted to understand offline mode for field staff. that's it. "
     "asked for a trial login to play with. no rush.",
     ("Neutral", "Medium", "Absent", "Low", "Low")),

    ("- Head of BU (approver) attended\n"
     "- tone: courteous, reserved\n"
     "- asked for implementation plan + contract terms\n"
     "- timing: 'sometime next quarter'\n"
     "- interest: sales + service modules",
     ("Neutral", "High", "Present", "Medium", "Medium")),

    ("Spoke w/ Mr. Kapoor, MD. He's fed up with their current vendor (constant downtime) - not with us, "
     "he really liked our uptime story and said so. Asked for a proposal for full platform rollout "
     "across 3 plants. Must go live before April 1 (new FY).",
     ("Positive", "High", "Present", "High", "High")),

    ("Analyst from their strategy team doing a vendor scan. Collected brochures. Asked a bit about "
     "everything at a high level, nothing specific. No timeline mentioned. Tone neutral.",
     ("Neutral", "Low", "Absent", "Low", "Medium")),

    ("CEO on the call for 10 min, rest with IT. CEO said 'impressive' twice. Demo for ops heads "
     "requested. Go-live target end of quarter, board has been told.",
     ("Positive", "Medium", "Present", "High", "Medium")),

    ("Regional manager attended, says he will push it to the national head. Openly doubtful we'd "
     "handle their volume (2M records), asked us to prove it with a POC. Timeline flexible, nothing "
     "pressing. Only cares about data import + dedupe.",
     ("Negative", "Medium", "Indirect", "Low", "Low")),

    ("pharma distributor. purchase head + MD. MD very warm, thanked us for the detailed demo. asked "
     "for final pricing + payment terms. need go-live in 30 days, existing system contract ends. "
     "want everything: SFA, inventory sync, analytics, service",
     ("Positive", "High", "Present", "High", "High")),

    ("Met their IT team only. Lots of questions on API limits and SSO. Will evaluate in a sandbox over "
     "the next month. Neither impressed nor dismissive. Target is rough - 'H2'. Integration + SSO are "
     "the only parts they care about.",
     ("Neutral", "Medium", "Absent", "Medium", "Low")),

    ("Follow-up call cancelled twice by them. Today spoke to their EA who said the director is "
     "'reviewing options' and she'll pass on our note. No requests. No date.",
     ("Neutral", "Low", "Indirect", "Low", "Medium")),

    ("Second meeting with Greenfield Logistics. Their COO (who approves spend) joined for the first half "
     "and spent most of it questioning whether a new CRM is worth the disruption, given a painful "
     "rollout with a previous vendor two years ago. He was blunt that he's not convinced we'd be any "
     "different. The ops manager was more open but deferred to him. They did agree to a short trial "
     "at one depot. No timeline; COO said 'no rush, we've survived this long'. Interest limited to "
     "the dispatch tracking piece.",
     ("Negative", "Medium", "Present", "Low", "Low")),

    ("Bullets from today:\n"
     "• attendees: CTO + 2 devs (CTO signs off on tooling)\n"
     "• CTO complimented the API docs, said best he's seen\n"
     "• want pricing for 50 dev seats + enterprise support\n"
     "• no deadline\n"
     "• interest: API + integrations only",
     ("Positive", "High", "Present", "Low", "Low")),

    ("Insurance broker, 40 ppl. Spoke to the office manager who handles admin; owner not involved yet "
     "but she'll show him. Wants a demo of policy renewal reminders + lead tracking. Renewal season "
     "starts in 7 weeks, wants something before then. Matter-of-fact, no strong reaction.",
     ("Neutral", "Medium", "Indirect", "High", "Medium")),

    ("School chain admin staff (no principal or trustee). Info gathering, said it can wait for next "
     "year's planning round. No requests. They were happy with our presentation and said so.",
     ("Positive", "Low", "Absent", "Low", "Medium")),

    ("dir. of ops present, budget already signed off. wants contract this week. must switch before "
     "their hubspot renewal on the 15th. full suite. businesslike.",
     ("Neutral", "High", "Present", "High", "High")),

    ("Spoke to two sales reps their manager asked to 'look at CRMs'. They moaned that their current "
     "tool is clunky. They liked our mobile app a lot - said reps would actually use it. Will report "
     "back to the manager. No timeline. Only the mobile app came up.",
     ("Positive", "Low", "Indirect", "Low", "Low")),

    ("Finance controller (approves IT spend) + IT head. Controller challenged every number on our ROI "
     "slide, clearly unconvinced. IT head ok with the tech. They want a detailed proposal anyway because "
     "their ERP vendor is sunsetting the CRM add-on in Sept. Interest: quoting and finance integration.",
     ("Negative", "High", "Present", "High", "Medium")),

    ("demo done. client liked it. wants a quote. MD attended. no rush.",
     ("Positive", "High", "Present", "Low", "Medium")),

    ("Hospital group. Met the CMIO's deputy; CMIO will decide. Deputy questioned whether our product "
     "meets their compliance needs, sounded doubtful about going further. Asked only for compliance "
     "docs to keep on file. NABH audit in 10 weeks is why they're looking. Interest: patient feedback "
     "module only.",
     ("Negative", "Low", "Indirect", "High", "Low")),

    ("Textile exporter. Owner + son attended. Measured, practical questions. Want a 2-week trial for "
     "their sales team. Plan to decide in a couple of months. Interested in order tracking, dealer "
     "portal, reports and the sales app - basically everything we showed.",
     ("Neutral", "Medium", "Present", "Medium", "High")),

    ("Call with an IT consultant the client hired to shortlist vendors. Client's GM decides; the "
     "consultant reports to him. He wanted a feature matrix to compare vendors. Very neutral. Their "
     "go-live goal is 'Q3'.",
     ("Neutral", "Low", "Indirect", "Medium", "Medium")),

    ("- ops head attended (budget is his)\n"
     "- not happy w/ how we handled the last support ticket in the pilot, said our response was slow\n"
     "- wants to pause, questioned continuing\n"
     "- no timeline\n"
     "- pilot only used the ticketing module",
     ("Negative", "Low", "Present", "Low", "Low")),

    ("real estate developer, sales director (owns budget) present. she was pleasantly surprised by "
     "the site-visit scheduling, said 'love this'. asked for implementation plan + pricing for 3 "
     "projects. new project launch in 6 weeks. interest across sales, site visits, broker portal and "
     "analytics.",
     ("Positive", "High", "Present", "High", "High")),

    ("IT security team review only. They grilled us on data residency; no strong reaction either way. "
     "They'll send findings to the CIO. Nothing requested. No timeline. Data residency was all they "
     "looked at.",
     ("Neutral", "Low", "Indirect", "Low", "Low")),

    ("Visited Arora Foods' Ludhiana plant. Met Mr. Arora (MD) and his sales head. Both were genuinely "
     "enthusiastic - the MD said he wished he'd found us two years ago. They want us back to demo for "
     "the 12 regional sales managers before taking it further. Would like to be live sometime before "
     "next season but no fixed date. Interest is the sales app and distributor management; they don't "
     "need service or marketing.",
     ("Positive", "Medium", "Present", "Medium", "Medium")),

    ("Startup founder, 8 people. Just exploring, said a CRM is probably overkill for them right now. "
     "Asked how pricing works in general. Polite. Revisit in a year maybe.",
     ("Neutral", "Low", "Present", "Low", "Low")),

    ("Cooperative bank. GM-IT and DGM operations; GM-IT signs off. Very formal. Need a proposal with "
     "implementation plan for 25 branches; RBI compliance date is 30 June. Want CRM, service, "
     "compliance reporting and analytics - full scope.",
     ("Neutral", "High", "Present", "High", "High")),
]


def main() -> None:
    rows = []
    for index, (note, values) in enumerate(CASES, 1):
        signals = dict(zip(fmt.FIELD_ORDER, values))
        for field, value in signals.items():
            assert value in fmt.ALLOWED_VALUES[field], (index, field, value)
        rows.append({"meeting_id": f"REAL-MTG-{index:03d}", "meeting_notes": note, "signals": signals})
    with OUT.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} rows to {OUT}")


if __name__ == "__main__":
    main()
