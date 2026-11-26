"""
prompt_format.py
================
Single source of truth, on the Python side, for the Marketing Automation
adapter (Module 11): its two prompts, chat template, request builders and the
output checks the Java backend applies.

Two tasks, one adapter
----------------------
  * Lead evaluation   : (audience brief + rule results + one lead) -> JSON verdict
  * Content generation: (campaign brief + style + channel limits)   -> JSON copy

Both are requests from the same module, share one output discipline (a single
strict-JSON object built only from supplied facts) and are small enough that
one r=16 adapter holds them. Splitting them would cost a second 160MB adapter
and a second routing marker for no measured gain.

Why the prompts are read from the Java resources
------------------------------------------------
The other adapters copy their system prompt into Python and pin the copy with a
test. Here the Java prompts already live as plain files
(backend/src/main/resources/marketing/prompts), so this module reads those
files directly: training, evaluation and serving all see the exact bytes
PromptLibrary.java sends, and there is no second copy to drift.

Mirrors, not authorities
------------------------
validate_content(), parse_lead_evaluation() and the builders below port
ContentOutputValidator, LeadEvaluationValidator, ContentGenerationAi and
LeadEvaluationAi. Java remains the authority at runtime; these exist so the
dataset generator only keeps targets the backend would accept, and so the
evaluator can score replies the way the backend will.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

CONTRACT_VERSION = "1.0.0"

AI_ROOT = Path(__file__).resolve().parent.parent.parent
PROMPTS_DIR = Path(
    os.getenv(
        "CRM_MARKETING_PROMPTS_DIR",
        AI_ROOT.parent / "backend" / "src" / "main" / "resources" / "marketing" / "prompts",
    )
)


def _load(name: str) -> str:
    path = PROMPTS_DIR / name
    if not path.exists():
        raise FileNotFoundError(
            f"Marketing prompt not found at {path}. Set CRM_MARKETING_PROMPTS_DIR "
            "to backend/src/main/resources/marketing/prompts."
        )
    return path.read_text(encoding="utf-8")


LEAD_SYSTEM_PROMPT = _load("lead-evaluation.system.txt")
CONTENT_SYSTEM_PROMPT = _load("content-generation.system.txt")

# ============================================================
# ROUTING MARKERS
# ============================================================
# The opening clause of each Java prompt. server/main.py sends a request to
# this adapter when the system text contains either one. PromptLibrary.java
# refuses to boot if a prompt loses its marker, so rewording a prompt fails
# loudly instead of silently dropping marketing back to the base model.
LEAD_MARKER = "you are the campaign audience analyst"
CONTENT_MARKER = "you are the campaign copywriter"


def is_marketing(system_text: str) -> bool:
    lowered = (system_text or "").lower()
    return LEAD_MARKER in lowered or CONTENT_MARKER in lowered


def is_lead_evaluation(system_text: str) -> bool:
    return LEAD_MARKER in (system_text or "").lower()


# ============================================================
# CHAT TEMPLATE
# ============================================================
# Llama 3.1 Instruct without the stock "Cutting Knowledge Date / Today Date"
# preamble, and with {% generation %} markers so TRL's assistant_only_loss can
# mask the prompt. Same template as deal_state and lead_meeting.
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
    """Install this adapter's template on an in-memory tokenizer."""
    tokenizer.chat_template = CHAT_TEMPLATE
    return tokenizer


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """Byte-identical to CHAT_TEMPLATE for one system/user(/assistant) turn.

    server/main.py renders marketing requests through this, so the adapter
    infers on exactly the string it was trained on.
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


def to_java_json(value) -> str:
    """Serialise as Jackson's default ObjectMapper does: compact, UTF-8 kept."""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def render_target(value: dict) -> str:
    """How targets are written in training: one line, readable separators."""
    return json.dumps(value, ensure_ascii=False)


# ============================================================
# CHANNELS  (ContentChannel.java)
# ============================================================
CHANNEL_FIELDS = {
    "core": {"headline": 90, "promotionalMessage": 600, "shortCopy": 200, "cta": 40},
    "email": {"subject": 120, "preheader": 150, "body": 4000, "cta": 40},
    "poster": {"headline": 70, "subheadline": 140, "body": 400, "cta": 40},
    "sms": {"text": 320},
    "notification": {"title": 65, "body": 180},
    "social": {"post": 1200, "hashtags": 200},
}

# Distilled training copy must stay under this share of every field's limit.
# Trained right up to the limit, the adapter lands just over it a few percent
# of the time -- the September eval rejected a 157-character preheader against
# 150 -- and every overshoot costs the backend a retry. The validator itself
# still enforces the full limit; this applies to training targets only.
LENGTH_MARGIN = 0.9


def over_margin(target: dict, margin: float = LENGTH_MARGIN) -> list:
    """Fields of a content target longer than `margin` of their limit."""
    over = []
    for channel, fields in target.items():
        for key, value in (fields or {}).items():
            limit = CHANNEL_FIELDS.get(channel, {}).get(key)
            if limit and isinstance(value, str) and len(value) > margin * limit:
                over.append(f"{channel}.{key} {len(value)}/{limit}")
    return over


REQUIRED_FIELDS = {
    "core": ["headline", "promotionalMessage", "cta"],
    "email": ["subject", "body", "cta"],
    "poster": ["headline", "body", "cta"],
    "sms": ["text"],
    "notification": ["title", "body"],
    "social": ["post"],
}

# AbTest.Element -> the channel whose variant B is written (CampaignChannels.testedChannel).
AB_ELEMENTS = {
    "EMAIL_SUBJECT": "email",
    "EMAIL_BODY": "email",
    "POSTER_HEADLINE": "poster",
    "CTA": None,               # first deliverable channel configured
    "OFFER_PRESENTATION": None,
    "MESSAGE_WORDING": None,
}
DELIVERABLE = ["email", "sms", "notification"]


# ============================================================
# REQUEST BUILDERS  (LeadEvaluationAi / ContentGenerationAi)
# ============================================================
def build_lead_user_prompt(brief: dict, match: str, rule_results: list, lead: dict) -> str:
    """LeadEvaluationAi.buildUserPrompt."""
    rules = [
        {
            "rule": r["label"],
            "system_check": r["outcome"],
            "lead_value": "not recorded" if r.get("actual") is None else r["actual"],
        }
        for r in rule_results
    ]
    doc = {
        "campaign": brief,
        "eligibility_rules": {"match": match, "rules": rules},
        "lead": lead,
    }
    return to_java_json(doc)


def lead_retry_prompt(user_prompt: str, error: str) -> str:
    return (user_prompt + "\n\nYour previous reply was rejected: " + error
            + ". Reply again with only the JSON object described in your instructions.")


def build_content_user_prompt(brief: dict, style: dict, channels: list,
                              variant: dict | None = None, extra: str | None = None) -> str:
    """ContentGenerationAi.buildUserPrompt."""
    style = dict(style)
    if extra:
        style["regeneration_feedback"] = extra[:500]
    channel_doc = {}
    for ch in channels:
        channel_doc[ch] = {
            field: f"max {limit} characters" + (", required" if field in REQUIRED_FIELDS[ch] else ", optional")
            for field, limit in CHANNEL_FIELDS[ch].items()
        }
    doc = {"brief": brief, "style": style, "channels": channel_doc}
    if variant is not None:
        doc["variant"] = {"element": variant["element"], "variant_a": variant["variant_a"]}
    return to_java_json(doc)


def grounding_text(brief: dict, style: dict) -> str:
    """ContentSource.groundingText: brief values plus additional instructions."""
    return "\n".join(str(v) for v in brief.values()) + "\n" + (style.get("additional_instructions") or "")


# ============================================================
# PARSING
# ============================================================
def extract_object(reply: str):
    """First '{' to last '}', as LeadEvaluationValidator.extractObject does."""
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


def parse_lead_evaluation(reply: str, expected_ref: str) -> dict:
    """LeadEvaluationValidator.parse. Raises ValueError with the Java message."""
    root = extract_object(reply)
    if root is None:
        raise ValueError("Reply was not a JSON object")
    ref = root.get("lead_id")
    if ref is None or str(ref).strip().lower() != expected_ref.lower():
        raise ValueError(f"lead_id did not match the lead that was sent (expected {expected_ref})")
    score = root.get("score")
    if isinstance(score, bool) or not isinstance(score, (int, float)):
        raise ValueError("score must be a number")
    if score < 0 or score > 100:
        raise ValueError("score must be between 0 and 100")
    eligibility = str(root.get("eligibility") or "").strip().lower()
    if eligibility == "eligible":
        eligible = True
    elif eligibility in ("not_eligible", "not eligible", "ineligible"):
        eligible = False
    else:
        raise ValueError("eligibility must be 'eligible' or 'not_eligible'")
    reason = root.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("reason is required")
    for key in ("positive_factors", "negative_factors"):
        value = root.get(key)
        if value is not None and not isinstance(value, list):
            raise ValueError("factors must be lists of strings")
    confidence = root.get("confidence")
    return {
        "lead_id": expected_ref,
        "score": int(round(score)),
        "eligible": eligible,
        "confidence": confidence if isinstance(confidence, (int, float)) and 0 <= confidence <= 1 else None,
        "positive_factors": [f for f in (root.get("positive_factors") or []) if isinstance(f, str)],
        "negative_factors": [f for f in (root.get("negative_factors") or []) if isinstance(f, str)],
        "reason": reason.strip(),
    }


# ============================================================
# CONTENT VALIDATION  (ContentOutputValidator.java)
# ============================================================
_NUMBER = re.compile(r"(?<![\w.])(\d[\d,]*(?:\.\d+)?)\s*(%|percent)?")
_CURRENCY_BEFORE = re.compile(r"(₹|rs\.?|inr|\$|€|£|usd)\s*$", re.IGNORECASE)
_URL = re.compile(r"(?i)\b(?:https?://|www\.)[^\s)\]]+")
_EMAIL = re.compile(r"(?i)\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b")
_TOKEN = re.compile(r"\{\{\s*([\w.]+)\s*}}")
_BRACKET = re.compile(r"\[(?:your|insert|company|name|first|link|date)[^\]]{0,40}]", re.IGNORECASE)
_SCRIPT = re.compile(r"(?i)<\s*script|javascript:|on\w+\s*=")
_TAG = re.compile(r"<[^>]{1,200}>")
_ALLOWED_TOKENS = {"first_name", "company"}

_CLAIMS = [
    (re.compile(r"(?i)\bfree\b"), "free"),
    (re.compile(r"(?i)\bguarantee(?:d|s)?\b"), "guaranteed"),
    (re.compile(r"(?i)\brisk[- ]free\b"), "risk-free"),
    (re.compile(r"(?i)(?:#\s?1\b|\bnumber one\b|\bno\.\s?1\b)"), "#1 / number one"),
    (re.compile(r"(?i)\bcheapest\b"), "cheapest"),
    (re.compile(r"(?i)\blowest price"), "lowest price"),
    (re.compile(r"(?i)\blimited[- ]time\b"), "limited time"),
    (re.compile(r"(?i)\bcertified\b"), "certified"),
    (re.compile(r"(?i)\baward[- ]winning\b"), "award-winning"),
]

_SOFT_CLAIMS = [
    re.compile(r"(?i)\bbest[- ]in[- ]class\b"),
    re.compile(r"(?i)\bindustry[- ]leading\b"),
    re.compile(r"(?i)\bworld[- ]class\b"),
    re.compile(r"(?i)\brevolutionary\b"),
    re.compile(r"(?i)\bunmatched\b"),
    re.compile(r"(?i)\bexclusive\b"),
]


def _strip_zeros(digits: str) -> str:
    if "." not in digits:
        return digits
    trimmed = digits.rstrip("0")
    return trimmed[:-1] if trimmed.endswith(".") else trimmed


def _numbers_in(text: str) -> set:
    out = set()
    for m in _NUMBER.finditer(text):
        digits = m.group(1).replace(",", "")
        if digits.endswith("."):
            digits = digits[:-1]
        out.add(_strip_zeros(digits))
    return out


def validate_content(channel: str, node, grounding: str) -> tuple[dict, list, list]:
    """One channel's object -> (fields, errors, warnings). Errors mean rejected."""
    label = channel.upper()[0] + channel[1:]
    if not isinstance(node, dict):
        return {}, [f"{channel.upper()}: missing from the reply"], []
    limits = CHANNEL_FIELDS[channel]
    source = (grounding or "").lower()
    source_numbers = _numbers_in(source)
    fields, errors, warnings = {}, [], []

    for key, limit in limits.items():
        value = node.get(key)
        if not isinstance(value, str) or not value.strip():
            continue
        value = value.replace("\r\n", "\n").strip()
        if _SCRIPT.search(value):
            errors.append(f"{label} {key}: contains script or event-handler markup")
            continue
        if _TAG.search(value):
            value = _TAG.sub("", value).strip()
            warnings.append(f"{label} {key}: HTML tags were removed")
        if len(value) > limit:
            errors.append(f"{label} {key}: {len(value)} characters, limit is {limit}")
        fields[key] = value

    for required in REQUIRED_FIELDS[channel]:
        if required not in fields:
            errors.append(f"{label}: '{required}' is required")

    text = "\n".join(fields.values())
    lower = text.lower()

    flagged = set()
    for m in _NUMBER.finditer(text):
        digits = m.group(1).replace(",", "")
        if digits.endswith("."):
            digits = digits[:-1]
        percent = m.group(2) is not None
        currency = bool(_CURRENCY_BEFORE.search(text[max(0, m.start() - 5):m.start()]))
        trivial = len(digits) == 1 and not percent and not currency
        if trivial or _strip_zeros(digits) in source_numbers:
            continue
        shown = m.group().strip()
        if shown not in flagged:
            flagged.add(shown)
            errors.append(f"{label}: the figure '{shown}' does not appear in the campaign brief")

    for pattern, what in ((_URL, "link"), (_EMAIL, "email address")):
        for m in pattern.finditer(text):
            found = re.sub(r"[.,;:!?]+$", "", m.group().lower())
            if found not in source:
                errors.append(f"{label}: the {what} '{m.group()}' does not appear in the campaign brief")

    for m in _TOKEN.finditer(text):
        if m.group(1).lower() not in _ALLOWED_TOKENS:
            errors.append(f"{label}: unsupported placeholder {m.group()}")
    bracket = _BRACKET.search(text)
    if bracket:
        errors.append(f"{label}: unfilled placeholder {bracket.group()}")

    for pattern, what in _CLAIMS:
        if pattern.search(lower) and not pattern.search(source):
            errors.append(f"{label}: claims '{what}', which the campaign brief does not state")
    for pattern in _SOFT_CLAIMS:
        if pattern.search(lower) and not pattern.search(source):
            warnings.append(f"{label}: uses '{pattern.pattern}'")

    return fields, errors, warnings


def check_content_reply(reply: str, channels: list, grounding: str) -> tuple[dict, dict]:
    """ContentGenerationAi.check: (accepted fields per channel, errors per channel)."""
    root = extract_object(reply)
    accepted, rejected = {}, {}
    for ch in channels:
        node = None if root is None else root.get(ch)
        fields, errors, _ = validate_content(ch, node, grounding)
        if errors:
            rejected[ch] = errors
        else:
            accepted[ch] = fields
    return accepted, rejected
