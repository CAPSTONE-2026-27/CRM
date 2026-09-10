"""
meeting_prompt_format.py
========================
Single source of truth for the lead qualification-meeting extraction task:
vocabulary, prompt, output parsing, and the rule table the backend scores with.

Task
----
Input : a Sales Executive's qualification meeting notes (free text)
Output: five business signals, as JSON

The model NEVER produces a score. It reads the meeting and names five values;
the Java LeadScoreFluctuationEngine turns those into a meeting score, a priority
band and the lead's updated score.

That split is the whole design. A model asked for both a reading and a number
can return a number its own reading does not support, and afterwards nothing can
say which of the two was wrong. Keeping the arithmetic outside the model means
any score can be recomputed -- and challenged -- from the stored signals months
later, and the weights can be retuned without retraining anything.

Separate from prompt_format.py, which serves the capture-time lead scorer. That
task and this one share a base model and nothing else: different input, different
output, different adapter. Importing across them would couple two things that
must be free to change independently.
"""

import json
import re

CONTRACT_VERSION = "1.0.0"

# ============================================================
# VOCABULARY
# ============================================================
# Mirrors the lead_ai_analysis_vocabulary CHECK in V18 and LeadSignals.java.
# Three copies of one list is not redundancy: the model is the least reliable of
# the three, and each layer catches a different failure.

SENTIMENT = ["Positive", "Neutral", "Negative"]
BUYING_INTENT = ["High", "Medium", "Low"]
DECISION_MAKER = ["Present", "Indirect", "Absent"]
URGENCY = ["High", "Medium", "Low"]
PRODUCT_INTEREST = ["High", "Medium", "Low"]

FIELD_ORDER = [
    "customer_sentiment",
    "buying_intent",
    "decision_maker_involvement",
    "customer_urgency",
    "product_interest_level",
]

ALLOWED_VALUES = {
    "customer_sentiment": SENTIMENT,
    "buying_intent": BUYING_INTENT,
    "decision_maker_involvement": DECISION_MAKER,
    "customer_urgency": URGENCY,
    "product_interest_level": PRODUCT_INTEREST,
}

# Middle of each scale, never the worst. A missing reading means "the notes did
# not say", and scoring that as Negative/Absent/Low would punish the lead for
# the model's failure to parse rather than for anything the customer did.
DEFAULTS = {
    "customer_sentiment": "Neutral",
    "buying_intent": "Medium",
    "decision_maker_involvement": "Indirect",
    "customer_urgency": "Medium",
    "product_interest_level": "Medium",
}

# ============================================================
# RULE TABLE
# ============================================================
# Duplicated from LeadScoreFluctuationEngine.java so the generator can compute
# the ground-truth score for a sampled label set, and so tests can assert the
# two implementations agree. Java remains the authority at runtime -- nothing
# in the serving path reads these numbers.

WEIGHTS = {
    "customer_sentiment": {"Positive": 20, "Neutral": 10, "Negative": 0},
    "buying_intent": {"High": 30, "Medium": 15, "Low": 5},
    "decision_maker_involvement": {"Present": 20, "Indirect": 10, "Absent": 0},
    "customer_urgency": {"High": 15, "Medium": 10, "Low": 5},
    "product_interest_level": {"High": 15, "Medium": 10, "Low": 5},
}

MAX_POINTS = {"customer_sentiment": 20, "buying_intent": 30,
              "decision_maker_involvement": 20, "customer_urgency": 15,
              "product_interest_level": 15}

PRIORITY_BANDS = [(85, "High Priority"), (70, "Medium Priority"),
                  (50, "Low Priority"), (0, "Very Low Priority")]


def meeting_score(signals: dict) -> int:
    """The 0-100 score a signal set earns. Minimum achievable is 15, not 0."""
    return sum(WEIGHTS[field].get(signals.get(field), 0) for field in FIELD_ORDER)


def priority_for(score: int) -> str:
    for floor, band in PRIORITY_BANDS:
        if score >= floor:
            return band
    return PRIORITY_BANDS[-1][1]


# ============================================================
# PROMPT
# ============================================================

INSTRUCTION = (
    "Read the qualification meeting notes and extract the five business signals."
)


def _vocabulary_block() -> str:
    return "\n".join(
        f"- {field}: one of [{', '.join(ALLOWED_VALUES[field])}]" for field in FIELD_ORDER
    )


SYSTEM_PROMPT = (
    "You are a CRM Lead Qualification Analyst.\n\n"
    "You will be given a sales executive's written notes from a qualification "
    "meeting with a customer. Read the notes and extract five business signals.\n\n"
    "You do NOT score the lead. You do not calculate, estimate or mention any "
    "score, rating or number. You only report what the notes show.\n\n"
    "Rules:\n"
    "- Base every value strictly on the notes. Never infer facts that are not there.\n"
    "- The notes will not state these values outright. Read the business meaning: "
    "how the customer reacted, who attended, what they asked for, how soon they "
    "need it, how much of the product interested them.\n"
    "- When the notes are silent on a signal, choose the middle value rather than "
    "guessing a favourable or unfavourable one.\n"
    "- Use ONLY the allowed values below. Never use synonyms.\n\n"
    "Allowed values:\n"
    f"{_vocabulary_block()}\n\n"
    "Guidance on the harder judgements:\n"
    "- decision_maker_involvement is Present when someone who can approve the "
    "purchase attended, Indirect when they were represented or consulted but "
    "absent, Absent when only evaluators or technical staff attended.\n"
    "- buying_intent is High when the customer asked for a proposal, pricing, "
    "contract or implementation plan; Medium when they asked for a demo or "
    "further evaluation; Low when they were gathering information only.\n"
    "- customer_urgency is High when a date, deadline or compelling event was "
    "named; Medium when a rough timeframe was given; Low when none was.\n\n"
    "Respond with ONLY the JSON object below. No prose, no markdown fences, no "
    "explanation, no score.\n\n"
    "{\n"
    '  "customer_sentiment": "",\n'
    '  "buying_intent": "",\n'
    '  "decision_maker_involvement": "",\n'
    '  "customer_urgency": "",\n'
    '  "product_interest_level": ""\n'
    "}"
)


def build_messages(meeting_notes: str, target: dict = None) -> list:
    """The example in conversational form: system / user / (assistant).

    Conversational because TRL's assistant_only_loss -- which masks the prompt so
    gradient flows only through the completion -- requires it. That masking
    matters here: the prompt is a 200-word meeting note and the target is five
    short values, so training on the whole sequence would spend almost all of the
    signal teaching the model to reproduce the notes it was handed.
    """
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": f"{INSTRUCTION}\n\n{meeting_notes.strip()}"},
    ]
    if target is not None:
        messages.append({"role": "assistant", "content": render(target)})
    return messages


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """Render one turn in the exact format train_meeting.py trained against.

    Must stay byte-identical to what train_meeting.CHAT_TEMPLATE produces for
    the same messages, which is standard Llama 3.1 Instruct WITHOUT the
    tokenizer's stock "Cutting Knowledge Date / Today Date" preamble. Rendering
    through the stock template at inference would feed the adapter a prompt it
    never saw in training -- and the replies would stay well-formed while
    quietly getting worse, which is the hardest kind of regression to notice.

    Guarded by tests/test_server.py::test_meeting_extraction_uses_its_own_prompt.
    """
    prompt = (
        "<|begin_of_text|>"
        "<|start_header_id|>system<|end_header_id|>\n\n"
        f"{system.strip()}"
        "<|eot_id|>"
        "<|start_header_id|>user<|end_header_id|>\n\n"
        f"{user.strip()}"
        "<|eot_id|>"
        "<|start_header_id|>assistant<|end_header_id|>\n\n"
    )
    if assistant:
        prompt += f"{assistant.strip()}<|eot_id|>"
    return prompt


def render(signals: dict) -> str:
    """Serialise in canonical field order, as the model must emit it."""
    return json.dumps({field: signals[field] for field in FIELD_ORDER}, indent=2)


# ============================================================
# PARSING
# ============================================================

def extract(reply: str):
    """Pull the JSON object out of a model reply, or None if there isn't one."""
    if not reply:
        return None
    start, end = reply.find("{"), reply.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(reply[start:end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _normalise(value) -> str:
    return re.sub(r"\s+", " ", str(value).strip().lower())


def snap(field: str, raw):
    """Map what the model said onto an accepted value, or None.

    Exact, then case-insensitive, then containment -- enough to absorb
    "positive" and "High intent" without pretending "Excellent" is a value the
    rule table knows how to weigh.
    """
    allowed = ALLOWED_VALUES.get(field)
    if allowed is None or raw is None:
        return None
    value = _normalise(raw)
    if not value:
        return None
    for candidate in allowed:
        if _normalise(candidate) == value:
            return candidate
    best = None
    for candidate in allowed:
        c = _normalise(candidate)
        if c in value or value in c:
            if best is None or len(candidate) > len(best):
                best = candidate
    return best


def coerce(raw: dict):
    """Force a reply into a complete, scoreable signal set.

    Returns (signals, repairs). `repairs` names every field imputed or snapped --
    log it. A reading that needed three repairs and one that needed none produce
    equally confident scores, and only this list tells them apart.
    """
    raw = raw or {}
    signals, repairs = {}, []
    for field in FIELD_ORDER:
        value = raw.get(field)
        snapped = snap(field, value)
        if snapped is None:
            snapped = DEFAULTS[field]
            repairs.append(f"{field}={value!r}->default {snapped!r}")
        elif _normalise(value) != _normalise(snapped):
            repairs.append(f"{field}={value!r}->{snapped!r}")
        signals[field] = snapped
    return signals, repairs
