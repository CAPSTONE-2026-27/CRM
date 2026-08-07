"""
deal_state_format.py
====================
Single source of truth for the CRM Deal Intelligence task: vocabulary, prompt,
and the coercion that keeps model output consumable by the XGBoost deal scorer.

Task
----
Input : previous CRM Deal State (JSON) + latest meeting notes (free text)
Output: the updated CRM Deal State (JSON), 17 fields

The model never predicts the deal score. It produces the feature vector that
XgBoost/serve_api.py consumes; the regressor produces the score.

Why this vocabulary and not the one in the spec
-----------------------------------------------
XgBoost/deal_score_service.py runs with strict=True, which has two failure
modes and only one is loud:

  * An ordinal column with an unrecognised value raises SchemaError -> HTTP 400.
  * A one-hot column (customer_requirements, risk_factors) with an unrecognised
    value raises nothing. get_dummies makes a column the bundle never saw, the
    reindex drops it, and every requirement/risk feature scores zero. The deal
    still gets a confident score, computed from evidence that vanished.

So every value below is taken from the saved bundle, verified by round-tripping
it through transform_for_inference. Five differences from the written spec were
found that way, each of which would have broken scoring:

  1. relationship_strength is NUMERIC 0-10, not Weak/Moderate/Strong/Very
     Strong. serve_api declares `float = Field(ge=0, le=10)`; a string is a 422
     before the model is consulted. (The ordinal lexicon does map
     "very strong" -> 4, but the model trained on a 1-10 spread, so a
     categorical value would land mid-range and read as mediocre anyway.)
  2. buying_intent has NO "Very High" — see the note on INTENT_VALUES. This one
     is invisible in the source and only the bundle reveals it.
  3. main_objections is a semicolon-joined STRING, not a list.
  4. customer_requirements and risk_factors are SINGLE-VALUED strings from a
     fixed 8-item vocabulary each, not free lists.
  5. Objection/requirement/risk tokens use the trained wording ("Price Too
     High", not "Price"; "Budget Constraints", not "Budget Risk").
"""

import json
import re

# Bumped when the emitted contract changes shape — a field added or removed, a
# value added or retired, a numeric range moved. Consumers pin against this, and
# /v1/schema publishes it, so a silent vocabulary change cannot pass unnoticed.
#
# 1.0.0 — 17 fields, vocabulary verified against deal_score bundle
#         deal_score_v1.0.0_20260802_062230Z
CONTRACT_VERSION = "1.0.0"

# The bundle this contract was verified against. If the deal scorer is
# retrained, re-run tests/test_contract.py::TestLiveBundle before assuming the
# vocabulary still holds — the bundle, not the pipeline source, is the authority.
VERIFIED_AGAINST_BUNDLE = "deal_score_v1.0.0_20260802_062230Z"

# ============================================================
# VOCABULARY  (verified against the saved bundle)
# ============================================================

SENTIMENT_VALUES = ["Negative", "Neutral", "Positive"]

# No "Very High", unlike product_interest_level below — and that asymmetry is
# real, not an oversight.
#
# ORDINAL_LEXICON in deal_score_pipeline.py *does* define "very high": 4 for
# buying_intent, so reading the source says it is accepted. It is not. The
# bundle builds ordinal_maps by intersecting that lexicon with the labels
# actually present in the training CSV, which only ever held Low/Medium/High
# for this column. The deployed model raises SchemaError on "Very High".
# product_interest_level keeps it because the training data does contain it.
INTENT_VALUES = ["Low", "Medium", "High"]

BUDGET_VALUES = ["Not Allocated", "Under Review", "Partially Approved", "Fully Approved"]
DECISION_MAKER_VALUES = ["No", "Indirect", "Yes"]
URGENCY_VALUES = ["Low", "Medium", "High", "Critical"]
INTEREST_VALUES = ["Low", "Medium", "High", "Very High"]
OUTCOME_VALUES = [
    "No Show / Cancelled",
    "Rescheduled",
    "Discussed Requirements",
    "Proposal Sent",
    "Verbal Agreement",
]
YES_NO_VALUES = ["No", "Yes"]
READINESS_VALUES = ["Not Ready", "Partially Ready", "Ready", "Fully Ready"]

# One-hot columns: exactly one value each, never a list.
REQUIREMENT_VALUES = [
    "API / Technical Integration",
    "Basic Feature Set",
    "Compliance-driven Requirements",
    "Customized Integration",
    "Enterprise-grade Security",
    "Multi-department Rollout",
    "Scalable Infrastructure",
    "Standard Package",
]
RISK_VALUES = [
    "No Risk Identified",
    "Budget Constraints",
    "Competitor Pressure",
    "Economic Uncertainty",
    "Internal Politics",
    "Stakeholder Turnover",
    "Technical Concerns",
    "Timeline Conflict",
]

# The one genuine multi-label field. Joined with "; " into a single string.
OBJECTION_TOKENS = [
    "Price Too High",
    "Budget Not Allocated",
    "Competitor Preference",
    "Lack of Internal Buy-in",
    "Long Implementation Time",
    "Missing Features",
    "No Urgent Business Need",
    "Poor Past Experience / Support",
    "Security / Compliance Concerns",
    "Unfavorable Contract Terms",
]
NO_OBJECTIONS = "No Objections"

CATEGORICAL_VALUES = {
    "customer_sentiment": SENTIMENT_VALUES,
    "buying_intent": INTENT_VALUES,
    "budget_status": BUDGET_VALUES,
    "decision_maker_involvement": DECISION_MAKER_VALUES,
    "customer_urgency": URGENCY_VALUES,
    "product_interest_level": INTEREST_VALUES,
    "meeting_outcome": OUTCOME_VALUES,
    "customer_requirements": REQUIREMENT_VALUES,
    "risk_factors": RISK_VALUES,
    "competitor_mention": YES_NO_VALUES,
    "implementation_readiness": READINESS_VALUES,
    "upsell_opportunity": YES_NO_VALUES,
}

NUMERIC_RANGES = {
    "total_meetings": (0, 100),
    "lead_score": (0, 100),
    "relationship_strength": (0, 10),
    "engagement_score": (0, 100),
}

# Emission order, matching the XGBoost dataset's columns. Not required by the
# scorer (it reindexes), but a stable order is one fewer thing to get wrong.
FIELD_ORDER = [
    "total_meetings",
    "lead_score",
    "customer_sentiment",
    "buying_intent",
    "relationship_strength",
    "budget_status",
    "decision_maker_involvement",
    "customer_urgency",
    "main_objections",
    "product_interest_level",
    "meeting_outcome",
    "customer_requirements",
    "risk_factors",
    "competitor_mention",
    "engagement_score",
    "implementation_readiness",
    "upsell_opportunity",
]

# Neutral / most conservative value per field. A missing reading must pull
# toward the middle, never invent a favourable signal.
DEFAULTS = {
    "total_meetings": 1,
    "lead_score": 50,
    "customer_sentiment": "Neutral",
    "buying_intent": "Medium",
    "relationship_strength": 5.0,
    "budget_status": "Under Review",
    "decision_maker_involvement": "Indirect",
    "customer_urgency": "Medium",
    "main_objections": NO_OBJECTIONS,
    "product_interest_level": "Medium",
    "meeting_outcome": "Discussed Requirements",
    "customer_requirements": "Standard Package",
    "risk_factors": "No Risk Identified",
    "competitor_mention": "No",
    "engagement_score": 50,
    "implementation_readiness": "Partially Ready",
    "upsell_opportunity": "No",
}


# ============================================================
# PROMPT
# ============================================================

def _vocabulary_block() -> str:
    lines = [
        "total_meetings: integer, the previous value plus one",
        "lead_score: integer 0-100, carried forward and adjusted only as far as "
        "this meeting justifies",
        "relationship_strength: number 0-10 (0 = hostile, 10 = trusted partner)",
        "engagement_score: integer 0-100",
    ]
    for field in FIELD_ORDER:
        if field in CATEGORICAL_VALUES:
            lines.append(f"{field}: one of [{', '.join(CATEGORICAL_VALUES[field])}]")
    lines.append(
        "main_objections: semicolon-separated selection of ["
        + ", ".join(OBJECTION_TOKENS)
        + f'], or "{NO_OBJECTIONS}" if none remain unresolved'
    )
    return "\n".join(lines)


SYSTEM_PROMPT = (
    "You are CRM Deal Intelligence AI. You extract and update structured CRM "
    "deal state after every customer meeting.\n\n"
    "You are not a chatbot and not an assistant. You never predict the deal "
    "score, never summarise the meeting, and never explain your reasoning. "
    "Your output is consumed directly by a scoring model, so it must be JSON "
    "and nothing else.\n\n"
    "You will be given the previous CRM Deal State as JSON and the latest "
    "meeting notes as free text. Return the CURRENT deal state after this "
    "meeting.\n\n"
    "Rules:\n"
    "- Update only the fields this meeting changes. Carry every other field "
    "forward exactly as it was.\n"
    "- Never remove previously known information unless this meeting "
    "explicitly invalidates it.\n"
    "- Drop an objection from main_objections once the meeting shows it "
    "resolved; add one when the meeting raises it.\n"
    "- Read the business context, not keywords. Infer only what the notes "
    "support. Never invent facts.\n"
    "- Use ONLY the allowed values below. Never use synonyms.\n"
    "- Output all 17 fields. Never leave one blank or omit it.\n\n"
    "Allowed values:\n"
    f"{_vocabulary_block()}\n\n"
    "Respond with ONLY the JSON object. No prose, no markdown fences, no "
    "explanation, no confidence scores."
)

INSTRUCTION = "Update the CRM deal state from the latest meeting notes."


def build_user_turn(previous_state: dict, meeting_notes: str) -> str:
    return (
        f"{INSTRUCTION}\n\n"
        "SECTION 1 - Previous CRM State:\n"
        f"{render_state(previous_state)}\n\n"
        "SECTION 2 - Latest Meeting Notes:\n"
        f"{meeting_notes.strip()}"
    )


# Llama 3.1 Instruct chat format, with two deliberate departures from the
# tokenizer's stock template. Assigned to the tokenizer by BOTH train.py and
# serve.py, so training and inference provably render the same string.
#
# 1. `{% generation %}` markers around the assistant turn. TRL's
#    assistant_only_loss needs them to build a mask of which tokens are the
#    completion; the stock template has none, and trl refuses to patch it
#    ("chat template is not training-compatible"). Without the mask, gradient
#    flows through the prompt too — and here the prompt holds a full 17-field
#    state while the target is a near-copy of it, so most of the learning signal
#    would go into reproducing text the model was already handed, rewarding it
#    for copying the previous state wholesale. Knowing which fields to change is
#    the entire task.
#
# 2. No "Cutting Knowledge Date / Today Date" preamble. The stock template
#    injects one into the system block. It carries no meaning for this task, and
#    a date that shifts between training and serving is exactly the kind of
#    silent prompt drift that makes a model perform worse at inference than its
#    eval loss predicts.
CHAT_TEMPLATE = (
    "{{- bos_token }}"
    "{%- for message in messages %}"
    "{%- if message['role'] == 'assistant' %}"
    "{{- '<|start_header_id|>assistant<|end_header_id|>\n\n' }}"
    "{%- generation %}"
    "{{- message['content'] | trim + '<|eot_id|>' }}"
    "{%- endgeneration %}"
    "{%- else %}"
    "{{- '<|start_header_id|>' + message['role'] + '<|end_header_id|>\n\n' "
    "+ message['content'] | trim + '<|eot_id|>' }}"
    "{%- endif %}"
    "{%- endfor %}"
    "{%- if add_generation_prompt %}"
    "{{- '<|start_header_id|>assistant<|end_header_id|>\n\n' }}"
    "{%- endif %}"
)


def apply_chat_template(tokenizer):
    """Install this project's template. Call before training or serving.

    Mutates only the in-memory tokenizer object — the shared base-model
    directory on disk is never written to, so the lead-scoring service that
    loads the same weights is unaffected.
    """
    tokenizer.chat_template = CHAT_TEMPLATE
    return tokenizer


def build_messages(previous_state: dict, meeting_notes: str, target: str = None) -> list:
    """The example in conversational form: system / user / (assistant).

    This is the canonical representation for both training and inference, and
    the reason it exists rather than a hand-built prompt string:

    TRL's `assistant_only_loss` — which masks the prompt so gradient flows only
    through the completion — requires a conversational dataset. That masking
    matters more here than on most tasks: the prompt contains a full 17-field
    state and the target is a near-copy of it, so training on the whole sequence
    would spend most of the gradient teaching the model to reproduce text it was
    already handed, and reward copying the previous state wholesale. The entire
    task is knowing which fields to change.

    Using messages also means the tokenizer's own chat template is applied at
    both training and inference. Llama 3.1's template injects a "Cutting
    Knowledge Date / Today Date" preamble into the system block that a
    hand-built prompt does not, so mixing the two would train and infer on
    measurably different prompts — the exact drift that makes a model behave
    worse at inference than its eval loss suggests.
    """
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": build_user_turn(previous_state, meeting_notes)},
    ]
    if target is not None:
        messages.append({"role": "assistant", "content": target})
    return messages


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """One example in raw Llama 3.1 Instruct chat format.

    Retained for tests and offline inspection. Production paths use
    build_messages() with the tokenizer's chat template instead — see the note
    there about the date preamble.
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


def render_state(state: dict) -> str:
    """Serialise in canonical field order, as the model must emit it."""
    return json.dumps({field: state[field] for field in FIELD_ORDER}, indent=2)


# ============================================================
# COERCION
# ============================================================

def _normalise(label) -> str:
    return re.sub(r"\s+", " ", str(label).strip().lower())


def snap(field: str, raw) -> "str | None":
    """Map what the model said onto a value the scorer accepts, or None.

    Exact, then case-insensitive, then longest containment — enough to absorb
    "positive", "Fully approved" and "very high" without pretending "Excellent"
    is a value the bundle knows. None tells the caller to impute a default and
    record that it did, which beats passing a label that would be silently
    dropped at one-hot reindex time.
    """
    allowed = CATEGORICAL_VALUES.get(field)
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


def snap_objections(raw) -> str:
    """Normalise main_objections into the semicolon string XGBoost splits.

    Accepts a list — the model will emit one despite the prompt, because the
    original spec asked for one — or a string. Unrecognised tokens are dropped:
    they match no indicator column and would score as absent regardless, so
    dropping them at least makes the loss visible in the output.
    """
    if raw is None:
        return NO_OBJECTIONS
    parts = raw if isinstance(raw, list) else re.split(r"[;,]", str(raw))
    kept = []
    for part in parts:
        value = _normalise(part)
        if not value or value in {"none", "no objections", "no objection", "na", "n/a", "-"}:
            continue
        for token in OBJECTION_TOKENS:
            t = _normalise(token)
            if t == value or value in t or t in value:
                if token not in kept:
                    kept.append(token)
                break
    return "; ".join(kept) if kept else NO_OBJECTIONS


def _clamp_number(field: str, raw, default):
    low, high = NUMERIC_RANGES[field]
    try:
        value = float(str(raw).strip())
    except (TypeError, ValueError):
        return default
    value = max(low, min(high, value))
    return value if field == "relationship_strength" else int(round(value))


def coerce_state(raw: dict) -> tuple:
    """Force a model reply into a state the scorer will accept.

    Returns (state, repairs). `repairs` names every field imputed or snapped —
    log it. A state that needed six repairs and one that needed none produce
    equally confident deal scores, and only this list tells them apart.
    """
    raw = raw or {}
    state, repairs = {}, []

    for field in FIELD_ORDER:
        value = raw.get(field)

        if field in NUMERIC_RANGES:
            coerced = _clamp_number(field, value, DEFAULTS[field])
            if value is None:
                repairs.append(f"{field}=missing")
            else:
                # Numeric comparison, not textual: 5 and 5.0 are the same
                # reading, and reporting that would bury the real repairs.
                try:
                    changed = float(str(value).strip()) != float(coerced)
                except (TypeError, ValueError):
                    changed = True
                if changed:
                    repairs.append(f"{field}={value!r}->{coerced}")
            state[field] = coerced

        elif field == "main_objections":
            coerced = snap_objections(value)
            if value is None:
                repairs.append(f"{field}=missing")
            elif _normalise(value) != _normalise(coerced):
                repairs.append(f"{field}={value!r}->{coerced!r}")
            state[field] = coerced

        else:
            coerced = snap(field, value)
            if coerced is None:
                coerced = DEFAULTS[field]
                repairs.append(f"{field}={value!r}->default {coerced!r}")
            elif _normalise(value) != _normalise(coerced):
                repairs.append(f"{field}={value!r}->{coerced!r}")
            state[field] = coerced

    return state, repairs


def extract_state(reply: str) -> "dict | None":
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
