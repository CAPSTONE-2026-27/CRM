"""
generate_meeting_dataset.py
===========================
Synthesises the lead qualification-meeting dataset: a sales executive's written
notes, and the five business signals a reader should be able to infer from them.

Method
------
Labels first, prose second. Each record samples a signal combination, then a
business scenario consistent with it, then asks the local base Llama to write
the meeting note. The labels are therefore ground truth by construction -- there
is no labelling judgement that could be wrong, and no drift between what the
note says and what the target claims.

The alternative -- write notes, then label them -- needs a second model whose
mistakes become permanent training targets. This way the only thing that can go
wrong is the note failing to convey its labels, which is checkable (see
validate()) in a way that a wrong label is not.

Why a model writes the prose and not a template
-----------------------------------------------
The task being trained is reading unstructured business English. A template
generator produces text with a recoverable surface pattern, and a model trained
on it learns the pattern rather than the language -- it scores well on held-out
rows from the same generator and fails on the first real meeting note. Slower,
but the variety is the point.

Run:
    python scripts/generate_meeting_dataset.py --rows 500
    python scripts/generate_meeting_dataset.py --rows 20 --out data/sample.jsonl
"""

from __future__ import annotations

import argparse
import itertools
import json
import random
import re
import sys
import time
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

ADAPTER_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ADAPTER_DIR))
import prompt_format as fmt  # noqa: E402

AI_ROOT = ADAPTER_DIR.parent.parent
MODEL_PATH = AI_ROOT / "base-model" / "Llama-3.1-8B-Instruct"
OUT_PATH = ADAPTER_DIR / "data" / "meeting_train.jsonl"
SEED = 42

MIN_WORDS, MAX_WORDS = 150, 250


# ============================================================
# SCENARIO VOCABULARY
# ============================================================

INDUSTRIES = [
    "Banking", "Healthcare", "Retail", "Manufacturing", "Education",
    "Telecommunications", "Insurance", "Logistics", "IT Services",
    "Construction", "Automobile", "Pharmaceutical", "Real Estate",
    "E-commerce", "Government",
]

COMPANY_SIZES = [
    ("a early-stage startup", "around 25 staff"),
    ("a growing SME", "roughly 140 employees"),
    ("a mid-market firm", "about 600 employees"),
    ("a large enterprise", "over 4,000 employees"),
    ("a global corporation", "more than 20,000 employees across several regions"),
]

CURRENT_SYSTEMS = [
    "Salesforce", "Zoho CRM", "HubSpot", "Microsoft Dynamics",
    "spreadsheets and shared drives", "an in-house custom system",
    "a legacy on-premise tool", "no CRM at all",
]

COMPETITORS = ["Zoho", "Salesforce", "HubSpot", "Freshworks", "Pipedrive",
               "Microsoft Dynamics", "a regional vendor", None, None, None]

PAIN_POINTS = [
    "duplicate records across teams", "no visibility of the sales pipeline",
    "manual reporting that takes days", "leads going cold without follow-up",
    "disconnected support and sales data", "compliance reporting done by hand",
    "field staff unable to update records offline",
    "quotations taking too long to produce", "no forecasting capability",
    "customer history scattered across email",
]

REQUIREMENTS = [
    "integration with their existing ERP", "role-based access for regional teams",
    "a mobile app for field staff", "custom dashboards for the leadership team",
    "automated workflow approvals", "data residency within the country",
    "single sign-on", "bulk import from their current system",
    "multi-currency support", "an audit trail for regulatory review",
]

WRITING_STYLES = [
    "brisk and factual, short sentences, minimal adjectives",
    "detailed and narrative, walking through the meeting in order",
    "structured around what the customer asked for and what we committed to",
    "reflective, weighing up how the meeting felt overall",
    "clipped and note-like, the way a busy rep types between calls",
    "thorough and slightly formal, as if the manager will read it",
]

# Words that would give the answer away. The model must infer the labels from
# business meaning, so a note containing the label itself teaches it to
# keyword-match instead of read.
BANNED = re.compile(
    r"\b(positive|negative|neutral|high intent|buying intent|urgency|"
    r"sentiment|product interest|decision[- ]maker involvement|"
    r"score|rating|qualified|priority)\b",
    re.IGNORECASE,
)


# ============================================================
# SIGNAL SAMPLING
# ============================================================

def stratified_signal_plan(rows: int, rng: random.Random) -> list:
    """Signal combinations spread across the whole score range.

    Sampling each signal independently would cluster meeting scores around the
    middle and leave High and Very Low Priority barely represented -- the two
    bands a manager most needs the model to get right. So combinations are
    bucketed by the score they earn and drawn evenly from each band.
    """
    every_combination = [
        dict(zip(fmt.FIELD_ORDER, values))
        for values in itertools.product(*(fmt.ALLOWED_VALUES[f] for f in fmt.FIELD_ORDER))
    ]

    by_band: dict[str, list] = {}
    for combination in every_combination:
        band = fmt.priority_for(fmt.meeting_score(combination))
        by_band.setdefault(band, []).append(combination)

    bands = list(by_band)
    plan = []
    for index in range(rows):
        band = bands[index % len(bands)]
        plan.append(rng.choice(by_band[band]))
    rng.shuffle(plan)
    return plan


def scenario_for(signals: dict, rng: random.Random) -> dict:
    """A business situation consistent with the sampled signals.

    The brief describes *what happened* -- who attended, what was asked for, what
    was said about timing -- never the label. Handing the writer "Buying Intent:
    High" would produce a note that says so; handing it "they asked us to send a
    commercial proposal" produces a note a model has to actually read.
    """
    size, headcount = rng.choice(COMPANY_SIZES)

    attendance = {
        "Present": rng.choice([
            "Their managing director joined the call and led most of the discussion",
            "The CFO attended in person and asked about commercial terms directly",
            "Their VP of Operations, who owns the budget for this, was in the room",
            "The head of the business unit attended and confirmed she signs off on tooling",
        ]),
        "Indirect": rng.choice([
            "Our contact is preparing a recommendation for the director, who could not attend",
            "The decision sits with their CTO, who was briefed afterwards but was not on the call",
            "Only the project lead attended; she will take our material to the steering committee",
            "The budget owner was travelling; our contact will relay the discussion",
        ]),
        "Absent": rng.choice([
            "Only two analysts from the operations team attended",
            "The session was with their IT staff; nobody from the business side joined",
            "Attendance was limited to junior team members evaluating options",
            "We met the technical team only; leadership were not involved at this stage",
        ]),
    }[signals["decision_maker_involvement"]]

    ask = {
        "High": rng.choice([
            "They asked us to send a formal commercial proposal this week",
            "They walked us through their procurement process and asked what we need to raise a PO",
            "They want to move to contract discussions and asked about implementation timelines",
            "They requested detailed pricing for the full rollout and asked who our legal contact is",
        ]),
        "Medium": rng.choice([
            "They asked for a product demonstration with their wider team",
            "They want indicative pricing to circulate internally before deciding",
            "They asked for a follow-up session covering the reporting features",
            "They would like a trial environment to evaluate over a few weeks",
        ]),
        "Low": rng.choice([
            "They are gathering information and made no request to take things further",
            "They asked for some background material to keep on file",
            "The conversation stayed general; no next step was requested",
            "They said they are surveying the market and will come back if it becomes a priority",
        ]),
    }[signals["buying_intent"]]

    timing = {
        "High": rng.choice([
            "Their current contract terminates at the end of next month, which sets a firm date",
            "A regulatory deadline in eight weeks means this has to be live before then",
            "They have committed to their board that this is in place by the end of the quarter",
            "Their peak season starts in six weeks and they cannot go into it on the current setup",
        ]),
        "Medium": rng.choice([
            "They are targeting sometime next quarter, though nothing is fixed",
            "They mentioned wanting this in place within the next few months",
            "The rough plan is to decide after their budget cycle closes",
        ]),
        "Low": rng.choice([
            "No timeline was discussed; this is not pressing for them",
            "They said it could wait until next year's planning round",
            "There is no internal pressure to move on this at the moment",
        ]),
    }[signals["customer_urgency"]]

    breadth = {
        "High": rng.choice([
            "They went through most of the platform in detail and could see it used across several departments",
            "They were interested in the full suite, including the modules we did not lead with",
            "They spent the bulk of the session on the advanced capabilities and asked strong questions throughout",
        ]),
        "Medium": rng.choice([
            "Their interest centred on the core modules; the rest they set aside for now",
            "They see a fit for the standard functionality but nothing beyond that yet",
            "They focused on two of the four areas we covered",
        ]),
        "Low": rng.choice([
            "Only one narrow use case was of interest; they considered the rest unnecessary",
            "They engaged with a small part of what we showed and dismissed the remainder",
            "Their interest was limited to a single reporting requirement",
        ]),
    }[signals["product_interest_level"]]

    mood = {
        "Positive": rng.choice([
            "The tone throughout was warm and the team were visibly engaged",
            "They were enthusiastic, particularly after the walkthrough",
            "The meeting went well; they were open, asked a lot, and were complimentary about the approach",
        ]),
        "Neutral": rng.choice([
            "The tone was businesslike; they listened, took notes and gave little away",
            "They were courteous but hard to read, working steadily through their own agenda",
            "Measured throughout, with no strong reaction either way",
        ]),
        "Negative": rng.choice([
            "The mood was difficult; they were sceptical from the outset and pushed back repeatedly",
            "They were visibly frustrated, referring back to a poor experience with a previous supplier",
            "The session was uncomfortable; they questioned whether this was worth continuing",
        ]),
    }[signals["customer_sentiment"]]

    competitor = rng.choice(COMPETITORS)
    return {
        "industry": rng.choice(INDUSTRIES),
        "size": size,
        "headcount": headcount,
        "current_system": rng.choice(CURRENT_SYSTEMS),
        "pain": rng.sample(PAIN_POINTS, 2),
        "requirement": rng.choice(REQUIREMENTS),
        "competitor": competitor,
        "style": rng.choice(WRITING_STYLES),
        "attendance": attendance,
        "ask": ask,
        "timing": timing,
        "breadth": breadth,
        "mood": mood,
    }


def writer_prompt(scenario: dict) -> str:
    competitor_line = (
        f"- They mentioned they are also looking at {scenario['competitor']}.\n"
        if scenario["competitor"] else ""
    )
    return (
        "You are a Sales Executive writing up notes in your CRM straight after a "
        "customer qualification meeting.\n\n"
        f"Write the meeting note. {MIN_WORDS}-{MAX_WORDS} words, one or two "
        "paragraphs, professional business English, first person plural "
        "('we met', 'they asked').\n\n"
        f"Writing style: {scenario['style']}.\n\n"
        "What happened:\n"
        f"- The customer is {scenario['size']} in {scenario['industry']}, {scenario['headcount']}.\n"
        f"- They currently use {scenario['current_system']}.\n"
        f"- Their problems: {scenario['pain'][0]}, and {scenario['pain'][1]}.\n"
        f"- They need {scenario['requirement']}.\n"
        f"- {scenario['attendance']}.\n"
        f"- {scenario['mood']}.\n"
        f"- {scenario['breadth']}.\n"
        f"- {scenario['ask']}.\n"
        f"- {scenario['timing']}.\n"
        f"{competitor_line}"
        "\nRules:\n"
        "- Describe what happened. Do NOT label anything.\n"
        "- Never use the words: positive, negative, neutral, high, medium, low, "
        "sentiment, intent, urgency, priority, score, qualified.\n"
        "- Do not write headings, bullet points or a title. Prose only.\n"
        "- Do not invent a score or rating.\n"
        "- Start directly with the note. No preamble.\n"
    )


# ============================================================
# GENERATION
# ============================================================

def load_model():
    tokenizer = AutoTokenizer.from_pretrained(
        str(MODEL_PATH), trust_remote_code=True, local_files_only=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(
        str(MODEL_PATH),
        quantization_config=BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True),
        device_map="auto", trust_remote_code=True, local_files_only=True)
    model.eval()
    return tokenizer, model


def write_note(tokenizer, model, scenario: dict, rng: random.Random) -> str:
    prompt = tokenizer.apply_chat_template(
        [{"role": "user", "content": writer_prompt(scenario)}],
        tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt")
    inputs = {k: v.to(model.device) for k, v in inputs.items()}
    prompt_len = inputs["input_ids"].shape[-1]
    with torch.no_grad():
        out = model.generate(
            **inputs,
            # 600, not 420: a 250-word note is ~330 tokens, and the model often
            # spends a few on a preamble before starting. At 420 roughly a
            # quarter of notes were cut off mid-sentence, which is worse than it
            # sounds -- the clipped clause is frequently the one carrying the
            # timeline or the ask, so the note stops supporting its own label.
            max_new_tokens=600,
            # Sampled, not greedy: 500 greedy generations from similar briefs
            # would collapse into near-identical prose, which is the template
            # problem arriving by a different route.
            do_sample=True,
            temperature=0.95,
            top_p=0.92,
            repetition_penalty=1.08,
            eos_token_id=tokenizer.eos_token_id,
            pad_token_id=tokenizer.eos_token_id,
        )
    text = tokenizer.decode(out[0][prompt_len:], skip_special_tokens=True).strip()
    # Models routinely open with "Here is the meeting note:" despite being told
    # not to. Strip a leading line that is clearly a preamble rather than prose.
    lines = [line for line in text.split("\n") if line.strip()]
    if lines and len(lines[0]) < 80 and lines[0].rstrip().endswith(":"):
        lines = lines[1:]
    return " ".join(line.strip() for line in lines).strip()


def validate(note: str) -> str | None:
    """Returns a rejection reason, or None when the note is usable."""
    words = len(note.split())
    if words < MIN_WORDS:
        return f"too short ({words}w)"
    if words > MAX_WORDS + 60:
        return f"too long ({words}w)"
    leak = BANNED.search(note)
    if leak:
        # A note containing the label teaches keyword matching rather than
        # reading, which is exactly the failure this dataset exists to avoid.
        return f"leaked label word {leak.group(0)!r}"
    if note.lstrip().startswith(("#", "-", "*", "1.")):
        return "formatted as a list"
    if not note.rstrip().endswith((".", "!", "?", '."', ".'")):
        # Ran out of tokens mid-sentence. Not cosmetic: the trailing clause is
        # often where the ask or the timeline lands, so a truncated note can
        # stop supporting the label it was generated for.
        return "truncated mid-sentence"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=500)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--out", type=Path, default=OUT_PATH)
    parser.add_argument("--attempts", type=int, default=3,
                        help="regeneration attempts per row before giving up")
    parser.add_argument("--restart", action="store_true",
                        help="discard any existing rows and start from scratch")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    plan = stratified_signal_plan(args.rows, rng)

    # Resume from whatever a previous run already wrote.
    #
    # An earlier version buffered everything in memory and wrote once at the
    # end, so being stopped -- for a demo, a reboot, anything -- threw away the
    # entire run. Fifty minutes of GPU time went that way. Rows are now appended
    # as they are produced and the plan is skipped forward past them.
    args.out.parent.mkdir(parents=True, exist_ok=True)
    done = 0
    seen = set()
    if args.out.exists() and not args.restart:
        with args.out.open(encoding="utf-8") as handle:
            for line in handle:
                try:
                    existing = json.loads(line)
                except json.JSONDecodeError:
                    continue  # a partial final line from a hard kill
                done += 1
                seen.add(" ".join(existing["meeting_notes"].lower().split()[:12]))
        if done:
            print(f"Resuming: {done} rows already in {args.out}")
    if done >= len(plan):
        print(f"Nothing to do -- {done} rows already generated.")
        return 0

    print(f"Loading base model for note generation: {MODEL_PATH}")
    tokenizer, model = load_model()

    rejected, kept = 0, 0
    started = time.time()
    # Line-buffered append: a row is on disk the moment it is accepted, so a
    # kill costs at most the note in flight.
    handle = args.out.open("a" if done else "w", encoding="utf-8", buffering=1)

    for index, signals in enumerate(plan, 1):
        if index <= done:
            continue
        note = None
        for _ in range(args.attempts):
            scenario = scenario_for(signals, rng)
            candidate = write_note(tokenizer, model, scenario, rng)
            reason = validate(candidate)
            # First 12 words as a cheap duplicate key: two notes opening
            # identically are the collapse-into-one-voice failure, even when the
            # remainder differs.
            fingerprint = " ".join(candidate.lower().split()[:12])
            if reason is None and fingerprint not in seen:
                seen.add(fingerprint)
                note = candidate
                break
            rejected += 1
        if note is None:
            continue

        score = fmt.meeting_score(signals)
        record = {
            "meeting_id": f"MTG-{index:05d}",
            "meeting_notes": note,
            # The lead's score BEFORE this meeting, from firmographics the notes
            # say nothing about. Deliberately uncorrelated with the signals: a
            # strong lead can have a poor meeting, and the model must not learn
            # to read the score off the prior.
            "lead_score": rng.randint(20, 95),
            # Training target. Not in the requested column list, but a dataset
            # without it cannot supervise an extraction model.
            "signals": signals,
            "messages": fmt.build_messages(note, signals),
            # Diagnostics, not consumed by training.
            "meta": {"meeting_score": score, "priority": fmt.priority_for(score)},
        }
        handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        kept += 1

        if index % 10 == 0:
            elapsed = time.time() - started
            rate = elapsed / max(1, index - done)
            remaining = (len(plan) - index) * rate / 60
            # flush=True: without it Python block-buffers stdout when redirected
            # to a file, and the progress this line exists to report stays
            # invisible for the whole run.
            print(f"  {index}/{len(plan)}  kept={kept} rejected={rejected}  "
                  f"{rate:.1f}s/row  eta {remaining:.0f}m", flush=True)

    handle.close()

    # Read back what was written rather than reporting from memory.
    #
    # An earlier version of this summary re-opened the output with mode "w" to
    # dump an in-memory list -- which truncated a completed 496-row run to zero
    # bytes before crashing on the missing variable. Nothing here may open the
    # output for writing again: by this point the file IS the result, and the
    # summary's only job is to describe it.
    with args.out.open(encoding="utf-8") as f:
        rows = [json.loads(line) for line in f if line.strip()]

    if not rows:
        print(f"\nNo rows written to {args.out}.")
        return 1

    bands: dict[str, int] = {}
    for row in rows:
        bands[row["meta"]["priority"]] = bands.get(row["meta"]["priority"], 0) + 1
    words = sorted(len(r["meeting_notes"].split()) for r in rows)

    print(f"\nWrote {len(rows)} rows to {args.out}  ({rejected} regenerated)")
    print("Priority mix: " + ", ".join(f"{k}={v}" for k, v in sorted(bands.items())))
    print(f"Note length: min={words[0]} median={words[len(words) // 2]} max={words[-1]}")
    print(f"Total time: {(time.time() - started) / 60:.1f} min")
    return 0


if __name__ == "__main__":
    sys.exit(main())
