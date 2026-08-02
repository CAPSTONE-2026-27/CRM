"""
prompt_format.py
================
Single source of truth for the instruction text, system prompt, and Llama 3.1
chat template shared by train.py and inference.py.

Why this module exists: previously train.py and inference.py each hand-wrote
their own version of the prompt. They had drifted apart — inference.py used a
different system prompt (one that explicitly banned "Recommended Action") and
dropped the instruction line from the user turn entirely. That mismatch means
the model was being asked something different at inference time than what it
was trained on, which is a direct cause of inconsistent output regardless of
how clean the dataset is. Importing from here guarantees train and inference
always build the identical prompt.
"""

import re

INSTRUCTION = (
    "Analyze the following lead and predict Lead Score, Qualification, "
    "Priority and Reason."
)

SYSTEM_PROMPT = (
    "You are an AI CRM Lead Management Assistant.\n\n"
    "Analyze the lead details and respond ONLY in this exact format. "
    "Do not add greetings, markdown, or any commentary outside the format below.\n\n"
    "Lead Score: <0-100>/100\n\n"
    "Qualification:\n"
    "<Hot/Warm/Cold>\n\n"
    "Priority:\n"
    "<High/Medium/Low>\n\n"
    "Reason:\n"
    "• <point 1>\n"
    "• <point 2>\n"
    "• <point 3>\n"
    "• <point 4>\n"
    "• <point 5>\n\n"
    "Recommended Action:\n"
    "<one short instruction>"
)


# Fixed thresholds the training data actually uses: Cold is 5-30, Warm is
# 35-60, Hot is 65-100, with clean unused gaps at 31-34 and 61-64. Midpoints
# of those gaps are used as cutoffs so any score reconstructs the same label
# the dataset would have assigned.
QUALIFICATION_BY_SCORE = [
    (32, "Cold", "Low"),
    (62, "Warm", "Medium"),
    (100, "Hot", "High"),
]

BULLET_POINTS_RE = re.compile(r"contributing (\d+) points")

# Point tiers for the 4 scoring factors that turned out (verified by scanning
# every (value, points) pair actually used in data/train.jsonl -- see
# scripts/ground_reasoning.py's docstring) to be deterministic range lookups,
# not judgment calls. Only Customer Requirement requires reading free text,
# so only its bullet is left to the model. Each tuple is (upper bound
# inclusive, points); cutoffs sit at the midpoint of the gap between two
# tiers in the real data so any value reconstructs the same tier the dataset
# generator would have assigned.
EMPLOYEES_COUNT_TIERS = [(51, 0), (204, 5), (1027, 10), (5015, 15), (10**9, 20)]
PRODUCT_QUANTITY_TIERS = [(10, 0), (50, 5), (101, 10), (500, 15), (10**9, 20)]
DEAL_VALUE_TIERS = [(106_324, 0), (998_899, 5), (5_128_723, 10), (20_242_399, 15), (10**12, 20)]

TIMELINE_POINTS = {
    "More than 3 Months": 0,
    "Within 3 Months": 5,
    "Within 2 Months": 10,
    "Within 1 Month": 15,
    "Within 15 Days": 20,
    "Immediately": 20,
}


def _tier_points(value: int, tiers) -> int:
    for cutoff, pts in tiers:
        if value <= cutoff:
            return pts
    return tiers[-1][1]


def _employees_bullet(value: int, pts: int) -> str:
    if pts >= 20:
        desc = "a large enterprise"
    elif pts >= 15:
        desc = "a mid-to-large company"
    elif pts >= 10:
        desc = "a mid-sized company"
    elif pts >= 5:
        desc = "a small company"
    else:
        desc = "a very small company"
    return f"Employees Count of {value} indicates {desc}, contributing {pts} points."


def _quantity_bullet(value: int, pts: int) -> str:
    if pts >= 20:
        desc = "a large-scale order"
    elif pts >= 15:
        desc = "a sizable order"
    elif pts >= 10:
        desc = "a moderate order size"
    elif pts >= 5:
        desc = "a small order"
    else:
        desc = "a minimal order size"
    return f"Product Quantity of {value} units reflects {desc}, contributing {pts} points."


def _deal_value_bullet(value_str: str, pts: int) -> str:
    if pts >= 20:
        desc = "the highest deal-value tier"
    elif pts >= 15:
        desc = "a high-value deal tier"
    elif pts >= 10:
        desc = "a mid-tier deal range"
    elif pts >= 5:
        desc = "a low-value deal tier"
    else:
        desc = "the lowest deal-value tier"
    return f"Deal Value of {value_str} places this in {desc}, contributing {pts} points."


_TIMELINE_DESCRIPTORS = {
    "Immediately": "signals maximum urgency",
    "Within 15 Days": "signals strong urgency",
    "Within 1 Month": "signals solid urgency",
    "Within 2 Months": "signals moderate urgency",
    "Within 3 Months": "signals mild urgency",
    "More than 3 Months": "signals no near-term urgency",
}


def _timeline_bullet(value_str: str, pts: int) -> str:
    desc = _TIMELINE_DESCRIPTORS.get(value_str, "signals a defined purchase timeline")
    return f'Purchase Timeline of "{value_str}" {desc}, contributing {pts} points.'


# Customer Requirement looks like free text but isn't: scanning every one of
# the 500 examples in data/train.jsonl showed each one starts with exactly
# one of these 20 fixed phrases (optionally followed by one of ~9 context
# clauses), and every occurrence maps 1:1 to the same points value -- zero
# exceptions. So this is a closed set the data generator drew from, not a
# judgment call, and can be made deterministic exactly like the other 4
# factors. If a future lead's Customer Requirement text doesn't match any of
# these (e.g. if this field becomes genuinely free-form in production),
# _requirement_points() returns None and the model's own bullet is left
# untouched -- this only overrides cases we can verify.
CUSTOMER_REQUIREMENT_TIERS = {
    "Wanted to understand what the product does.": 0,
    "Just making a general enquiry.": 0,
    "Reached out with a casual query, nothing specific yet.": 0,
    "Only gathering basic information for now.": 0,
    "Collecting quotations for a future decision.": 5,
    "Exploring options available in the market.": 5,
    "Comparing a few vendors before deciding.": 5,
    "Doing early research on possible solutions.": 5,
    "Interested in sales tracking and email automation.": 10,
    "Looking for a CRM to manage customer records.": 10,
    "Needs a better customer support ticketing system.": 10,
    "Wants an analytics dashboard for the sales team.": 10,
    "Needs ERP integration with the existing system.": 15,
    "Looking specifically for AI-based lead scoring.": 15,
    "Wants workflow automation for the sales pipeline.": 15,
    "Requires SAP integration across departments.": 15,
    "Ready to buy, only finalizing the vendor choice.": 20,
    "Budget already approved and ready to move forward.": 20,
    "Management has approved funds and wants to start soon.": 20,
    "Wants a product demo this week before purchasing.": 20,
}


def _requirement_points(raw: str) -> "int | None":
    for template, pts in CUSTOMER_REQUIREMENT_TIERS.items():
        if raw.startswith(template):
            return pts
    return None


def _requirement_bullet(value_str: str, pts: int) -> str:
    if pts >= 20:
        desc = "confirmed buying intent"
    elif pts >= 15:
        desc = "strong, specific interest"
    elif pts >= 10:
        desc = "moderate, defined interest"
    elif pts >= 5:
        desc = "early-stage, exploratory interest"
    else:
        desc = "no concrete buying intent yet"
    return f'Customer Requirement — "{value_str}" — shows {desc}, contributing {pts} points.'


def _parse_input_fields(lead_input: str) -> dict:
    fields = {}
    for line in lead_input.splitlines():
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return fields


def _corrected_bullet_text(bullet: str, fields: dict) -> str:
    """Recompute a single bullet from the lead's actual input rather than
    trusting what the model wrote, for the 4 factors that have one
    objectively correct answer. Falls back to the model's own bullet
    unchanged if the field is missing or unparseable, and always leaves
    Customer Requirement as the model's own judgment call -- there's no
    formula for reading free text."""
    try:
        if bullet.startswith("Employees Count"):
            value = int(fields["Employees Count"])
            return _employees_bullet(value, _tier_points(value, EMPLOYEES_COUNT_TIERS))
        if bullet.startswith("Product Quantity"):
            value = int(fields["Product Quantity"])
            return _quantity_bullet(value, _tier_points(value, PRODUCT_QUANTITY_TIERS))
        if bullet.startswith("Deal Value"):
            raw = fields["Deal Value"]
            digits = re.sub(r"[^0-9]", "", raw)
            if not digits:
                return bullet
            return _deal_value_bullet(raw, _tier_points(int(digits), DEAL_VALUE_TIERS))
        if bullet.startswith("Purchase Timeline"):
            raw = fields["Purchase Timeline"]
            if raw not in TIMELINE_POINTS:
                return bullet
            return _timeline_bullet(raw, TIMELINE_POINTS[raw])
        if bullet.startswith("Customer Requirement"):
            raw = fields["Customer Requirement"]
            pts = _requirement_points(raw)
            if pts is None:
                return bullet  # doesn't match a known template; trust the model
            return _requirement_bullet(raw, pts)
    except (KeyError, ValueError):
        return bullet
    return bullet  # unrecognized bullet


def _correct_deterministic_bullets(output: str, lead_input: str) -> str:
    fields = _parse_input_fields(lead_input)
    bullet_matches = list(re.finditer(r"^• (.+)$", output, re.MULTILINE))
    if len(bullet_matches) != 5:
        return output  # malformed output; leave it for validation to catch

    corrected = iter(_corrected_bullet_text(m.group(1), fields) for m in bullet_matches)
    return re.sub(r"^• (.+)$", lambda m: f"• {next(corrected)}", output, flags=re.MULTILINE)


def reconcile_output(output: str, lead_input: str = None) -> str:
    """Override Lead Score/Qualification/Priority with what the Reason
    bullets add up to, so the output can never contradict its own stated
    arithmetic. When `lead_input` is given, first replaces all 5 bullets
    with the objectively correct value computed from the lead's actual
    input where possible: Employees Count, Product Quantity, Deal Value,
    and Purchase Timeline are always fixed range/lookup rules, and Customer
    Requirement is corrected too whenever its text matches one of the known
    templates (see CUSTOMER_REQUIREMENT_TIERS) -- otherwise it's left as the
    model's own judgment call, since there's no rule for genuinely novel
    free text.

    Why this exists: the model writes an explicit point value in each of the
    5 Reason bullets, and the dataset was built so Lead Score always equals
    their sum. If the model ever states a Qualification/Priority/Score that
    disagrees with its own bullets (rare, but possible), that is a strictly
    avoidable inconsistency -- the correct values are recoverable from text
    the model already generated.
    """
    if lead_input is not None:
        output = _correct_deterministic_bullets(output, lead_input)

    bullet_points = [int(p) for p in BULLET_POINTS_RE.findall(output)]
    if len(bullet_points) != 5:
        return output  # malformed output; leave it for validation to catch

    total = max(0, min(100, sum(bullet_points)))
    qualification, priority = next(
        (qual, prio) for cutoff, qual, prio in QUALIFICATION_BY_SCORE if total <= cutoff
    )

    output = re.sub(r"Lead Score:\s*\d+/100", f"Lead Score: {total}/100", output)
    output = re.sub(r"(Qualification:\n)[^\n]+", rf"\g<1>{qualification}", output)
    output = re.sub(r"(Priority:\n)[^\n]+", rf"\g<1>{priority}", output)
    return output


def build_user_turn(lead_input: str) -> str:
    """Combine the fixed instruction with the lead's input fields, exactly
    as every row in train.jsonl does."""
    return f"{INSTRUCTION}\n\n{lead_input}"


def build_llama3_prompt(system: str, user: str, assistant: str = "") -> str:
    """Build one example in raw Llama 3.1 Instruct chat format.

    At training time `assistant` is the target output and the trailing
    <|eot_id|> teaches the model where to stop. At inference time `assistant`
    is left empty so the prompt ends right after the assistant header and
    generation starts from there — no trailing <|eot_id|> in that case.
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
