"""
meeting_prompt_format.py
========================
Single source of truth for the meeting re-scoring task: the system prompt, the
parameter vocabulary, the points policy, and the deterministic reconciliation
that keeps the model's arithmetic honest.

Imported by generate_meeting_dataset.py, train_meeting.py and main.py, so the
prompt used at generation, training and inference time can never drift apart —
the same reason prompt_format.py exists for lead scoring.

The task
--------
Given a lead's profile, its current score, and the rep's raw meeting notes,
extract five signals and re-score the lead from them:

    customer_sentiment            Negative | Neutral | Positive
    buying_intent                 Low | Medium | High
    decision_maker_involvement    No | Indirect | Yes
    customer_urgency              Low | Medium | High | Critical
    product_interest_level        Low | Medium | High | Very High

The vocabulary is not a free choice. It is copied verbatim from
backend/.../dealflow/DealParameters.java, which the deal-flow XGBoost bundle
already accepts in strict mode. Sharing it means a signal means the same thing
whether it was read off a lead meeting or a deal meeting, and it lets
DealParameters.snap() absorb the model's near-misses without a second mapping.
"""

import re

# ============================================================
# PARAMETER VOCABULARY  (mirrors DealParameters.java)
# ============================================================

CUSTOMER_SENTIMENT = "customer_sentiment"
BUYING_INTENT = "buying_intent"
DECISION_MAKER_INVOLVEMENT = "decision_maker_involvement"
CUSTOMER_URGENCY = "customer_urgency"
PRODUCT_INTEREST_LEVEL = "product_interest_level"

ORDERED = [
    CUSTOMER_SENTIMENT,
    BUYING_INTENT,
    DECISION_MAKER_INVOLVEMENT,
    CUSTOMER_URGENCY,
    PRODUCT_INTEREST_LEVEL,
]

DISPLAY_NAMES = {
    CUSTOMER_SENTIMENT: "Customer Sentiment",
    BUYING_INTENT: "Buying Intent",
    DECISION_MAKER_INVOLVEMENT: "Decision Maker Involvement",
    CUSTOMER_URGENCY: "Customer Urgency",
    PRODUCT_INTEREST_LEVEL: "Product Interest Level",
}

ALLOWED_VALUES = {
    CUSTOMER_SENTIMENT: ["Negative", "Neutral", "Positive"],
    BUYING_INTENT: ["Low", "Medium", "High"],
    DECISION_MAKER_INVOLVEMENT: ["No", "Indirect", "Yes"],
    CUSTOMER_URGENCY: ["Low", "Medium", "High", "Critical"],
    PRODUCT_INTEREST_LEVEL: ["Low", "Medium", "High", "Very High"],
}

# ============================================================
# POINTS POLICY
# ============================================================
# Each parameter contributes 0-20 points and the updated score is their sum, so
# the scale is 0-100 by construction — the same shape as the lead scorer's five
# factors. That symmetry is deliberate: a rep reading either score is reading
# the same kind of number, and reconcile_meeting_output() can verify the
# arithmetic exactly as reconcile_output() does for lead scoring.
#
# Spacing follows DealParameters.NUMERIC_ENCODING's calibration rather than
# being uniform. Note in particular that Negative sentiment scores 0, not a
# proportional 2: a customer who was actively negative in a meeting is a much
# stronger signal than a neutral one is a weak one, which is exactly the
# asymmetry that encoding table records.
#
# THIS TABLE IS THE POLICY. It is the one place to tune how a meeting moves a
# score; everything downstream — dataset, training targets, reconciliation — is
# derived from it.
PARAMETER_POINTS = {
    CUSTOMER_SENTIMENT: {"Negative": 0, "Neutral": 10, "Positive": 20},
    BUYING_INTENT: {"Low": 0, "Medium": 10, "High": 20},
    DECISION_MAKER_INVOLVEMENT: {"No": 0, "Indirect": 10, "Yes": 20},
    CUSTOMER_URGENCY: {"Low": 0, "Medium": 10, "High": 15, "Critical": 20},
    PRODUCT_INTEREST_LEVEL: {"Low": 0, "Medium": 10, "High": 15, "Very High": 20},
}

MAX_POINTS_PER_PARAMETER = 20

# Qualification bands, matching the lead scorer's (prompt_format.py) so the two
# scores are read on one scale. Cutoffs are inclusive upper bounds.
QUALIFICATION_BY_SCORE = [
    (32, "Cold", "Low"),
    (62, "Warm", "Medium"),
    (100, "Hot", "High"),
]

# ============================================================
# QUALIFICATION PROBABILITY
# ============================================================
# Deliberately NOT a copy of the score. The score answers "how warm is this
# lead"; the probability answers "how likely is pursuing it to be worth a rep's
# time", and those differ. A lead can be enthusiastic (high sentiment and
# interest) while having nobody who can sign — pleasant, and unlikely to close.
#
# So the probability is weighted toward the three parameters that predict
# closing rather than enthusiasm. The weights sum to 1.0.
PROBABILITY_WEIGHTS = {
    DECISION_MAKER_INVOLVEMENT: 0.40,
    BUYING_INTENT: 0.35,
    CUSTOMER_URGENCY: 0.25,
}


def qualification_probability(parameters: dict) -> int:
    """0-100 confidence that pursuing this lead is worthwhile."""
    total = 0.0
    for name, weight in PROBABILITY_WEIGHTS.items():
        points = PARAMETER_POINTS[name].get(parameters.get(name), 0)
        total += weight * (points / MAX_POINTS_PER_PARAMETER)
    return max(0, min(100, round(total * 100)))


def score_for(parameters: dict) -> int:
    """The updated lead score: the five parameters' points, summed."""
    return max(0, min(100, sum(
        PARAMETER_POINTS[name].get(parameters.get(name), 0) for name in ORDERED
    )))


def qualification_for(score: int) -> tuple:
    """(qualification, priority) for a score, from the shared bands."""
    for cutoff, qualification, priority in QUALIFICATION_BY_SCORE:
        if score <= cutoff:
            return qualification, priority
    return QUALIFICATION_BY_SCORE[-1][1], QUALIFICATION_BY_SCORE[-1][2]


# ============================================================
# PROMPTS
# ============================================================

INSTRUCTION = (
    "Analyze the meeting notes below and re-score the lead. Extract the five "
    "signals, then report the updated Lead Score, Qualification and Summary."
)


def _allowed_values_block() -> str:
    lines = []
    for name in ORDERED:
        values = ALLOWED_VALUES[name]
        lines.append(f"{DISPLAY_NAMES[name]}: one of [{', '.join(values)}]")
    return "\n".join(lines)


SYSTEM_PROMPT = (
    "You are an AI CRM Meeting Analysis Assistant.\n\n"
    "You will be given a lead's profile, its current lead score, and a sales "
    "representative's raw notes from a customer meeting. Read the notes and "
    "extract five signals, then re-score the lead from those signals alone.\n\n"
    "Rules:\n"
    "- Base every signal strictly on the meeting notes. Never infer facts that "
    "are not stated.\n"
    "- When the notes are silent on a signal, choose the neutral or lowest "
    "value rather than guessing a favourable one.\n"
    "- Use ONLY the allowed values listed below.\n\n"
    "Allowed values:\n"
    f"{_allowed_values_block()}\n\n"
    "Respond ONLY in this exact format. Do not add greetings, markdown, or any "
    "commentary outside it.\n\n"
    "Updated Lead Score: <0-100>/100\n\n"
    "Qualification:\n"
    "<Hot/Warm/Cold>\n\n"
    "Priority:\n"
    "<High/Medium/Low>\n\n"
    "Summary:\n"
    "<2-4 sentences describing what happened in the meeting>\n\n"
    "Signals:\n"
    "• Customer Sentiment — <value>, contributing <n> points.\n"
    "• Buying Intent — <value>, contributing <n> points.\n"
    "• Decision Maker Involvement — <value>, contributing <n> points.\n"
    "• Customer Urgency — <value>, contributing <n> points.\n"
    "• Product Interest Level — <value>, contributing <n> points.\n\n"
    "Qualification Probability: <0-100>\n\n"
    "Recommended Action:\n"
    "<one short instruction>"
)


def build_user_turn(meeting_input: str) -> str:
    """Combine the fixed instruction with the meeting block."""
    return f"{INSTRUCTION}\n\n{meeting_input}"


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """One example in raw Llama 3.1 Instruct chat format.

    Identical construction to prompt_format.build_llama3_prompt — duplicated
    rather than imported so the two tasks' prompt builders stay independently
    changeable, which is the whole reason they are separate adapters.
    """
    prompt = (
        "<|begin_of_text|>"
        "<|start_header_id|>system<|end_header_id|>\n\n"
        f"{system}"
        "<|eot_id|>"
        "<|start_header_id|>user<|end_header_id|>\n\n"
        f"{user}"
        "<|eot_id|>"
        "<|start_header_id|>assistant<|end_header_id|>\n\n"
        f"{assistant}"
    )
    if assistant:
        prompt += "<|eot_id|>"
    return prompt


# ============================================================
# OUTPUT PARSING + RECONCILIATION
# ============================================================

SCORE_RE = re.compile(r"Updated Lead Score:\s*(\d+)/100")
QUALIFICATION_RE = re.compile(r"Qualification:\s*\n\s*(\w+)")
PRIORITY_RE = re.compile(r"Priority:\s*\n\s*(\w+)")
SUMMARY_RE = re.compile(r"Summary:\s*\n(.+?)(?:\n\nSignals:|\Z)", re.DOTALL)
PROBABILITY_RE = re.compile(r"Qualification Probability:\s*(\d+)")
ACTION_RE = re.compile(r"Recommended Action:\s*\n(.+)", re.DOTALL)

# "• Customer Sentiment — Positive, contributing 20 points."
SIGNAL_RE = re.compile(
    r"^•\s*(?P<label>[A-Za-z ]+?)\s*[—–-]\s*(?P<value>[A-Za-z ]+?),\s*"
    r"contributing\s*(?P<points>\d+)\s*points\.?$",
    re.MULTILINE,
)

_LABEL_TO_NAME = {label.lower(): name for name, label in DISPLAY_NAMES.items()}


def parse_signals(output: str) -> dict:
    """Extract {parameter_name: value} from the Signals block."""
    found = {}
    for match in SIGNAL_RE.finditer(output):
        name = _LABEL_TO_NAME.get(match.group("label").strip().lower())
        if name is None:
            continue
        value = match.group("value").strip()
        # Snap to the allowed list case-insensitively; an unrecognised value is
        # dropped rather than trusted, so reconciliation treats it as missing.
        for allowed in ALLOWED_VALUES[name]:
            if allowed.lower() == value.lower():
                found[name] = allowed
                break
    return found


def reconcile_meeting_output(output: str) -> str:
    """Recompute every derived number from the signals the model extracted.

    The model's job is to *read* the meeting — which value each signal takes.
    The arithmetic that follows (points per signal, their sum, the qualification
    band, the probability) is a fixed lookup, and letting a language model do
    arithmetic it does not need to do is how outputs come to contradict
    themselves. So the values are the model's; every number is recomputed here.

    Returns the output unchanged when fewer than all five signals parse — a
    malformed reply is left intact for validation to catch rather than being
    half-corrected into something that looks trustworthy.
    """
    signals = parse_signals(output)
    if len(signals) != len(ORDERED):
        return output

    score = score_for(signals)
    qualification, priority = qualification_for(score)
    probability = qualification_probability(signals)

    output = SCORE_RE.sub(f"Updated Lead Score: {score}/100", output)
    output = re.sub(r"(Qualification:\n)[^\n]+", rf"\g<1>{qualification}", output)
    output = re.sub(r"(Priority:\n)[^\n]+", rf"\g<1>{priority}", output)
    output = PROBABILITY_RE.sub(f"Qualification Probability: {probability}", output)

    # Rewrite each signal bullet with the points its value actually earns.
    def _fix(match):
        name = _LABEL_TO_NAME.get(match.group("label").strip().lower())
        value = signals.get(name)
        if name is None or value is None:
            return match.group(0)
        points = PARAMETER_POINTS[name][value]
        return f"• {DISPLAY_NAMES[name]} — {value}, contributing {points} points."

    return SIGNAL_RE.sub(_fix, output)


def build_output(parameters: dict, summary: str, action: str) -> str:
    """Render a complete, self-consistent training target."""
    score = score_for(parameters)
    qualification, priority = qualification_for(score)
    bullets = "\n".join(
        f"• {DISPLAY_NAMES[name]} — {parameters[name]}, "
        f"contributing {PARAMETER_POINTS[name][parameters[name]]} points."
        for name in ORDERED
    )
    return (
        f"Updated Lead Score: {score}/100\n\n"
        f"Qualification:\n{qualification}\n\n"
        f"Priority:\n{priority}\n\n"
        f"Summary:\n{summary}\n\n"
        f"Signals:\n{bullets}\n\n"
        f"Qualification Probability: {qualification_probability(parameters)}\n\n"
        f"Recommended Action:\n{action}"
    )
